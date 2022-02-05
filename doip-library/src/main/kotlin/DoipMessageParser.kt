import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.experimental.xor

class IncorrectPatternFormat(message: String) : RuntimeException(message)
class HeaderTooShort(message: String) : RuntimeException(message)
class InvalidPayloadLength(message: String) : RuntimeException(message)
class UnknownPayloadType(message: String) : RuntimeException(message)

fun doipMessage(payloadType: Short, vararg data: Byte): ByteArray {
    val bb = ByteBuffer.allocate(8 + data.size)
    bb.put(0x02)
    bb.put(0xFD.toByte())
    bb.putShort(payloadType)
    bb.putInt(data.size)
    bb.put(data)
    return bb.array()
}

object DoipMessageParser {
    private fun checkSyncPattern(data: ByteBuffer): Boolean {
        if (data[0] != 0x02.toByte() ||
            data[1] != 0x02.toByte() xor 0xFF.toByte()
        ) {
            return false
        }
        return true
    }

    private fun <T> checkPayloadLength(
        expectedLength: Int,
        payloadLength: Int,
        buffer: ByteBuffer,
        block: ByteBuffer.() -> T
    ): DoipUdpMessage {
        if (buffer.limit() - 8 != payloadLength || expectedLength != payloadLength) {
            throw InvalidPayloadLength("Payload isn't the right size ($payloadLength ${buffer.limit() - 8})")
        }
        return block(buffer) as DoipUdpMessage
    }

    fun parseUDP(ba: ByteArray): DoipUdpMessage {
        val data = ByteBuffer.wrap(ba)
        data.rewind()
        data.order(ByteOrder.BIG_ENDIAN)

        // Check header length
        if (data.limit() < 8) {
            throw HeaderTooShort("DoIP UDP message too short for interpretation")
        }
        if (!checkSyncPattern(data)) {
            throw IncorrectPatternFormat("Sync Pattern isn't valid")
        }
        val payloadType = data.getShort(2)
        val payloadLength = data.getInt(4)

        return when (payloadType) {
            TYPE_HEADER_NACK -> checkPayloadLength(1, payloadLength, data) { DoipUdpHeaderNegAck(data[8]) }

            TYPE_UDP_VIR -> checkPayloadLength(0, payloadLength, data) { DoipUdpVehicleInformationRequest() }

            TYPE_UDP_VIR_EID -> checkPayloadLength(
                6,
                payloadLength,
                data
            ) { DoipUdpVehicleInformationRequestWithEid(data.sliceArray(8, 6)) }

            TYPE_UDP_VIR_VIN -> checkPayloadLength(
                17,
                payloadLength,
                data
            ) { DoipUdpVehicleInformationRequestWithVIN(data.sliceArray(8, 17)) }

            TYPE_UDP_VAM -> checkPayloadLength(
                33,
                payloadLength,
                data
            ) {
                DoipUdpVehicleVehicleAnnouncmentMessage(
                    vin = data.sliceArray(8, 17),
                    logicalAddress = data.getShort(25),
                    eid = data.sliceArray(27, 6),
                    gid = data.sliceArray(33, 6),
                    furtherActionRequired = data.get(39),
                    syncStatus = data.get(40)
                )
            }

            TYPE_UDP_ENTITY_STATUS_REQ -> checkPayloadLength(0, payloadLength, data) { DoipUdpEntityStatusRequest() }

            TYPE_UDP_ENTITY_STATUS_RES -> checkPayloadLength(7, payloadLength, data) {
                DoipUdpEntityStatusResponse(
                    nodeType = data.get(8),
                    numberOfSockets = data.get(9),
                    currentNumberOfSockets = data.get(10),
                    maxDataSize = data.getInt(11)
                )
            }

            TYPE_UDP_DIAG_POWER_MODE_REQ -> checkPayloadLength(
                0,
                payloadLength,
                data
            ) { DoipUdpDiagnosticPowerModeRequest() }

            TYPE_UDP_DIAG_POWER_MODE_RES -> checkPayloadLength(
                1,
                payloadLength,
                data
            ) { DoipUdpDiagnosticPowerModeResponse(data.get(8)) }

            else -> throw UnknownPayloadType("$payloadType is an unknown payload type")
        }
    }
}

fun ByteBuffer.sliceArray(index: Int, length: Int): ByteArray {
    val ba = ByteArray(length)
    this.get(index, ba, 0, length)
    return ba
}

