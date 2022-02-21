package library

import io.ktor.utils.io.*
import java.io.OutputStream

open class DoipTcpMessage

interface DoipTcpConnectionMessageHandler {
    suspend fun receiveTcpData(brc: ByteReadChannel): DoipTcpMessage
    suspend fun handleTcpMessage(message: DoipTcpMessage, output: OutputStream)

    fun getRegisteredSourceAddress(): Short?
}

class DoipTcpHeaderNegAck(
    val code: Byte
) : DoipTcpMessage() {
    val message by lazy { doipMessage(TYPE_HEADER_NACK, code) }
}

class DoipTcpRoutingActivationRequest(
    val sourceAddress: Short,
    val activationType: Byte,
    val oemData: Int? = null
) : DoipTcpMessage() {
    val message by lazy {
        doipMessage(TYPE_TCP_ROUTING_REQ, *sourceAddress.toByteArray(), activationType, *(oemData?.toByteArray() ?: ByteArray(0)))
    }
}

class DoipTcpRoutingActivationResponse(
    val testerAddress: Short,
    val entityAddress: Short,
    val responseCode: Byte,
    val oemData: Int? = null
) : DoipTcpMessage() {
    @Suppress("unused")
    companion object {
        const val RC_ERROR_UNKNOWN_SOURCE_ADDRESS: Byte = 0x00
        const val RC_ERROR_TCP_DATA_SOCKETS_EXHAUSTED: Byte = 0x01
        const val RC_ERROR_DIFFERENT_SOURCE_ADDRESS: Byte = 0x02
        const val RC_ERROR_SOURCE_ADDRESS_ALREADY_ACTIVE: Byte = 0x03
        const val RC_ERROR_AUTHENTICATION_MISSING: Byte = 0x04
        const val RC_ERROR_CONFIRMATION_REJECTED: Byte = 0x05
        const val RC_ERROR_UNSUPPORTED_ACTIVATION_TYPE: Byte = 0x06
        const val RC_ERROR_REQUIRES_TLS: Byte = 0x07
        const val RC_OK: Byte = 0x10
        const val RC_OK_REQUIRES_CONFIRMATION: Byte = 0x11
    }
    val message by lazy {
        doipMessage(
            TYPE_TCP_ROUTING_RES,
            *testerAddress.toByteArray(),
            *entityAddress.toByteArray(),
            responseCode,
            0, 0, 0, 0, // Reserved for standardization use.
            *(oemData?.toByteArray() ?: ByteArray(0))
        )
    }
}

class DoipTcpAliveCheckRequest : DoipTcpMessage() {
    val message by lazy { doipMessage(TYPE_TCP_ALIVE_REQ) }
}

class DoipTcpAliveCheckResponse(val sourceAddress: Short) : DoipTcpMessage() {
    val message by lazy { doipMessage(TYPE_TCP_ALIVE_RES) }
}

class DoipTcpDiagMessage(
    val sourceAddress: Short,
    val targetAddress: Short,
    val payload: ByteArray
) : DoipTcpMessage() {
    val message by lazy {
        doipMessage(
            TYPE_TCP_DIAG_MESSAGE, *sourceAddress.toByteArray(), *targetAddress.toByteArray(), *payload
        )
    }
}

class DoipTcpDiagMessagePosAck(
    val sourceAddress: Short,
    val targetAddress: Short,
    val ackCode: Byte,
    val payload: ByteArray = ByteArray(0)
) : DoipTcpMessage() {
    val message by lazy { doipMessage(TYPE_TCP_DIAG_MESSAGE_POS_ACK, *sourceAddress.toByteArray(), *targetAddress.toByteArray(), ackCode, *payload) }
}

class DoipTcpDiagMessageNegAck(
    val sourceAddress: Short,
    val targetAddress: Short,
    val nackCode: Byte,
    val payload: ByteArray = ByteArray(0)
) : DoipTcpMessage() {
    @Suppress("unused")
    companion object {
        const val NACK_CODE_INVALID_SOURCE_ADDRESS: Byte = 0x02
        const val NACK_CODE_UNKNOWN_TARGET_ADDRESS: Byte = 0x03
        const val NACK_CODE_DIAGNOSTIC_MESSAGE_TOO_LARGE: Byte = 0x04
        const val NACK_CODE_OUT_OF_MEMORY: Byte = 0x05
        const val NACK_CODE_TARGET_UNREACHABLE: Byte = 0x06
        const val NACK_CODE_UNKNOWN_NETWORK: Byte = 0x07
        const val NACK_CODE_TRANSPORT_PROTOCOL_ERROR: Byte = 0x08
    }
    val message by lazy { doipMessage(TYPE_TCP_DIAG_MESSAGE_NEG_ACK, *sourceAddress.toByteArray(), *targetAddress.toByteArray(), nackCode, *payload) }
}
