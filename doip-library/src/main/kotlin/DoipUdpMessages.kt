open class DoipUdpMessage : DoipMessage()

class DoipUdpHeaderNegAck(val code: Byte) : DoipUdpMessage() {
    companion object {
        const val NACK_INCORRECT_PATTERN_FORMAT: Byte = 0
        const val NACK_UNKNOWN_PAYLOAD_TYPE: Byte = 1
        const val NACK_MESSAGE_TOO_LARGE: Byte = 2
        const val NACK_OUT_OF_MEMORY: Byte = 3
        const val NACK_INVALID_PAYLOAD_LENGTH: Byte = 4
    }

    val message by lazy { doipMessage(TYPE_HEADER_NACK, code) }
}

class DoipUdpVehicleInformationRequest : DoipUdpMessage() {
    val message by lazy { doipMessage(TYPE_UDP_VIR) }
}

class DoipUdpVehicleInformationRequestWithEid(val eid: EID) : DoipUdpMessage() {
    init {
        if (eid.size != 6) {
            throw IllegalArgumentException("eid must be 6 bytes")
        }
    }
    val message by lazy { doipMessage(TYPE_UDP_VIR_EID, *eid) }
}

class DoipUdpVehicleInformationRequestWithVIN(val vin: VIN) : DoipUdpMessage() {
    init {
        if (vin.size != 17) {
            throw IllegalArgumentException("vin must be 17 bytes")
        }
    }
    val message by lazy { doipMessage(TYPE_UDP_VIR_VIN, *vin) }
}

class DoipUdpVehicleAnnouncementMessage(
    val vin: VIN,
    val logicalAddress: Short,
    val gid: GID,
    val eid: EID,
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
