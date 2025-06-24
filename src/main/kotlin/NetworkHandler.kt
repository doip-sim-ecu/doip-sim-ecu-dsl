import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.slf4j.MDCContext
import library.*
import nl.altindag.ssl.SSLFactory
import nl.altindag.ssl.pem.util.PemUtils
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.io.EOFException
import java.net.InetAddress
import java.net.SocketException
import java.nio.file.Paths
import java.util.*
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.fixedRateTimer
import kotlin.concurrent.thread
import kotlin.system.exitProcess

public open class UdpNetworkBindingAny(
    private val port: Int = 13400,
    private val sendVirReply: (target: SocketAddress) -> Unit
) {
    private val logger = LoggerFactory.getLogger(UdpNetworkBindingAny::class.java)

    private lateinit var udpServerSocket: BoundDatagramSocket

    public open fun start() {
        thread(name = "UDP") {
            runBlocking {
                MDC.put("ecu", "ALL")
                udpServerSocket = aSocket(ActorSelectorManager(Dispatchers.IO))
                    .udp()
                    .bind(localAddress = InetSocketAddress(hostname = "0.0.0.0", port = port)) {
                        broadcast = true
                        reuseAddress = true
//                            reusePort = true // not supported on windows
                        typeOfService = TypeOfService.IPTOS_RELIABILITY
//                            socket.joinGroup(multicastAddress)
                    }
                logger.info("Listening on udp: ${udpServerSocket.localAddress}")

                while (!udpServerSocket.isClosed) {
                    val datagram = udpServerSocket.receive()
                    try {
                        val message = DoipUdpMessageParser.parseUDP(datagram.packet)
                        if (message is DoipUdpVehicleInformationRequest ||
                            message is DoipUdpVehicleInformationRequestWithEid ||
                            message is DoipUdpVehicleInformationRequestWithVIN
                        ) {
                            sendVirReply(datagram.address)
                        }
                    } catch (e: Exception) {
                        logger.error("Unknown error while processing message", e)
                    }
                }
            }
        }
    }
}

public open class UdpNetworkBinding(
    private val localAddress: String,
    private val port: Int = 13400,
    private val broadcastEnabled: Boolean = true,
    private val broadcastAddress: String = "255.255.255.255",
    private val doipEntities: List<DoipEntity<*>>,
) {
    private val logger = LoggerFactory.getLogger(UdpNetworkBinding::class.java)

    private lateinit var udpServerSocket: BoundDatagramSocket

    private val udpMessageHandlers = doipEntities.associateWith { it.createDoipUdpMessageHandler() }

    public open suspend fun sendVirReply(address: SocketAddress) {
        internalSendVams(address, null)
    }

    protected open suspend fun startVamTimer(doipEntitiesFilter: List<DoipEntity<*>>? = null) {
        if (broadcastEnabled) {
            sendVams(doipEntitiesFilter)
        }
    }

    protected open suspend fun internalSendVams(
        address: SocketAddress,
        doipEntitiesFilter: List<DoipEntity<*>>? = null
    ) {
        val entries = doipEntities.associateWith { it.generateVehicleAnnouncementMessages() }

        entries.forEach { (doipEntity, vams) ->
            MDC.put("ecu", doipEntity.name)
            vams.forEach { vam ->
                if (doipEntitiesFilter != null && doipEntitiesFilter.none { vam.logicalAddress == it.config.logicalAddress }) {
                    return@forEach
                }
                logger.info("Sending VAM for ${vam.logicalAddress.toByteArray().toHexString()} as broadcast")
                udpServerSocket.send(
                    Datagram(
                        packet = ByteReadPacket(vam.asByteArray),
                        address = address
                    )
                )
            }
        }
    }

    protected open fun sendVams(doipEntitiesFilter: List<DoipEntity<*>>? = null) {
        var vamSentCounter = 0

        fixedRateTimer("VAM", daemon = true, initialDelay = 500, period = 500) {
            if (vamSentCounter >= 3) {
                this.cancel()
                return@fixedRateTimer
            }

            runBlocking(Dispatchers.IO) {
                launch(MDCContext()) {
                    internalSendVams(InetSocketAddress(broadcastAddress, port), doipEntitiesFilter)
                }
            }

            vamSentCounter++
        }
    }

    public suspend fun resendVams(doipEntitiesFilter: List<DoipEntity<*>>? = null) {
        startVamTimer(doipEntitiesFilter)
    }

    public fun start() {
        thread(name = "UDP") {
            runBlocking {
                MDC.put("ecu", doipEntities.first().name)
                udpServerSocket = aSocket(ActorSelectorManager(Dispatchers.IO))
                    .udp()
                    .bind(localAddress = InetSocketAddress(hostname = localAddress, port = port)) {
                        broadcast = true
                        reuseAddress = true
//                            reusePort = true // not supported on windows
                        typeOfService = TypeOfService.IPTOS_RELIABILITY
//                            socket.joinGroup(multicastAddress)
                    }
                logger.info("Listening on udp: ${udpServerSocket.localAddress}")
                startVamTimer()

                while (!udpServerSocket.isClosed) {
                    val datagram = udpServerSocket.receive()
                    withContext(Dispatchers.IO) {
                        handleUdpMessage(udpMessageHandlers, datagram, udpServerSocket)
                    }
                }
            }
        }
    }

    protected open fun CoroutineScope.handleUdpMessage(
        udpMessageHandlers: Map<DoipEntity<*>, DoipUdpMessageHandler>,
        datagram: Datagram,
        socket: BoundDatagramSocket
    ) {
        val message = DoipUdpMessageParser.parseUDP(datagram.packet)
        udpMessageHandlers.forEach { (doipEntity, datagramHandler) ->
            runBlocking {
                MDC.put("ecu", doipEntity.name)
                try {
                    logger.traceIf { "Incoming UDP message for ${doipEntity.name}" }
                    datagramHandler.handleUdpMessage(socket.outgoing, datagram.address, message)
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
                    datagramHandler.respondHeaderNegAck(
                        socket.outgoing,
                        datagram.address,
                        code
                    )
                    return@runBlocking
                } catch (e: Exception) {
                    logger.error("Unknown error while processing message", e)
                }
            }
        }
    }
}

public open class TcpNetworkBinding(
    private val networkManager: NetworkManager,
    private val localAddress: String,
    private val localPort: Int,
    private val tlsOptions: TlsOptions?,
    private val doipEntities: List<DoipEntity<*>>
) {
    private val logger = LoggerFactory.getLogger(TcpNetworkBinding::class.java)

    private val serverSockets: MutableList<ServerSocket> = mutableListOf()
    private val activeConnections: MutableMap<ActiveConnection, DoipEntity<*>> = mutableMapOf()
    private val hardResettingEcus: MutableSet<Short> = Collections.synchronizedSet(mutableSetOf())

    public fun isEcuHardResetting(targetAddress: Short): Boolean =
        hardResettingEcus.contains(targetAddress)

    public fun hardResetEcuFor(
        activeConnection: ActiveConnection,
        logicalAddress: Short,
        duration: kotlin.time.Duration
    ) {
        val isDoipEntity = doipEntities.any { it.config.logicalAddress == logicalAddress }

        if (isDoipEntity) {
            activeConnection.close()
            logger.info("Closing serversockets")
            serverSockets.forEach {
                try {
                    it.close()
                } catch (_: Exception) {
                    // ignored
                }
            }
            logger.info("Closing active connections")
            activeConnections.forEach {
                try {
                    it.key.close()
                } catch (_: Exception) {
                    // ignored
                }
            }
            serverSockets.clear()
        }

        hardResettingEcus.add(logicalAddress)

        logger.warn("Pausing server sockets for ${duration.inWholeMilliseconds} ms")
        Thread.sleep(duration.inWholeMilliseconds)

        hardResettingEcus.remove(logicalAddress)
        logger.info("Reactivating server sockets after ${duration.inWholeMilliseconds} ms")

        if (isDoipEntity) {
            runBlocking {
                launch {
                    start()
                }
                launch {
                    networkManager.resendVams(doipEntities)
                }
            }
        }
    }

    public fun start() {
        thread(name = "TCP") {
            runBlocking {
                withContext(Dispatchers.IO) {
                    val serverSocket =
                        aSocket(ActorSelectorManager(Dispatchers.IO))
                            .tcp()
                            .bind(InetSocketAddress(localAddress, localPort))
                    serverSockets.add(serverSocket)
                    logger.info("Listening on tcp: ${serverSocket.localAddress}")
                    while (!serverSocket.isClosed) {
                        val socket = serverSocket.accept()
                        val activeConnection = ActiveConnection(networkManager, this@TcpNetworkBinding, doipEntities)
                        activeConnection.handleTcpSocket(this@withContext, DelegatedKtorSocket(socket))
                    }
                }
            }
        }

// TLS with ktor-network doesn't work yet https://youtrack.jetbrains.com/issue/KTOR-694
        if (tlsOptions != null && tlsOptions.tlsMode != TlsMode.DISABLED) {
            if (tlsOptions.tlsCert == null) {
                System.err.println("tlsCert is null")
                exitProcess(-1)
            } else if (tlsOptions.tlsKey == null) {
                System.err.println("tlsKey is null")
                exitProcess(-1)
            } else if (!tlsOptions.tlsCert.isFile) {
                System.err.println("${tlsOptions.tlsCert.absolutePath} doesn't exist or isn't a file")
                exitProcess(-1)
            } else if (!tlsOptions.tlsKey.isFile) {
                System.err.println("${tlsOptions.tlsKey.absolutePath} doesn't exist or isn't a file")
                exitProcess(-1)
            }

            thread(name = "TLS") {
                runBlocking {
                    val key = PemUtils.loadIdentityMaterial(
                        Paths.get(tlsOptions.tlsCert.toURI()),
                        Paths.get(tlsOptions.tlsKey.toURI()),
                        tlsOptions.tlsKeyPassword?.toCharArray()
                    )
                    val trustMaterial = PemUtils.loadTrustMaterial(Paths.get(tlsOptions.tlsCert.toURI()))

                    val sslFactory = SSLFactory.builder()
                        .withIdentityMaterial(key)
                        .withTrustMaterial(trustMaterial)
                        .build()

                    val serverSocket = withContext(Dispatchers.IO) {
                        (sslFactory.sslServerSocketFactory.createServerSocket(
                            tlsOptions.tlsPort,
                            50,
                            InetAddress.getByName(localAddress)
                        ))
                    }
                    serverSockets.add(serverSocket as ServerSocket)
                    val tlsServerSocket = serverSocket as SSLServerSocket
                    logger.info("Listening on tls: ${tlsServerSocket.localSocketAddress}")

                    if (tlsOptions.tlsProtocols != null) {
                        val supportedProtocols = tlsServerSocket.supportedProtocols.toSet()
                        // Use filter to retain order of protocols/ciphers
                        tlsServerSocket.enabledProtocols =
                            tlsOptions.tlsProtocols.filter { supportedProtocols.contains(it) }.toTypedArray()
                    }

                    if (tlsOptions.tlsCiphers != null) {
                        val supportedCipherSuites = tlsServerSocket.supportedCipherSuites.toSet()
                        // Use filter to retain order of protocols/ciphers
                        tlsServerSocket.enabledCipherSuites =
                            tlsOptions.tlsCiphers.filter { supportedCipherSuites.contains(it) }.toTypedArray()
                    }

                    logger.info("Enabled TLS protocols: ${tlsServerSocket.enabledProtocols.joinToString(", ")}")
                    logger.info("Enabled TLS cipher suites: ${tlsServerSocket.enabledCipherSuites.joinToString(", ")}")

                    while (!tlsServerSocket.isClosed) {
                        val socket = tlsServerSocket.accept() as SSLSocket
                        val activeConnection = ActiveConnection(networkManager, this@TcpNetworkBinding, doipEntities)
                        activeConnection.handleTcpSocket(this, SSLDoipTcpSocket(socket))
                    }
                }
            }
        }
    }

    public open class ActiveConnection(
        private val networkManager: NetworkManager,
        private val networkBinding: TcpNetworkBinding,
        private val doipEntities: List<DoipEntity<*>>,
    ) {
        private val logger = LoggerFactory.getLogger(ActiveConnection::class.java)
        private var socket: DoipTcpSocket? = null
        private var closed: Boolean = false

        public open fun close() {
            socket?.close()
            closed = true
        }

        protected open suspend fun sendDoipAck(message: DoipTcpDiagMessage, output: OutputChannel) {
            val ack = DoipTcpDiagMessagePosAck(
                message.targetAddress,
                message.sourceAddress,
                0x00
            )
            output.writeFully(ack.asByteArray)
        }

        public open suspend fun handleTcpSocket(
            scope: CoroutineScope,
            socket: DoipTcpSocket
        ) {
            this.socket = socket

            scope.launch(Dispatchers.IO) {
                val handler =
                    networkManager.createTcpConnectionMessageHandler(doipEntities, socket, networkBinding.tlsOptions)

                val entity = doipEntities.first()

                logger.debugIf { "New incoming data connection from ${socket.remoteAddress}" }
                val input = socket.openReadChannel()
                val output = OutputChannelImpl(socket.openWriteChannel())
                try {
                    val parser = DoipTcpMessageParser(doipEntities.first().config.maxDataSize - 8)
                    while (!socket.isClosed && !closed) {
                        MDC.put("ecu", entity.name)
                        val message = parser.parseDoipTcpMessage(input)
                        launch(MDCContext()) {
                            try {
                                if (message is DoipTcpDiagMessage && networkBinding.isEcuHardResetting(message.targetAddress)) {
                                    sendDoipAck(message, output)
                                } else {
                                    handler.handleTcpMessage(message, output)
                                }
                            } catch (e: ClosedReceiveChannelException) {
                                // ignore - socket was closed
                                logger.debugIf { "Socket was closed by remote ${socket.remoteAddress}" }
                                withContext(Dispatchers.IO) {
                                    handler.connectionClosed(e)
                                    socket.runCatching { this.close() }
                                }
                            } catch (e: SocketException) {
                                logger.error("Socket error: ${e.message} -> closing socket")
                                withContext(Dispatchers.IO) {
                                    handler.connectionClosed(e)
                                    socket.runCatching { this.close() }
                                }
                            } catch (e: HeaderNegAckException) {
                                if (!socket.isClosed) {
                                    logger.debug(
                                        "Error in Header while parsing message, sending negative acknowledgment",
                                        e
                                    )
                                    val response =
                                        DoipTcpHeaderNegAck(DoipTcpDiagMessageNegAck.NACK_CODE_TRANSPORT_PROTOCOL_ERROR).asByteArray
                                    output.writeFully(response)
                                    withContext(Dispatchers.IO) {
                                        handler.connectionClosed(e)
                                        socket.runCatching { this.close() }
                                    }
                                }
                            } catch (e: DoipEntityHardResetException) {
                                logger.warn("Simulating Hard Reset on ${e.ecu.name} for ${e.duration.inWholeMilliseconds} ms")
                                output.flush()

                                networkBinding.hardResetEcuFor(
                                    this@ActiveConnection,
                                    e.ecu.config.logicalAddress,
                                    e.duration
                                )
                            } catch (e: Exception) {
                                if (!socket.isClosed) {
                                    logger.error(
                                        "Unknown error parsing/handling message, sending negative acknowledgment",
                                        e
                                    )
                                    val response =
                                        DoipTcpHeaderNegAck(DoipTcpDiagMessageNegAck.NACK_CODE_TRANSPORT_PROTOCOL_ERROR).asByteArray
                                    output.writeFully(response)
                                    withContext(Dispatchers.IO) {
                                        handler.connectionClosed(e)
                                        socket.runCatching { this.close() }
                                    }
                                }
                            }
                        }
                    }
                } catch (_: ClosedReceiveChannelException) {
                    MDC.put("ecu", doipEntities.firstOrNull()?.name)
                    logger.info("Connection closed by remote through ClosedChannel ${socket.remoteAddress}")
                } catch (_: EOFException) {
                    MDC.put("ecu", doipEntities.firstOrNull()?.name)
                    logger.info("Connection closed by remote through EOF ${socket.remoteAddress}")
                } catch (e: Throwable) {
                    MDC.put("ecu", doipEntities.firstOrNull()?.name)
                    logger.error("Unknown error inside socket processing loop, closing socket", e)
                } finally {
                    try {
                        socket.close()
                    } finally {
                        networkBinding.activeConnections.remove(this@ActiveConnection)
                    }
                }
            }
        }
    }
}
