package library

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

