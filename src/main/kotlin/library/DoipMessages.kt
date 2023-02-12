package library

public const val TYPE_HEADER_NACK: Short = 0x0000
public const val TYPE_UDP_VIR: Short = 0x0001
public const val TYPE_UDP_VIR_EID: Short = 0x0002
public const val TYPE_UDP_VIR_VIN: Short = 0x0003
public const val TYPE_UDP_VAM: Short = 0x0004
public const val TYPE_TCP_ROUTING_REQ: Short = 0x0005
public const val TYPE_TCP_ROUTING_RES: Short = 0x0006
public const val TYPE_TCP_ALIVE_REQ: Short = 0x0007
public const val TYPE_TCP_ALIVE_RES: Short = 0x0008

public const val TYPE_UDP_ENTITY_STATUS_REQ: Short = 0x4001
public const val TYPE_UDP_ENTITY_STATUS_RES: Short = 0x4002
public const val TYPE_UDP_DIAG_POWER_MODE_REQ: Short = 0x4003
public const val TYPE_UDP_DIAG_POWER_MODE_RES: Short = 0x4004

public const val TYPE_TCP_DIAG_MESSAGE: Short = 0x8001.toShort()
public const val TYPE_TCP_DIAG_MESSAGE_POS_ACK: Short = 0x8002.toShort()
public const val TYPE_TCP_DIAG_MESSAGE_NEG_ACK: Short = 0x8003.toShort()

public abstract class DoipMessage {
    public abstract val asByteArray: ByteArray
}

