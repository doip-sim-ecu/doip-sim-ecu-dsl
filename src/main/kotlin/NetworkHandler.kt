import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.slf4j.MDCContext
import library.*
import library.DelegatedKtorSocket
import library.SSLDoipTcpSocket
import nl.altindag.ssl.SSLFactory
import nl.altindag.ssl.pem.util.PemUtils
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.net.InetAddress
import java.net.SocketException
import java.nio.file.Paths
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.concurrent.fixedRateTimer
import kotlin.concurrent.thread
import kotlin.system.exitProcess

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

    protected open suspend fun startVamTimer(socket: BoundDatagramSocket) {
        if (broadcastEnabled) {
            sendVams(socket)
        }
    }

    protected open suspend fun sendVams(socket: BoundDatagramSocket) {
        var vamSentCounter = 0

        val entries = doipEntities.associateWith { it.generateVehicleAnnouncementMessages() }

        fixedRateTimer("VAM", daemon = true, initialDelay = 500, period = 500) {
            if (vamSentCounter >= 3) {
                this.cancel()
                return@fixedRateTimer
            }
            entries.forEach { (doipEntity, vams) ->
                MDC.put("ecu", doipEntity.name)
                vams.forEach { vam ->
                    logger.info("Sending VAM for ${vam.logicalAddress.toByteArray().toHexString()} as broadcast")
                    runBlocking(Dispatchers.IO) {
                        launch(MDCContext()) {
                            socket.send(
                                Datagram(
                                    packet = ByteReadPacket(vam.asByteArray),
                                    address = InetSocketAddress(broadcastAddress, port)
                                )
                            )
                        }
                    }
                }
            }

            vamSentCounter++
        }
    }

    public fun start() {
        thread(name = "UDP") {
            runBlocking {
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
                startVamTimer(udpServerSocket)

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

//    public fun pauseTcpServerSockets(duration: kotlin.time.Duration) {
//        logger.warn("Closing serversockets")
//        serverSockets.forEach {
//            try {
//                it.close()
//            } catch (ignored: Exception) {
//            }
//        }
//        serverSockets.clear()
//        logger.warn("Pausing server sockets for ${duration.inWholeMilliseconds} ms")
//        Thread.sleep(duration.inWholeMilliseconds)
//        logger.warn("Restarting server sockets after ${duration.inWholeMilliseconds} ms")
//        runBlocking {
//            launch {
//                startVamTimer(udpServerSocket)
//            }
//            launch {
//                start()
//            }
//        }
//    }

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
                        val activeConnection = ActiveConnection(this@TcpNetworkBinding, doipEntities)
                        activeConnection.handleTcpSocket(this@withContext, DelegatedKtorSocket(socket), null)
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
                        val activeConnection = ActiveConnection(this@TcpNetworkBinding, doipEntities)
                        activeConnection.handleTcpSocket(this, SSLDoipTcpSocket(socket), null)
                    }
                }
            }
        }
    }

    public open class ActiveConnection(
        private val networkBinding: TcpNetworkBinding,
        private val doipEntities: List<DoipEntity<*>>
    ) {
        private val logger = LoggerFactory.getLogger(ActiveConnection::class.java)

        public open suspend fun handleTcpSocket(
            scope: CoroutineScope,
            socket: DoipTcpSocket,
            disableServerSocketCallback: ((kotlin.time.Duration) -> Unit)?
        ) {
            scope.launch(Dispatchers.IO) {
                val handler = GroupDoipTcpConnectionMessageHandler(doipEntities, socket, networkBinding.tlsOptions)

                val entity = doipEntities.first()

                logger.debugIf { "New incoming data connection from ${socket.remoteAddress}" }
                val input = socket.openReadChannel()
                val output = socket.openWriteChannel()
                try {
                    val parser = DoipTcpMessageParser(doipEntities.first().config.maxDataSize - 8)
                    while (!socket.isClosed) {
                        val message = parser.parseDoipTcpMessage(input)

                        runBlocking {
                            try {
                                MDC.put("ecu", entity.name)
                                handler.handleTcpMessage(message, output)
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
                                logger.warn("Simulating Hard Reset on ${entity.name} for ${e.duration.inWholeMilliseconds} ms")
                                output.flush()
                                socket.close()

                                if (disableServerSocketCallback != null) {
                                    disableServerSocketCallback(e.duration)
                                }
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
                } catch (e: Throwable) {
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
