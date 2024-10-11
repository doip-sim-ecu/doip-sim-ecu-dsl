package library

import io.ktor.utils.io.core.*
import kotlinx.io.Source
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlin.experimental.inv

public open class HeaderNegAckException(message: String) : RuntimeException(message)

public class IncorrectPatternFormat(message: String) : HeaderNegAckException(message)
public class HeaderTooShort(message: String) : HeaderNegAckException(message)
public class InvalidPayloadLength(message: String) : HeaderNegAckException(message)
public class UnknownPayloadType(message: String) : HeaderNegAckException(message)

public object DoipUdpMessageParser {
    private fun checkSyncPattern(protocolVersion: Byte, inverseProtocolVersion: Byte): Boolean {
        return protocolVersion == inverseProtocolVersion.inv()
    }

    private inline fun <T> checkPayloadLength(
        expectedLength: Int,
        payloadLength: Int,
        brp: Source,
        block: () -> T
    ): DoipUdpMessage {
        if (brp.remaining.toInt() != payloadLength || expectedLength != payloadLength) {
            throw InvalidPayloadLength("Payload isn't the right size ($payloadLength vs. ${brp.remaining})")
        }
        return block() as DoipUdpMessage
    }

    public fun parseUDP(ba: ByteArray): DoipUdpMessage =
        parseUDP(ba.inputStream().asSource().buffered())

    public fun parseUDP(brp: Source): DoipUdpMessage {
        // Check header length
        if (brp.exhausted()) {
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
            TYPE_HEADER_NACK ->
                checkPayloadLength(1, payloadLength, brp) { DoipUdpHeaderNegAck(brp.readByte()) }

            TYPE_UDP_VIR ->
                checkPayloadLength(0, payloadLength, brp) { DoipUdpVehicleInformationRequest() }

            TYPE_UDP_VIR_EID ->
                checkPayloadLength(
                    6,
                    payloadLength,
                    brp,
                ) { DoipUdpVehicleInformationRequestWithEid(brp.readByteArray(6)) }

            TYPE_UDP_VIR_VIN ->
                checkPayloadLength(
                    17,
                    payloadLength,
                    brp
                ) { DoipUdpVehicleInformationRequestWithVIN(brp.readByteArray(17)) }

            TYPE_UDP_VAM ->
                checkPayloadLength(
                    33,
                    payloadLength,
                    brp
                ) {
                    DoipUdpVehicleAnnouncementMessage(
                        vin = brp.readByteArray(17),
                        logicalAddress = brp.readShort(),
                        eid = brp.readByteArray(6),
                        gid = brp.readByteArray(6),
                        furtherActionRequired = brp.readByte(),
                        syncStatus = brp.readByte()
                    )
                }

            TYPE_UDP_ENTITY_STATUS_REQ ->
                checkPayloadLength(0, payloadLength, brp) { DoipUdpEntityStatusRequest() }

            TYPE_UDP_ENTITY_STATUS_RES ->
                checkPayloadLength(7, payloadLength, brp) {
                    DoipUdpEntityStatusResponse(
                        nodeType = brp.readByte(),
                        numberOfSockets = brp.readByte(),
                        currentNumberOfSockets = brp.readByte(),
                        maxDataSize = brp.readInt()
                    )
                }

            TYPE_UDP_DIAG_POWER_MODE_REQ ->
                checkPayloadLength(
                    0,
                    payloadLength,
                    brp
                ) { DoipUdpDiagnosticPowerModeRequest() }

            TYPE_UDP_DIAG_POWER_MODE_RES ->
                checkPayloadLength(
                    1,
                    payloadLength,
                    brp
                ) { DoipUdpDiagnosticPowerModeResponse(brp.readByte()) }

            else ->
                throw UnknownPayloadType("$payloadType is an unknown payload type")
        }
    }
}

