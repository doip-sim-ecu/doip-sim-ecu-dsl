package library

import io.ktor.utils.io.core.*
import kotlin.experimental.inv

open class HeaderNegAckException(message: String) : RuntimeException(message)

class IncorrectPatternFormat(message: String) : HeaderNegAckException(message)
class HeaderTooShort(message: String) : HeaderNegAckException(message)
class InvalidPayloadLength(message: String) : HeaderNegAckException(message)
class UnknownPayloadType(message: String) : HeaderNegAckException(message)

object DoipUdpMessageParser {
    private fun checkSyncPattern(protocolVersion: Byte, inverseProtocolVersion: Byte): Boolean {
        if (protocolVersion != inverseProtocolVersion.inv()) {
            return false
        }
        return true
    }

    private fun <T> checkPayloadLength(
        expectedLength: Int,
        payloadLength: Int,
        brp: ByteReadPacket,
        block: () -> T
    ): DoipUdpMessage {
        if (brp.remaining.toInt() != payloadLength || expectedLength != payloadLength) {
            throw InvalidPayloadLength("Payload isn't the right size ($payloadLength vs. ${brp.remaining})")
        }
        return block() as DoipUdpMessage
    }

    fun parseUDP(ba: ByteArray): DoipUdpMessage =
        parseUDP(ByteReadPacket(ba))

    fun parseUDP(brp: ByteReadPacket): DoipUdpMessage {
        // Check header length
        if (brp.remaining < 8) {
            throw HeaderTooShort("DoIP UDP message too short for interpretation")
        }

        val protocolVersion = brp.readByte()
        val inverseProtocolVersion = brp.readByte()
        if (!checkSyncPattern(protocolVersion, inverseProtocolVersion)) {
            throw IncorrectPatternFormat("Sync Pattern isn't valid")
        }
        val payloadType = brp.readShort()
        val payloadLength = brp.readInt()

        return when (payloadType) {
            TYPE_HEADER_NACK -> checkPayloadLength(1, payloadLength, brp) { DoipUdpHeaderNegAck(brp.readByte()) }

            TYPE_UDP_VIR -> checkPayloadLength(0, payloadLength, brp) { DoipUdpVehicleInformationRequest() }

            TYPE_UDP_VIR_EID -> checkPayloadLength(
                6,
                payloadLength,
                brp,
            ) { DoipUdpVehicleInformationRequestWithEid(brp.readBytes(6)) }

            TYPE_UDP_VIR_VIN -> checkPayloadLength(
                17,
                payloadLength,
                brp
            ) { DoipUdpVehicleInformationRequestWithVIN(brp.readBytes(17)) }

            TYPE_UDP_VAM -> checkPayloadLength(
                33,
                payloadLength,
                brp
            ) {
                DoipUdpVehicleAnnouncementMessage(
                    vin = brp.readBytes(17),
                    logicalAddress = brp.readShort(),
                    eid = brp.readBytes(6),
                    gid = brp.readBytes(6),
                    furtherActionRequired = brp.readByte(),
                    syncStatus = brp.readByte()
                )
            }

            TYPE_UDP_ENTITY_STATUS_REQ -> checkPayloadLength(0, payloadLength, brp) { DoipUdpEntityStatusRequest() }

            TYPE_UDP_ENTITY_STATUS_RES -> checkPayloadLength(7, payloadLength, brp) {
                DoipUdpEntityStatusResponse(
                    nodeType = brp.readByte(),
                    numberOfSockets = brp.readByte(),
                    currentNumberOfSockets = brp.readByte(),
                    maxDataSize = brp.readInt()
                )
            }

            TYPE_UDP_DIAG_POWER_MODE_REQ -> checkPayloadLength(
                0,
                payloadLength,
                brp
            ) { DoipUdpDiagnosticPowerModeRequest() }

            TYPE_UDP_DIAG_POWER_MODE_RES -> checkPayloadLength(
                1,
                payloadLength,
                brp
            ) { DoipUdpDiagnosticPowerModeResponse(brp.readByte()) }

            else -> throw UnknownPayloadType("$payloadType is an unknown payload type")
        }
    }
}

