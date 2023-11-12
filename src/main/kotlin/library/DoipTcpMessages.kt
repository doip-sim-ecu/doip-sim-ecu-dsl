package library

import io.ktor.utils.io.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.lang.Integer.min
import kotlin.experimental.inv

public abstract class DoipTcpMessage : DoipMessage

@Suppress("MemberVisibilityCanBePrivate")
public open class DoipTcpConnectionMessageHandler(
    public val socket: DoipTcpSocket,
    public val maxPayloadLength: Int = Int.MAX_VALUE
) {
    private var _registeredSourceAddress: Short? = null

    public var registeredSourceAddress: Short?
        get() = _registeredSourceAddress
        protected set(value) {
            _registeredSourceAddress = value
        }


    public open suspend fun receiveTcpData(brc: ByteReadChannel): DoipTcpMessage {
        logger.traceIf { "# receiveTcpData" }
        val protocolVersion = brc.readByte()
        val inverseProtocolVersion = brc.readByte()
        if (protocolVersion != inverseProtocolVersion.inv()) {
            val available = brc.availableForRead
            val data = ByteArray(min(available, 2000))
            brc.readFully(data)
            throw IncorrectPatternFormat("Invalid header $protocolVersion != $inverseProtocolVersion xor 0xFF -- $available bytes available - first ${data.size}: ${data.toHexString()}")
        }
        val payloadType = brc.readShort()
        val payloadLength = brc.readInt()
        if (payloadLength > maxPayloadLength) {
            throw InvalidPayloadLength("Payload longer than maximum allowed length ($payloadLength > $maxPayloadLength)")
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

    public open suspend fun handleTcpMessage(message: DoipTcpMessage, output: ByteWriteChannel) {
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

    protected open suspend fun handleTcpHeaderNegAck(message: DoipTcpHeaderNegAck, output: ByteWriteChannel) {
        logger.traceIf { "# handleTcpHeaderNegAck $message" }
    }

    protected open suspend fun handleTcpRoutingActivationRequest(
        message: DoipTcpRoutingActivationRequest,
        output: ByteWriteChannel
    ) {
        logger.traceIf { "# handleTcpRoutingActivationRequest $message" }
        registeredSourceAddress = message.sourceAddress
    }

    protected open suspend fun handleTcpRoutingActivationResponse(
        message: DoipTcpRoutingActivationResponse,
        output: ByteWriteChannel
    ) {
        logger.traceIf { "# handleTcpRoutingActivationResponse $message" }
    }

    protected open suspend fun handleTcpAliveCheckRequest(message: DoipTcpAliveCheckRequest, output: ByteWriteChannel) {
        logger.traceIf { "# handleTcpAliveCheckRequest $message" }
    }

    protected open suspend fun handleTcpAliveCheckResponse(message: DoipTcpAliveCheckResponse, output: ByteWriteChannel) {
        logger.traceIf { "# handleTcpAliveCheckResponse $message" }
    }

    protected open suspend fun handleTcpDiagMessage(message: DoipTcpDiagMessage, output: ByteWriteChannel) {
        logger.traceIf { "# handleTcpDiagMessage $message for ${message.targetAddress}" }
    }

    protected open suspend fun handleTcpDiagMessagePosAck(message: DoipTcpDiagMessagePosAck, output: ByteWriteChannel) {
        logger.traceIf { "# handleTcpDiagMessagePosAck $message for ${message.targetAddress}" }
    }

    protected open suspend fun handleTcpDiagMessageNegAck(message: DoipTcpDiagMessageNegAck, output: ByteWriteChannel) {
        logger.traceIf { "# handleTcpDiagMessageNegAck $message for ${message.targetAddress}" }
    }

    public open suspend fun connectionClosed(exception: Exception?) {
        logger.traceIf { "# connectionClosed" }
    }

    public open suspend fun closeSocket() {
        if (socket.isClosed) {
            return
        }
        try {
            logger.info("Closing connection $socket")
            socket.close()
            connectionClosed(null)
        } catch (e: Exception) {
            connectionClosed(e)
        }
    }

    private companion object {
        private val logger: Logger = LoggerFactory.getLogger(DoipTcpConnectionMessageHandler::class.java)
    }
}

@Suppress("MemberVisibilityCanBePrivate")
public class DoipTcpHeaderNegAck(
    public val code: Byte
) : DoipTcpMessage() {
    override val asByteArray: ByteArray
        get() = doipMessage(TYPE_HEADER_NACK, code)
}

@Suppress("MemberVisibilityCanBePrivate")
public class DoipTcpRoutingActivationRequest(
    public val sourceAddress: Short,
    public val activationType: Byte = ACTIVATION_TYPE_DEFAULT,
    public val reserved: Int = 0,
    public val oemData: Int? = null
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
    public companion object {
        public const val ACTIVATION_TYPE_DEFAULT: Byte = 0x00
        public const val ACTIVATION_TYPE_REGULATORY: Byte = 0x01
    }
}

public class DoipTcpRoutingActivationResponse(
    public val testerAddress: Short,
    public val entityAddress: Short,
    public val responseCode: Byte,
    public val oemData: Int? = null
) : DoipTcpMessage() {
    @Suppress("unused")
    public companion object {
        public const val RC_ERROR_UNKNOWN_SOURCE_ADDRESS: Byte = 0x00
        public const val RC_ERROR_TCP_DATA_SOCKETS_EXHAUSTED: Byte = 0x01
        public const val RC_ERROR_DIFFERENT_SOURCE_ADDRESS: Byte = 0x02
        public const val RC_ERROR_SOURCE_ADDRESS_ALREADY_ACTIVE: Byte = 0x03
        public const val RC_ERROR_AUTHENTICATION_MISSING: Byte = 0x04
        public const val RC_ERROR_CONFIRMATION_REJECTED: Byte = 0x05
        public const val RC_ERROR_UNSUPPORTED_ACTIVATION_TYPE: Byte = 0x06
        public const val RC_ERROR_REQUIRES_TLS: Byte = 0x07
        public const val RC_OK: Byte = 0x10
        public const val RC_OK_REQUIRES_CONFIRMATION: Byte = 0x11
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

public class DoipTcpAliveCheckRequest : DoipTcpMessage() {
    override val asByteArray: ByteArray
        get() = doipMessage(TYPE_TCP_ALIVE_REQ)
}

@Suppress("MemberVisibilityCanBePrivate")
public class DoipTcpAliveCheckResponse(public val sourceAddress: Short) : DoipTcpMessage() {
    override val asByteArray: ByteArray
        get() = doipMessage(TYPE_TCP_ALIVE_RES, *sourceAddress.toByteArray())
}

public class DoipTcpDiagMessage(
    public val sourceAddress: Short,
    public val targetAddress: Short,
    public val payload: ByteArray
) : DoipTcpMessage() {
    override val asByteArray: ByteArray
        get() =
            doipMessage(
                TYPE_TCP_DIAG_MESSAGE, *sourceAddress.toByteArray(), *targetAddress.toByteArray(), *payload
            )
}

public class DoipTcpDiagMessagePosAck(
    public val sourceAddress: Short,
    public val targetAddress: Short,
    public val ackCode: Byte,
    public val payload: ByteArray = ByteArray(0)
) : DoipTcpMessage() {
    override val asByteArray: ByteArray
        get() = doipMessage(
            TYPE_TCP_DIAG_MESSAGE_POS_ACK, *sourceAddress.toByteArray(), *targetAddress.toByteArray(), ackCode, *payload
        )
}

public class DoipTcpDiagMessageNegAck(
    public val sourceAddress: Short,
    public val targetAddress: Short,
    public val nackCode: Byte,
    public val payload: ByteArray = ByteArray(0)
) : DoipTcpMessage() {
    @Suppress("unused")
    public companion object {
        public const val NACK_CODE_INVALID_SOURCE_ADDRESS: Byte = 0x02
        public const val NACK_CODE_UNKNOWN_TARGET_ADDRESS: Byte = 0x03
        public const val NACK_CODE_DIAGNOSTIC_MESSAGE_TOO_LARGE: Byte = 0x04
        public const val NACK_CODE_OUT_OF_MEMORY: Byte = 0x05
        public const val NACK_CODE_TARGET_UNREACHABLE: Byte = 0x06
        public const val NACK_CODE_UNKNOWN_NETWORK: Byte = 0x07
        public const val NACK_CODE_TRANSPORT_PROTOCOL_ERROR: Byte = 0x08
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
