import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlin.experimental.xor

open class DoipTcpMessage

interface DoipTcpConnectionMessageHandler {
    suspend fun receiveTcpData(brc: ByteReadChannel): DoipTcpMessage
    suspend fun handleTcpMessage(message: DoipTcpMessage, output: ByteWriteChannel)
    suspend fun isAutoFlushEnabled(): Boolean
}

class DoipTcpHeaderNegAck(
    val code: Byte
) : DoipTcpMessage() {
    val message by lazy { doipMessage(TYPE_HEADER_NACK, code) }
}

class DoipTcpRoutingActivationRequest(
    val sourceAddress: Short,
    val activationType: Byte,
    val oemData: Int = -1
) : DoipTcpMessage() {
    val message by lazy {
        if (oemData != -1)
            doipMessage(TYPE_TCP_ROUTING_REQ, *sourceAddress.toByteArray(), activationType, *oemData.toByteArray())
        else
            doipMessage(TYPE_TCP_ROUTING_REQ, *sourceAddress.toByteArray(), activationType)
    }
}

class DoipTcpRoutingActivationResponse(
    val testerAddress: Short,
    val entityAddress: Short,
    val responseCode: Byte
) : DoipTcpMessage() {
    @Suppress("unused")
    companion object {
        const val RC_ERROR_UNKNOWN_SOURCE_ADDRESS: Byte = 0x00
        const val RC_ERROR_TCP_DATA_SOCKETS_EXHAUSED: Byte = 0x01
        const val RC_ERROR_DIFFERENT_SOURCE_ADDRESS: Byte = 0x02
        const val RC_ERROR_SOURCE_ADDRESS_ALREADY_ACTIVE: Byte = 0x03
        const val RC_ERROR_AUTHENTICATION_MISSING: Byte = 0x04
        const val RC_ERROR_CONFIRMATION_REJECTED: Byte = 0x05
        const val RC_ERROR_UNSUPPORTED_ACTIVATION_TYPE: Byte = 0x06
        const val RC_OK: Byte = 0x10
        const val RC_OK_REQUIRES_CONFIRMATION: Byte = 0x11
    }
    val message by lazy {
        doipMessage(
            TYPE_TCP_ROUTING_RES, *testerAddress.toByteArray(), *entityAddress.toByteArray(), responseCode
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

private suspend fun ByteReadChannel.discardIf(condition: Boolean, n: Int) {
    if (condition) {
        this.discardExact(n.toLong())
    }
}

interface DiagnosticMessageHandler {
    fun existsTargetAddress(targetAddress: Short): Boolean
    suspend fun onIncomingDiagMessage(diagMessage: DoipTcpDiagMessage, output: ByteWriteChannel)
}

open class DefaultDoipTcpConnectionMessageHandler(
    val socket: Socket,
    val logicalAddress: Short,
    val maxPayloadLength: Int,
    val diagMessageHandler: DiagnosticMessageHandler) :
    DoipTcpConnectionMessageHandler {

    override suspend fun receiveTcpData(brc: ByteReadChannel): DoipTcpMessage {
        val protocolVersion = brc.readByte()
        val inverseProtocolVersion = brc.readByte()
        if (protocolVersion != 0x02.toByte() || protocolVersion != (inverseProtocolVersion xor 0xFF.toByte())) {
            throw IncorrectPatternFormat("Invalid header")
        }
        val payloadType = brc.readShort()
        val payloadLength = brc.readInt()
        if (payloadLength > maxPayloadLength) {
            throw InvalidPayloadLength("Payload longer than maximum allowed length")
        }
        when (payloadType) {
            TYPE_HEADER_NACK -> {
                val code = brc.readByte()
                brc.discardIf(payloadLength > 1, payloadLength - 1)
                return DoipTcpHeaderNegAck(code)
            }
            TYPE_TCP_ROUTING_REQ -> {
                val sourceAddress = brc.readShort()
                val activationType = brc.readByte()
                val oemData = if (payloadLength == 11) brc.readInt() else -1
                brc.discardIf(payloadLength > 11, payloadLength - 11)
                return DoipTcpRoutingActivationRequest(sourceAddress, activationType, oemData)
            }
            TYPE_TCP_ROUTING_RES -> {
                val testerAddress = brc.readShort()
                val entityAddress = brc.readShort()
                val responseCode = brc.readByte()
                brc.discardIf(payloadLength > 5, payloadLength - 5)
                return DoipTcpRoutingActivationResponse(
                    testerAddress = testerAddress, entityAddress = entityAddress, responseCode = responseCode
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

    override suspend fun handleTcpMessage(message: DoipTcpMessage, output: ByteWriteChannel) {
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
        output.flush()
    }

    override suspend fun isAutoFlushEnabled(): Boolean = false

    protected open suspend fun handleTcpHeaderNegAck(message: DoipTcpHeaderNegAck, output: ByteWriteChannel) {
        // No implementation
    }

    protected open suspend fun handleTcpRoutingActivationRequest(message: DoipTcpRoutingActivationRequest, output: ByteWriteChannel) {
        if (message.activationType != 0x00.toByte() && message.activationType != 0x01.toByte()) {
            output.writeFully(
                DoipTcpRoutingActivationResponse(
                    message.sourceAddress,
                    logicalAddress,
                    DoipTcpRoutingActivationResponse.RC_ERROR_UNSUPPORTED_ACTIVATION_TYPE
                ).message
            )
        } else {
            output.writeFully(
                DoipTcpRoutingActivationResponse(
                    message.sourceAddress,
                    logicalAddress,
                    DoipTcpRoutingActivationResponse.RC_OK
                ).message
            )
        }
    }

    protected open suspend fun handleTcpRoutingActivationResponse(message: DoipTcpRoutingActivationResponse, output: ByteWriteChannel) {
        // No implementation
    }

    protected open suspend fun handleTcpAliveCheckRequest(message: DoipTcpAliveCheckRequest, output: ByteWriteChannel) {
        output.writeFully(DoipTcpAliveCheckResponse(logicalAddress).message)
    }

    protected open suspend fun handleTcpAliveCheckResponse(message: DoipTcpAliveCheckResponse, output: ByteWriteChannel) {
        // No implementation
    }

    protected open suspend fun handleTcpDiagMessage(message: DoipTcpDiagMessage, output: ByteWriteChannel) {
        if (diagMessageHandler.existsTargetAddress(message.targetAddress)) {
            // Acknowledge message
            val ack = DoipTcpDiagMessagePosAck(
                message.targetAddress,
                message.sourceAddress,
                0x00
            )
            output.writeFully(ack.message)

            diagMessageHandler.onIncomingDiagMessage(message, output)
        } else {
            // Reject message with unknown target address
            val reject = DoipTcpDiagMessageNegAck(
                message.targetAddress,
                message.sourceAddress,
                DoipTcpDiagMessageNegAck.NACK_CODE_UNKNOWN_TARGET_ADDRESS
            )
            output.writeFully(reject.message)
        }
    }

    protected open suspend fun handleTcpDiagMessagePosAck(message: DoipTcpDiagMessagePosAck, output: ByteWriteChannel) {
        // No implementation
    }

    protected open suspend fun handleTcpDiagMessageNegAck(message: DoipTcpDiagMessageNegAck, output: ByteWriteChannel) {
        // No implementation
    }

}
