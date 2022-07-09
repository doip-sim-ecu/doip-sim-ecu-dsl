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

open class DefaultDoipEntityTcpConnectionMessageHandler(
    val doipEntity: DoipEntity,
    val socket: DoipTcpSocket,
    maxPayloadLength: Int,
    val logicalAddress: Short,
    val diagMessageHandler: DiagnosticMessageHandler
) : DoipTcpConnectionMessageHandler(maxPayloadLength) {
    private val logger: Logger = LoggerFactory.getLogger(DefaultDoipEntityTcpConnectionMessageHandler::class.java)

    private var _registeredSourceAddress: Short? = null

    fun getRegisteredSourceAddress(): Short? =
        _registeredSourceAddress

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
                    ).asByteArray
                )
            } else if (
                doipEntity.hasAlreadyActiveConnection(message.sourceAddress, this) &&
                doipEntity.ecus.all { it.config.additionalVam == null }
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
        if (_registeredSourceAddress != message.sourceAddress) {
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
            logger.traceIf { "# targetAddress ${message.targetAddress} exists, sending negative ack" }
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

interface DiagnosticMessageHandler {
    fun existsTargetAddress(targetAddress: Short): Boolean
    suspend fun onIncomingDiagMessage(diagMessage: DoipTcpDiagMessage, output: OutputStream)
}

fun DoipEntity.hasAlreadyActiveConnection(sourceAddress: Short, exclude: DoipTcpConnectionMessageHandler?) =
    this.connectionHandlers.any {
        (it as DefaultDoipEntityTcpConnectionMessageHandler).getRegisteredSourceAddress() == sourceAddress
                && it != exclude
    }

suspend fun OutputStream.writeFully(byteArray: ByteArray) =
    withContext(Dispatchers.IO) {
        this@writeFully.write(byteArray)
    }
