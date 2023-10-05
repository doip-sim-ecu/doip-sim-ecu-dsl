package library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.io.OutputStream

public open class DefaultDoipEntityTcpConnectionMessageHandler(
    public val doipEntity: DoipEntity,
    public val socket: DoipTcpSocket,
    maxPayloadLength: Int,
    public val logicalAddress: Short,
    public val diagMessageHandler: DiagnosticMessageHandler
) : DoipTcpConnectionMessageHandler(maxPayloadLength) {
    private val logger: Logger = LoggerFactory.getLogger(DefaultDoipEntityTcpConnectionMessageHandler::class.java)

    override suspend fun handleTcpMessage(message: DoipTcpMessage, output: OutputStream) {
        runBlocking {
            MDC.put("ecu", doipEntity.name)
            launch(MDCContext()) {
                super.handleTcpMessage(message, output)
            }
        }
    }

    override suspend fun handleTcpRoutingActivationRequest(message: DoipTcpRoutingActivationRequest, output: OutputStream) {
        logger.traceIf { "# handleTcpRoutingActivationRequest $message" }
        if (message.activationType != 0x00.toByte() && message.activationType != 0x01.toByte()) {
            logger.error("Routing activation for ${message.sourceAddress} denied (Unknown type: ${message.activationType})")
            output.writeFully(
                DoipTcpRoutingActivationResponse(
                    message.sourceAddress,
                    logicalAddress,
                    DoipTcpRoutingActivationResponse.RC_ERROR_UNSUPPORTED_ACTIVATION_TYPE
                ).asByteArray
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
                    ).asByteArray
                )
                return
            }

            if (registeredSourceAddress == null) {
                registeredSourceAddress = message.sourceAddress
            }

            if (registeredSourceAddress != message.sourceAddress) {
                logger.error("Routing activation for ${message.sourceAddress} denied (Different source address already registered)")
                output.writeFully(
                    DoipTcpRoutingActivationResponse(
                        message.sourceAddress,
                        logicalAddress,
                        DoipTcpRoutingActivationResponse.RC_ERROR_DIFFERENT_SOURCE_ADDRESS
                    ).asByteArray
                )
            } else if (
                doipEntity.ecus.all { it.config.additionalVam == null } &&
                doipEntity.hasAlreadyActiveConnection(message.sourceAddress, this)
            ) {
                logger.error("Routing activation for ${message.sourceAddress} denied (Has already an active connection)")
                output.writeFully(
                    DoipTcpRoutingActivationResponse(
                        message.sourceAddress,
                        logicalAddress,
                        DoipTcpRoutingActivationResponse.RC_ERROR_SOURCE_ADDRESS_ALREADY_ACTIVE
                    ).asByteArray
                )
            } else {
                logger.info("Routing activation for ${message.sourceAddress} was successful")
                output.writeFully(
                    DoipTcpRoutingActivationResponse(
                        message.sourceAddress,
                        logicalAddress,
                        DoipTcpRoutingActivationResponse.RC_OK
                    ).asByteArray
                )
            }
        }
    }

    override suspend fun handleTcpAliveCheckRequest(message: DoipTcpAliveCheckRequest, output: OutputStream) {
        logger.traceIf { "# handleTcpAliveCheckRequest $message" }
        output.writeFully(DoipTcpAliveCheckResponse(logicalAddress).asByteArray)
    }

    override suspend fun handleTcpDiagMessage(message: DoipTcpDiagMessage, output: OutputStream) {
        if (registeredSourceAddress != message.sourceAddress) {
            val reject = DoipTcpDiagMessageNegAck(
                message.targetAddress,
                message.sourceAddress,
                DoipTcpDiagMessageNegAck.NACK_CODE_INVALID_SOURCE_ADDRESS
            )
            output.writeFully(reject.asByteArray)
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
            output.writeFully(ack.asByteArray)

            // Handle the UDS message
            diagMessageHandler.onIncomingDiagMessage(message, output)
        } else {
            logger.traceIf { "# targetAddress ${message.targetAddress} doesn't exist, sending negative ack" }
            // Reject message with unknown target address
            val reject = DoipTcpDiagMessageNegAck(
                message.targetAddress,
                message.sourceAddress,
                DoipTcpDiagMessageNegAck.NACK_CODE_UNKNOWN_TARGET_ADDRESS
            )
            output.writeFully(reject.asByteArray)
        }
    }
}

//private suspend fun ByteReadChannel.discardIf(condition: Boolean, n: Int, payloadType: Short) {
//    if (condition) {
//        logger.error("Discarding $n bytes for payload-type $payloadType")
//        this.discardExact(n.toLong())
//    }
//}

public interface DiagnosticMessageHandler {
    public fun existsTargetAddress(targetAddress: Short): Boolean
    public suspend fun onIncomingDiagMessage(diagMessage: DoipTcpDiagMessage, output: OutputStream)
}

public fun DoipEntity.hasAlreadyActiveConnection(sourceAddress: Short, exclude: DoipTcpConnectionMessageHandler?): Boolean =
    this.connectionHandlers.any {
        it.registeredSourceAddress == sourceAddress
                && it != exclude
    }

public suspend fun OutputStream.writeFully(byteArray: ByteArray): Unit =
    withContext(Dispatchers.IO) {
        this@writeFully.write(byteArray)
    }
