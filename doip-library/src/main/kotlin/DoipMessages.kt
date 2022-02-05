import java.lang.IllegalArgumentException

const val TYPE_HEADER_NACK: Short = 0x0000
const val TYPE_UDP_VIR: Short = 0x0001
const val TYPE_UDP_VIR_EID: Short = 0x0002
const val TYPE_UDP_VIR_VIN: Short = 0x0003
const val TYPE_UDP_VAM: Short = 0x0004
const val TYPE_TCP_ROUTING_REQ: Short = 0x0005
const val TYPE_TCP_ROUTING_RES: Short = 0x0006
const val TYPE_TCP_ALIVE_REQ: Short = 0x0007
const val TYPE_TCP_ALIVE_RES: Short = 0x0008

const val TYPE_UDP_ENTITY_STATUS_REQ: Short = 0x4001
const val TYPE_UDP_ENTITY_STATUS_RES: Short = 0x4002
const val TYPE_UDP_DIAG_POWER_MODE_REQ: Short = 0x4003
const val TYPE_UDP_DIAG_POWER_MODE_RES: Short = 0x4004

const val TYPE_TCP_DIAG_MESSAGE: Short = 0x8001.toShort()
const val TYPE_TCP_DIAG_MESSAGE_POS_ACK: Short = 0x8002.toShort()
const val TYPE_TCP_DIAG_MESSAGE_NEG_ACK: Short = 0x8003.toShort()

open class DoipMessage

open class DoipUdpMessage : DoipMessage()

class DoipUdpHeaderNegAck(val code: Byte) : DoipUdpMessage() {
    val message by lazy { doipMessage(TYPE_HEADER_NACK, code) }
}

class DoipUdpVehicleInformationRequest : DoipUdpMessage() {
    val message by lazy { doipMessage(TYPE_UDP_VIR) }
}

class DoipUdpVehicleInformationRequestWithEid(val eid: ByteArray) : DoipUdpMessage() {
    init {
        if (eid.size != 6) {
            throw IllegalArgumentException("eid must be 6 bytes")
        }
    }
    val message by lazy { doipMessage(TYPE_UDP_VIR_EID, *eid) }
}

class DoipUdpVehicleInformationRequestWithVIN(val vin: ByteArray) : DoipUdpMessage() {
    init {
        if (vin.size != 17) {
            throw IllegalArgumentException("vin must be 17 bytes")
        }
    }
    val message by lazy { doipMessage(TYPE_UDP_VIR_VIN, *vin) }
}

class DoipUdpVehicleAnnouncementMessage(
    val vin: ByteArray,
    val logicalAddress: Short,
    val gid: ByteArray,
    val eid: ByteArray,
    val furtherActionRequired: Byte,
    val syncStatus: Byte
) : DoipUdpMessage() {
    init {
        if (vin.size != 17) {
            throw IllegalArgumentException("vin must be 17 bytes")
        }
        if (gid.size != 6) {
            throw IllegalArgumentException("gid must be 6 bytes")
        }
        if (eid.size != 6) {
            throw IllegalArgumentException("eid must be 6 bytes")
        }
    }

    val message by lazy {
        doipMessage(
            TYPE_UDP_VAM,
            *vin,
            (logicalAddress.toInt() shr 8).toByte(),
            logicalAddress.toByte(),
            *eid,
            *gid,
            furtherActionRequired,
            syncStatus
        )
    }
}

class DoipUdpEntityStatusRequest : DoipUdpMessage() {
    val message by lazy { doipMessage(TYPE_UDP_ENTITY_STATUS_REQ) }
}

class DoipUdpEntityStatusResponse(
    val nodeType: Byte,
    val numberOfSockets: Byte,
    val currentNumberOfSockets: Byte,
    val maxDataSize: Int
) : DoipUdpMessage() {
    val message by lazy {
        doipMessage(
            TYPE_UDP_ENTITY_STATUS_RES,
            nodeType,
            numberOfSockets,
            currentNumberOfSockets,
            *maxDataSize.toByteArray()
        )
    }
}

class DoipUdpDiagnosticPowerModeRequest : DoipUdpMessage() {
    val message by lazy { doipMessage(TYPE_UDP_DIAG_POWER_MODE_REQ) }
}

class DoipUdpDiagnosticPowerModeResponse(val diagPowerMode: Byte) : DoipUdpMessage() {
    val message by lazy { doipMessage(TYPE_UDP_DIAG_POWER_MODE_RES, diagPowerMode) }
}
