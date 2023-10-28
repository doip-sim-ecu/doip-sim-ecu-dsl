package library

public abstract class DoipUdpMessage : DoipMessage

public class DoipUdpHeaderNegAck(public val code: Byte) : DoipUdpMessage() {
    @Suppress("unused")
    public companion object {
        public const val NACK_INCORRECT_PATTERN_FORMAT: Byte = 0
        public const val NACK_UNKNOWN_PAYLOAD_TYPE: Byte = 1
        public const val NACK_MESSAGE_TOO_LARGE: Byte = 2
        public const val NACK_OUT_OF_MEMORY: Byte = 3
        public const val NACK_INVALID_PAYLOAD_LENGTH: Byte = 4
    }

    override val asByteArray: ByteArray
        get() = doipMessage(TYPE_HEADER_NACK, code)
}

public class DoipUdpVehicleInformationRequest : DoipUdpMessage() {
    override val asByteArray: ByteArray
        get() = doipMessage(TYPE_UDP_VIR)
}

public class DoipUdpVehicleInformationRequestWithEid(public val eid: EID) : DoipUdpMessage() {
    init {
        if (eid.size != 6) {
            throw IllegalArgumentException("eid must be 6 bytes")
        }
    }

    override val asByteArray: ByteArray
        get() = doipMessage(TYPE_UDP_VIR_EID, *eid)
}

public class DoipUdpVehicleInformationRequestWithVIN(public val vin: VIN) : DoipUdpMessage() {
    init {
        if (vin.size != 17) {
            throw IllegalArgumentException("vin must be 17 bytes")
        }
    }

    override val asByteArray: ByteArray
        get() = doipMessage(TYPE_UDP_VIR_VIN, *vin)
}

public class DoipUdpVehicleAnnouncementMessage(
    public val vin: VIN,
    public val logicalAddress: Short,
    public val gid: GID,
    public val eid: EID,
    public val furtherActionRequired: Byte = 0,
    public val syncStatus: Byte = 0,
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

    override val asByteArray: ByteArray
        get() =
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

public class DoipUdpEntityStatusRequest : DoipUdpMessage() {
    override val asByteArray: ByteArray
        get() = doipMessage(TYPE_UDP_ENTITY_STATUS_REQ)
}

public class DoipUdpEntityStatusResponse(
    public val nodeType: Byte,
    public val numberOfSockets: Byte,
    public val currentNumberOfSockets: Byte,
    public val maxDataSize: Int
) : DoipUdpMessage() {
    override val asByteArray: ByteArray
        get() =
            doipMessage(
                TYPE_UDP_ENTITY_STATUS_RES,
                nodeType,
                numberOfSockets,
                currentNumberOfSockets,
                *maxDataSize.toByteArray()
            )
}

public class DoipUdpDiagnosticPowerModeRequest : DoipUdpMessage() {
    override val asByteArray: ByteArray
        get() = doipMessage(TYPE_UDP_DIAG_POWER_MODE_REQ)
}

public class DoipUdpDiagnosticPowerModeResponse(public val diagPowerMode: Byte) : DoipUdpMessage() {
    override val asByteArray: ByteArray
        get() = doipMessage(TYPE_UDP_DIAG_POWER_MODE_RES, diagPowerMode)
}
