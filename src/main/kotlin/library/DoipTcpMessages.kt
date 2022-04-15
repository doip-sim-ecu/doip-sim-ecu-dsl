package library

import io.ktor.utils.io.*
import library.DoipUdpMessageHandler.Companion.logger
import java.io.OutputStream
import kotlin.experimental.inv

abstract class DoipTcpMessage : DoipMessage()

open class DoipTcpConnectionMessageHandler(
    val maxPayloadLength: Int = Int.MAX_VALUE
) {

    open suspend fun receiveTcpData(brc: ByteReadChannel): DoipTcpMessage {
        logger.traceIf { "# receiveTcpData" }
        val protocolVersion = brc.readByte()
        val inverseProtocolVersion = brc.readByte()
        if (protocolVersion != inverseProtocolVersion.inv()) {
            throw IncorrectPatternFormat("Invalid header $protocolVersion != $inverseProtocolVersion xor 0xFF")
        }
        val payloadType = brc.readShort()
        val payloadLength = brc.readInt()
        if (payloadLength > maxPayloadLength) {
            throw InvalidPayloadLength("Payload longer than maximum allowed length")
        }
        when (payloadType) {
            TYPE_HEADER_NACK -> {
                val code = brc.readByte()
                return DoipTcpHeaderNegAck(code)
            }
            TYPE_TCP_ROUTING_REQ -> {
                val sourceAddress = brc.readShort()
                val activationType = brc.readByte()
                val reserved = brc.readInt() // Reserved for future standardization use
                val oemData = if (payloadLength > 7) brc.readInt() else null
                return DoipTcpRoutingActivationRequest(sourceAddress, activationType, reserved, oemData)
            }
            TYPE_TCP_ROUTING_RES -> {
                val testerAddress = brc.readShort()
                val entityAddress = brc.readShort()
                val responseCode = brc.readByte()
                brc.readInt() // Reserved for future standardization use
                val oemData = if (payloadLength > 9) brc.readInt() else null
                return DoipTcpRoutingActivationResponse(
                    testerAddress = testerAddress,
                    entityAddress = entityAddress,
                    responseCode = responseCode,
                    oemData = oemData
                )
            }
            TYPE_TCP_ALIVE_REQ -> {
                return DoipTcpAliveCheckRequest()
            }
            TYPE_TCP_ALIVE_RES -> {
                val sourceAddress = brc.readShort()
                return DoipTcpAliveCheckResponse(sourceAddress)
            }
            TYPE_TCP_DIAG_MESSAGE -> {
                val sourceAddress = brc.readShort()
                val targetAddress = brc.readShort()
                val payload = ByteArray(payloadLength - 4)
                brc.readFully(payload, 0, payload.size)
                return DoipTcpDiagMessage(
                    sourceAddress, targetAddress, payload
                )
            }
            TYPE_TCP_DIAG_MESSAGE_POS_ACK -> {
                val sourceAddress = brc.readShort()
                val targetAddress = brc.readShort()
                val ackCode = brc.readByte()
                val payload = ByteArray(payloadLength - 5)
                brc.readFully(payload, 0, payload.size)
                return DoipTcpDiagMessagePosAck(
                    sourceAddress = sourceAddress,
                    targetAddress = targetAddress,
                    ackCode = ackCode,
                    payload = payload
                )
            }
            TYPE_TCP_DIAG_MESSAGE_NEG_ACK -> {
                val sourceAddress = brc.readShort()
                val targetAddress = brc.readShort()
                val ackCode = brc.readByte()
                val payload = ByteArray(payloadLength - 5)
                brc.readFully(payload, 0, payload.size)
                return DoipTcpDiagMessageNegAck(
                    sourceAddress = sourceAddress,
                    targetAddress = targetAddress,
                    nackCode = ackCode,
                    payload = payload
                )
            }
            else -> throw UnknownPayloadType("Unknown payload type $payloadType")
        }
    }

    open suspend fun handleTcpMessage(message: DoipTcpMessage, output: OutputStream) {
        logger.traceIf { "# handleTcpMessage $message" }
        when (message) {
            is DoipTcpHeaderNegAck -> handleTcpHeaderNegAck(message, output)
            is DoipTcpRoutingActivationRequest -> handleTcpRoutingActivationRequest(message, output)
            is DoipTcpRoutingActivationResponse -> handleTcpRoutingActivationResponse(message, output)
            is DoipTcpAliveCheckRequest -> handleTcpAliveCheckRequest(message, output)
            is DoipTcpAliveCheckResponse -> handleTcpAliveCheckResponse(message, output)
            is DoipTcpDiagMessage -> handleTcpDiagMessage(message, output)
            is DoipTcpDiagMessagePosAck -> handleTcpDiagMessagePosAck(message, output)
            is DoipTcpDiagMessageNegAck -> handleTcpDiagMessageNegAck(message, output)
        }
    }

    protected open suspend fun handleTcpHeaderNegAck(message: DoipTcpHeaderNegAck, output: OutputStream) {
        logger.traceIf { "# handleTcpHeaderNegAck $message" }
    }

    protected open suspend fun handleTcpRoutingActivationRequest(message: DoipTcpRoutingActivationRequest, output: OutputStream) {
        logger.traceIf { "# handleTcpRoutingActivationRequest $message" }
    }

    protected open suspend fun handleTcpRoutingActivationResponse(message: DoipTcpRoutingActivationResponse, output: OutputStream) {
        logger.traceIf { "# handleTcpRoutingActivationResponse $message" }
    }

    protected open suspend fun handleTcpAliveCheckRequest(message: DoipTcpAliveCheckRequest, output: OutputStream) {
        logger.traceIf { "# handleTcpAliveCheckRequest $message" }
    }

    protected open suspend fun handleTcpAliveCheckResponse(message: DoipTcpAliveCheckResponse, output: OutputStream) {
        logger.traceIf { "# handleTcpAliveCheckResponse $message" }
    }

    protected open suspend fun handleTcpDiagMessage(message: DoipTcpDiagMessage, output: OutputStream) {
        logger.traceIf { "# handleTcpDiagMessage $message for ${message.targetAddress}" }
    }

    protected open suspend fun handleTcpDiagMessagePosAck(message: DoipTcpDiagMessagePosAck, output: OutputStream) {
        logger.traceIf { "# handleTcpDiagMessagePosAck $message for ${message.targetAddress}" }
    }

    protected open suspend fun handleTcpDiagMessageNegAck(message: DoipTcpDiagMessageNegAck, output: OutputStream) {
        logger.traceIf { "# handleTcpDiagMessageNegAck $message for ${message.targetAddress}" }
    }
}

class DoipTcpHeaderNegAck(
    val code: Byte
) : DoipTcpMessage() {
    override val asByteArray: ByteArray
        get() = doipMessage(TYPE_HEADER_NACK, code)
}

class DoipTcpRoutingActivationRequest(
    val sourceAddress: Short,
    val activationType: Byte = ACTIVATION_TYPE_DEFAULT,
    val reserved: Int = 0,
    val oemData: Int? = null
) : DoipTcpMessage() {
    override val asByteArray: ByteArray
        get() =
            doipMessage(
                TYPE_TCP_ROUTING_REQ,
                *sourceAddress.toByteArray(),
                activationType,
                *reserved.toByteArray(),
                *(oemData?.toByteArray() ?: ByteArray(0))
            )
    @Suppress("unused")
    companion object {
        const val ACTIVATION_TYPE_DEFAULT: Byte = 0x00
        const val ACTIVATION_TYPE_REGULATORY: Byte = 0x01
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

    override val asByteArray: ByteArray
        get() =
            doipMessage(
                TYPE_TCP_ROUTING_RES,
                *testerAddress.toByteArray(),
                *entityAddress.toByteArray(),
                responseCode,
                0, 0, 0, 0, // Reserved for standardization use.
                *(oemData?.toByteArray() ?: ByteArray(0))
            )
}

class DoipTcpAliveCheckRequest : DoipTcpMessage() {
    override val asByteArray: ByteArray
        get() = doipMessage(TYPE_TCP_ALIVE_REQ)
}

class DoipTcpAliveCheckResponse(val sourceAddress: Short) : DoipTcpMessage() {
    override val asByteArray: ByteArray
        get() = doipMessage(TYPE_TCP_ALIVE_RES, *sourceAddress.toByteArray())
}

class DoipTcpDiagMessage(
    val sourceAddress: Short,
    val targetAddress: Short,
    val payload: ByteArray
) : DoipTcpMessage() {
    override val asByteArray: ByteArray
        get() =
            doipMessage(
                TYPE_TCP_DIAG_MESSAGE, *sourceAddress.toByteArray(), *targetAddress.toByteArray(), *payload
            )
}

class DoipTcpDiagMessagePosAck(
    val sourceAddress: Short,
    val targetAddress: Short,
    val ackCode: Byte,
    val payload: ByteArray = ByteArray(0)
) : DoipTcpMessage() {
    override val asByteArray: ByteArray
        get() = doipMessage(
            TYPE_TCP_DIAG_MESSAGE_POS_ACK, *sourceAddress.toByteArray(), *targetAddress.toByteArray(), ackCode, *payload
        )
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

    override val asByteArray: ByteArray
        get() = doipMessage(
            TYPE_TCP_DIAG_MESSAGE_NEG_ACK,
            *sourceAddress.toByteArray(),
            *targetAddress.toByteArray(),
            nackCode,
            *payload
        )
}
