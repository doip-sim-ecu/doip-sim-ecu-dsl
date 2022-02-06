package library

import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlin.experimental.xor

open class DefaultDoipTcpConnectionMessageHandler(
    val socket: Socket,
    val logicalAddress: Short,
    val maxPayloadLength: Int,
    val diagMessageHandler: DiagnosticMessageHandler
) :
    DoipTcpConnectionMessageHandler {

    override suspend fun receiveTcpData(brc: ByteReadChannel): DoipTcpMessage {
        val protocolVersion = brc.readByte()
        val inverseProtocolVersion = brc.readByte()
        if (protocolVersion != (inverseProtocolVersion xor 0xFF.toByte())) {
            throw IncorrectPatternFormat("Invalid header $protocolVersion != $inverseProtocolVersion xor 255")
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

            // Handle the UDS message
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

private suspend fun ByteReadChannel.discardIf(condition: Boolean, n: Int) {
    if (condition) {
        this.discardExact(n.toLong())
    }
}

interface DiagnosticMessageHandler {
    fun existsTargetAddress(targetAddress: Short): Boolean
    suspend fun onIncomingDiagMessage(diagMessage: DoipTcpDiagMessage, output: ByteWriteChannel)
}
