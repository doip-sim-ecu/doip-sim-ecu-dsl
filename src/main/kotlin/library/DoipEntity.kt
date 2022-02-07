package library

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.concurrent.fixedRateTimer
import kotlin.concurrent.thread

typealias GID = ByteArray
typealias EID = ByteArray
typealias VIN = ByteArray


enum class DoipNodeType(val value: Byte) {
    GATEWAY(0),
    NODE(1)
}

open class DoipEntityConfig(
    val name: String,
    val logicalAddress: Short,
    val gid: GID,
    val eid: EID,
    val vin: VIN,
    val maxDataSize: Int = 0xFFFF,
    val localAddress: InetAddress = InetAddress.getByName("0.0.0.0"),
    val localPort: Int = 13400,
    val broadcastEnabled: Boolean = true,
    val broadcastAddress: InetAddress = InetAddress.getByName("255.255.255.255"),
    // TODO tlsEnabled, tlsPort, certificate chain?
    val ecuConfigList: MutableList<EcuConfig> = mutableListOf(),
    val nodeType: DoipNodeType = DoipNodeType.GATEWAY,
) {
    init {
        if (name.isEmpty()) {
            throw IllegalArgumentException("name must be not empty")
        }
        if (gid.size != 6) {
            throw IllegalArgumentException("gid must be 6 bytes")
        }
        if (eid.size != 6) {
            throw IllegalArgumentException("eid must be 6 bytes")
        }
        if (vin.size != 17) {
            throw IllegalArgumentException("vin must be 17 bytes")
        }
    }
}

open class DoipEntity(
    val config: DoipEntityConfig,
) : DiagnosticMessageHandler {
    val name: String =
        config.name

    protected val logger = LoggerFactory.getLogger(DoipEntity::class.java)

    protected var targetEcusByPhysical: Map<Short, SimulatedEcu> = emptyMap()
    protected var targetEcusByFunctional: MutableMap<Short, MutableList<SimulatedEcu>> = mutableMapOf()

    protected var vamSentCounter = 0

    val connectionHandlers: MutableList<DoipTcpConnectionMessageHandler> = mutableListOf()

    private val _ecus: MutableList<SimulatedEcu> = mutableListOf()

    val ecus: List<SimulatedEcu>
        get() = _ecus

    protected open fun createEcu(config: EcuConfig): SimulatedEcu =
        SimulatedEcu(config)

    protected open fun createDoipUdpMessageHandler(): DoipUdpMessageHandler =
        DefaultDoipUdpMessageHandler(
            doipEntity = this,
            config = config
        )

    protected open fun createDoipTcpMessageHandler(socket: Socket): DoipTcpConnectionMessageHandler =
        DefaultDoipTcpConnectionMessageHandler(
            doipEntity = this,
            socket = socket,
            logicalAddress = config.logicalAddress,
            maxPayloadLength = config.maxDataSize - 8,
            diagMessageHandler = this
        )

    protected open suspend fun startVamTimer(socket: BoundDatagramSocket) {
        if (config.broadcastEnabled) {
            fixedRateTimer("VAM-TIMER-${name}", daemon = true, initialDelay = 500, period = 500) {
                MDC.put("ecu", name)
                if (vamSentCounter >= 3) {
                    this.cancel()
                    return@fixedRateTimer
                }
                logger.info("[${name}] Sending VAM")
                val vam = DefaultDoipUdpMessageHandler.generateVamByEntityConfig(config)
                runBlocking(Dispatchers.IO) {
                    socket.send(
                        Datagram(
                            packet = ByteReadPacket(vam.message),
                            address = InetSocketAddress(config.broadcastAddress, 13400)
                        )
                    )
                }

                vamSentCounter++
            }
        }
    }

    protected open suspend fun sendResponse(request: DoipTcpDiagMessage, output: ByteWriteChannel, data: ByteArray) {
        if (data.isEmpty()) {
            return
        }
        val response = DoipTcpDiagMessage(
            sourceAddress = request.targetAddress,
            targetAddress = request.sourceAddress,
            payload = data
        )
        output.writeFully(response.message)
    }

    override fun existsTargetAddress(targetAddress: Short): Boolean =
        targetEcusByPhysical.containsKey(targetAddress) || targetEcusByFunctional.containsKey(targetAddress)

    override suspend fun onIncomingDiagMessage(diagMessage: DoipTcpDiagMessage, output: ByteWriteChannel) {
        val ecu = targetEcusByPhysical[diagMessage.targetAddress]
        ecu?.run {
            MDC.put("ecu", ecu.name)
            onIncomingUdsMessage(diagMessage.toUdsMessage(UdsMessage.PHYSICAL, output))
            return
        }

        val ecus = targetEcusByFunctional[diagMessage.targetAddress]
        ecus?.forEach {
            MDC.put("ecu", it.name)
            it.onIncomingUdsMessage(diagMessage.toUdsMessage(UdsMessage.FUNCTIONAL, output))
        }
    }

    open fun findEcuByName(name: String): SimulatedEcu? =
        this.ecus.firstOrNull { it.name == name }

    protected open fun CoroutineScope.handleTcpSocket(socket: Socket) {
        launch {
            logger.debugIf { "New incoming TCP connection from ${socket.remoteAddress}" }
            val tcpMessageHandler = createDoipTcpMessageHandler(socket)
            val input = socket.openReadChannel()
            val output = socket.openWriteChannel(autoFlush = tcpMessageHandler.isAutoFlushEnabled())
            try {
                connectionHandlers.add(tcpMessageHandler)
                while (!socket.isClosed) {
                    try {
                        val message = tcpMessageHandler.receiveTcpData(input)
                        tcpMessageHandler.handleTcpMessage(message, output)
                    } catch (e: ClosedReceiveChannelException) {
                        // ignore - socket was closed
                        logger.debug("Socket was closed by remote ${socket.remoteAddress}")
                        withContext(Dispatchers.IO) {
                            socket.close()
                        }
                    } catch (e: HeaderNegAckException) {
                        if (!socket.isClosed) {
                            logger.debug("Error in Header while parsing message, sending negative acknowledgment", e)
                            val response =
                                DoipTcpHeaderNegAck(DoipTcpDiagMessageNegAck.NACK_CODE_TRANSPORT_PROTOCOL_ERROR).message
                            output.writeFully(response, 0, response.size)
                            output.flush()
                        }
                    } catch (e: Exception) {
                        if (!socket.isClosed) {
                            logger.error("Unknown error parsing/handling message, sending negative acknowledgment", e)
                            val response =
                                DoipTcpHeaderNegAck(DoipTcpDiagMessageNegAck.NACK_CODE_TRANSPORT_PROTOCOL_ERROR).message
                            output.writeFully(response, 0, response.size)
                            output.flush()
                        }
                    }
                }
            } catch (e: Throwable) {
                logger.error("Unknown inside socket processing loop, closing socket", e)
            } finally {
                try {
                    withContext(Dispatchers.IO) {
                        socket.close()
                    }
                } finally {
                    connectionHandlers.remove(tcpMessageHandler)
                }
            }
        }
    }

    protected open fun CoroutineScope.handleUdpMessage(
        udpMessageHandler: DoipUdpMessageHandler,
        datagram: Datagram,
        socket: BoundDatagramSocket
    ) {
        launch {
            MDC.put("ecu", name)
            try {
                logger.traceIf { "[${name}] Incoming UDP message" }
                val message = udpMessageHandler.parseMessage(datagram)
                logger.traceIf { "[${name}] Message is of type $message" }
                udpMessageHandler.handleUdpMessage(socket.outgoing, datagram.address, message)
            } catch (e: HeaderNegAckException) {
                val code = when (e) {
                    is IncorrectPatternFormat -> DoipUdpHeaderNegAck.NACK_INCORRECT_PATTERN_FORMAT
                    is HeaderTooShort -> DoipUdpHeaderNegAck.NACK_INCORRECT_PATTERN_FORMAT
                    is InvalidPayloadLength -> DoipUdpHeaderNegAck.NACK_INVALID_PAYLOAD_LENGTH
                    is UnknownPayloadType -> DoipUdpHeaderNegAck.NACK_UNKNOWN_PAYLOAD_TYPE
                    else -> {
                        DoipUdpHeaderNegAck.NACK_UNKNOWN_PAYLOAD_TYPE
                    }
                }
                logger.debug("Error in Message-Header, sending negative acknowledgement", e)
                udpMessageHandler.respondHeaderNegAck(
                    socket.outgoing,
                    datagram.address,
                    code
                )
            } catch (e: Exception) {
                logger.error("Unknown error while processing message", e)
            }
        }
    }

    fun start() {
        this._ecus.addAll(this.config.ecuConfigList.map { createEcu(it) })

        targetEcusByPhysical = this.ecus.associateBy { it.config.physicalAddress }

        targetEcusByFunctional = mutableMapOf()
        _ecus.forEach {
            val list = targetEcusByFunctional[it.config.functionalAddress]
            if (list == null) {
                targetEcusByFunctional[it.config.functionalAddress] = mutableListOf(it)
            } else {
                list.add(it)
            }
        }

        thread(name = "UDP") {
            runBlocking {
                val socket =
                    aSocket(ActorSelectorManager(Dispatchers.IO))
                        .udp() // InetSocketAddress(config.localAddress, config.localPort)
                        .bind(localAddress=InetSocketAddress(config.localAddress, 13400)) {
                            this.broadcast = true
//                        socket.joinGroup(multicastAddress)
                        }
                startVamTimer(socket)
                val udpMessageHandler = createDoipUdpMessageHandler()
                while (!socket.isClosed) {
                    val datagram = socket.receive()
                    handleUdpMessage(udpMessageHandler, datagram, socket)
                }
            }
        }

        thread(name = "TCP") {
            runBlocking {
                val serverSocket =
                    aSocket(ActorSelectorManager(Dispatchers.IO))
                        .tcp()
                        .bind(InetSocketAddress(config.localAddress, config.localPort))
                while (!serverSocket.isClosed) {
                    val socket = serverSocket.accept()
                    handleTcpSocket(socket)
                }
            }
        }

//        thread(name = "TLS") {
//            runBlocking {
//                val serverSocket =
//                    aSocket(ActorSelectorManager(Dispatchers.IO))
//                        .tcp()
//                        .bind(InetSocketAddress(config.localAddress, config.localPort))
//
//                while (!serverSocket.isClosed) {
//                    val socket = serverSocket.accept()
//                    handleTcpSocket(socket)
//                }
//            }
//        }
    }
}
