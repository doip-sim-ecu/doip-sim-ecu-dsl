package library

import io.ktor.utils.io.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.io.OutputStream
import kotlin.experimental.xor

open class DefaultDoipTcpConnectionMessageHandler(
    val doipEntity: DoipEntity,
    val socket: DoipTcpSocket,
    val logicalAddress: Short,
    val maxPayloadLength: Int,
    val diagMessageHandler: DiagnosticMessageHandler
) : DoipTcpConnectionMessageHandler {
    private val logger: Logger = LoggerFactory.getLogger(DefaultDoipTcpConnectionMessageHandler::class.java)

    private var _registeredSourceAddress: Short? = null

    override fun getRegisteredSourceAddress(): Short? =
        _registeredSourceAddress

    override suspend fun receiveTcpData(brc: ByteReadChannel): DoipTcpMessage {
        logger.traceIf { "# receiveTcpData" }
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
                return DoipTcpHeaderNegAck(code)
            }
            TYPE_TCP_ROUTING_REQ -> {
                val sourceAddress = brc.readShort()
                val activationType = brc.readByte()
                brc.readInt() // Reserved for future standardization use
                val oemData = if (payloadLength > 7) brc.readInt() else null
                return DoipTcpRoutingActivationRequest(sourceAddress, activationType, oemData)
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

    override suspend fun handleTcpMessage(message: DoipTcpMessage, output: OutputStream) {
        MDC.put("ecu", doipEntity.name)
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
        if (message.activationType != 0x00.toByte() && message.activationType != 0x01.toByte()) {
            logger.error("Routing activation for ${message.sourceAddress} denied (Unknown type: ${message.activationType})")
            output.writeFully(
                DoipTcpRoutingActivationResponse(
                    message.sourceAddress,
                    logicalAddress,
                    DoipTcpRoutingActivationResponse.RC_ERROR_UNSUPPORTED_ACTIVATION_TYPE
                ).message
            )
            return
        } else {
            if (doipEntity.config.tlsMode == TlsMode.MANDATORY && socket.socketType != SocketType.TLS_DATA) {
                logger.info("Routing activation for ${message.sourceAddress} denied (TLS required)")
                output.writeFully(
                    DoipTcpRoutingActivationResponse(
                        message.sourceAddress,
                        logicalAddress,
                        DoipTcpRoutingActivationResponse.RC_ERROR_REQUIRES_TLS
                    ).message
                )
                return
            }

            if (_registeredSourceAddress == null) {
                _registeredSourceAddress = message.sourceAddress
            }

            if (_registeredSourceAddress != message.sourceAddress) {
                logger.error("Routing activation for ${message.sourceAddress} denied (Different source address already registered)")
                output.writeFully(
                    DoipTcpRoutingActivationResponse(
                        message.sourceAddress,
                        logicalAddress,
                        DoipTcpRoutingActivationResponse.RC_ERROR_DIFFERENT_SOURCE_ADDRESS
                    ).message
                )
            } else if (doipEntity.hasAlreadyActiveConnection(message.sourceAddress, this)) {
                logger.error("Routing activation for ${message.sourceAddress} denied (Has already an active connection)")
                output.writeFully(
                    DoipTcpRoutingActivationResponse(
                        message.sourceAddress,
                        logicalAddress,
                        DoipTcpRoutingActivationResponse.RC_ERROR_SOURCE_ADDRESS_ALREADY_ACTIVE
                    ).message
                )
            } else {
                logger.info("Routing activation for ${message.sourceAddress} was successful")
                output.writeFully(
                    DoipTcpRoutingActivationResponse(
                        message.sourceAddress,
                        logicalAddress,
                        DoipTcpRoutingActivationResponse.RC_OK
                    ).message
                )
            }
        }
    }

    protected open suspend fun handleTcpRoutingActivationResponse(message: DoipTcpRoutingActivationResponse, output: OutputStream) {
        logger.traceIf { "# handleTcpRoutingActivationResponse $message" }
    }

    protected open suspend fun handleTcpAliveCheckRequest(message: DoipTcpAliveCheckRequest, output: OutputStream) {
        logger.traceIf { "# handleTcpAliveCheckRequest $message" }
        output.writeFully(DoipTcpAliveCheckResponse(logicalAddress).message)
    }

    protected open suspend fun handleTcpAliveCheckResponse(message: DoipTcpAliveCheckResponse, output: OutputStream) {
        logger.traceIf { "# handleTcpAliveCheckResponse $message" }
    }

    protected open suspend fun handleTcpDiagMessage(message: DoipTcpDiagMessage, output: OutputStream) {
        if (_registeredSourceAddress != message.sourceAddress) {
            val reject = DoipTcpDiagMessageNegAck(
                message.targetAddress,
                message.sourceAddress,
                DoipTcpDiagMessageNegAck.NACK_CODE_INVALID_SOURCE_ADDRESS
            )
            output.writeFully(reject.message)
            return
        }
        logger.traceIf { "# handleTcpDiagMessage $message for ${message.targetAddress}" }
        if (diagMessageHandler.existsTargetAddress(message.targetAddress)) {
            logger.traceIf { "# targetAddress ${message.targetAddress} exists, sending positive ack" }
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
            logger.traceIf { "# targetAddress ${message.targetAddress} exists, sending negative ack" }
            // Reject message with unknown target address
            val reject = DoipTcpDiagMessageNegAck(
                message.targetAddress,
                message.sourceAddress,
                DoipTcpDiagMessageNegAck.NACK_CODE_UNKNOWN_TARGET_ADDRESS
            )
            output.writeFully(reject.message)
        }
    }

    protected open suspend fun handleTcpDiagMessagePosAck(message: DoipTcpDiagMessagePosAck, output: OutputStream) {
        // No implementation
    }

    protected open suspend fun handleTcpDiagMessageNegAck(message: DoipTcpDiagMessageNegAck, output: OutputStream) {
        // No implementation
    }
}

//private suspend fun ByteReadChannel.discardIf(condition: Boolean, n: Int, payloadType: Short) {
//    if (condition) {
//        logger.error("Discarding $n bytes for payload-type $payloadType")
//        this.discardExact(n.toLong())
//    }
//}

interface DiagnosticMessageHandler {
    fun existsTargetAddress(targetAddress: Short): Boolean
    suspend fun onIncomingDiagMessage(diagMessage: DoipTcpDiagMessage, output: OutputStream)
}

fun DoipEntity.hasAlreadyActiveConnection(sourceAddress: Short, exclude: DoipTcpConnectionMessageHandler?) =
    this.connectionHandlers.any { it.getRegisteredSourceAddress() == sourceAddress && it != exclude }

fun OutputStream.writeFully(byteArray: ByteArray) =
    this.write(byteArray)
