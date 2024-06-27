package library

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.slf4j.MDCContext
import nl.altindag.ssl.SSLFactory
import nl.altindag.ssl.pem.util.PemUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.io.File
import java.net.InetAddress
import java.net.SocketException
import java.nio.file.Paths
import javax.net.ssl.*
import kotlin.concurrent.fixedRateTimer
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

public typealias GID = ByteArray
public typealias EID = ByteArray
public typealias VIN = ByteArray


public enum class DoipNodeType(public val value: Byte) {
    GATEWAY(0),
    NODE(1)
}

@Suppress("unused")
public enum class TlsMode {
    DISABLED,
    OPTIONAL,
    MANDATORY,
}

public data class TlsOptions(
    val tlsCert: File? = null,
    val tlsKey: File? = null,
    val tlsKeyPassword: String? = null,
    val tlsCiphers: List<String>? = DefaultTlsCiphers,
    val tlsProtocols: List<String>? = DefaultTlsProtocols,
)

@Suppress("unused")
public open class DoipEntityConfig(
    public val name: String,
    public val logicalAddress: Short,
    public val gid: GID,
    public val eid: EID,
    public val vin: VIN,
    public val maxDataSize: Int = Int.MAX_VALUE,
    public val localAddress: String = "0.0.0.0",
    public val bindOnAnyForUdpAdditional: Boolean = true,
    public val localPort: Int = 13400,
    public val broadcastEnabled: Boolean = true,
    public val broadcastAddress: String = "255.255.255.255",
    public val pendingNrcSendInterval: kotlin.time.Duration = 2.seconds,
    public val tlsMode: TlsMode = TlsMode.DISABLED,
    public val tlsPort: Int = 3496,
    public val tlsOptions: TlsOptions = TlsOptions(),
    public val ecuConfigList: MutableList<EcuConfig> = mutableListOf(),
    public val nodeType: DoipNodeType = DoipNodeType.GATEWAY,
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

/**
 * DoIP-Entity
 */
@Suppress("MemberVisibilityCanBePrivate")
public abstract class DoipEntity<out T : SimulatedEcu>(
    public val config: DoipEntityConfig,
) : DiagnosticMessageHandler {
    public val name: String =
        config.name

    protected val logger: Logger = LoggerFactory.getLogger(DoipEntity::class.java)

    protected var targetEcusByLogical: Map<Short, @UnsafeVariance T> = emptyMap()
    protected var targetEcusByFunctional: MutableMap<Short, MutableList<@UnsafeVariance T>> = mutableMapOf()

    public val connectionHandlers: MutableList<DoipTcpConnectionMessageHandler> = mutableListOf()

    private val _ecus: MutableList<T> = mutableListOf()

    public val ecus: List<T>
        get() = _ecus

    private lateinit var udpServerSocket: BoundDatagramSocket

    protected abstract fun createEcu(config: EcuConfig): T

    protected open fun createDoipUdpMessageHandler(): DoipUdpMessageHandler =
        DefaultDoipEntityUdpMessageHandler(
            doipEntity = this,
            config = config
        )

    protected open fun createDoipTcpMessageHandler(socket: DoipTcpSocket): DoipTcpConnectionMessageHandler =
        DefaultDoipEntityTcpConnectionMessageHandler(
            doipEntity = this,
            socket = socket,
            logicalAddress = config.logicalAddress,
            maxPayloadLength = config.maxDataSize - 8,
            diagMessageHandler = this
        )

    protected open suspend fun sendVams(vams: List<DoipUdpVehicleAnnouncementMessage>, socket: BoundDatagramSocket) {
        var vamSentCounter = 0

        fixedRateTimer("VAM", daemon = true, initialDelay = 500, period = 500) {
            if (vamSentCounter >= 3) {
                this.cancel()
                return@fixedRateTimer
            }
            vams.forEach { vam ->
                logger.info("Sending VAM for ${vam.logicalAddress.toByteArray().toHexString()} as broadcast")
                runBlocking(Dispatchers.IO) {
                    MDC.put("ecu", name)
                    launch(MDCContext()) {
                        socket.send(
                            Datagram(
                                packet = ByteReadPacket(vam.asByteArray),
                                address = InetSocketAddress(config.broadcastAddress, 13400)
                            )
                        )
                    }
                }
            }

            vamSentCounter++
        }
    }

    protected open suspend fun startVamTimer(socket: BoundDatagramSocket) {
        if (config.broadcastEnabled) {
            val vams = DefaultDoipEntityUdpMessageHandler.generateVamByEntityConfig(this)
            sendVams(vams, socket)
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
        output.writeFully(response.asByteArray)
    }

    override fun existsTargetAddress(targetAddress: Short): Boolean =
        targetEcusByLogical.containsKey(targetAddress) || targetEcusByFunctional.containsKey(targetAddress)

    override suspend fun onIncomingDiagMessage(diagMessage: DoipTcpDiagMessage, output: ByteWriteChannel) {
        val ecu = targetEcusByLogical[diagMessage.targetAddress]
        ecu?.run {
            runBlocking {
                MDC.put("ecu", ecu.name)
                launch(MDCContext()) {
                    onIncomingUdsMessage(diagMessage.toUdsMessage(UdsMessage.PHYSICAL, output, ecu.config.logicalAddress))
                }
            }
            // Exit if the target ecu was found by physical
            return
        }

        val ecus = targetEcusByFunctional[diagMessage.targetAddress]
        ecus?.forEach {
            runBlocking {
                MDC.put("ecu", it.name)
                launch(MDCContext()) {
                    it.onIncomingUdsMessage(diagMessage.toUdsMessage(UdsMessage.FUNCTIONAL, output, it.config.logicalAddress))
                }
            }
        }
    }

    public open fun findEcuByName(name: String, ignoreCase: Boolean = true): T? =
        this.ecus.firstOrNull { name.equals(it.name, ignoreCase = ignoreCase) }

    protected open fun CoroutineScope.handleTcpSocket(socket: DoipTcpSocket, disableServerSocketCallback: (kotlin.time.Duration) -> Unit) {
        launch {
            logger.debugIf { "New incoming data connection from ${socket.remoteAddress}" }
            val tcpMessageHandler = createDoipTcpMessageHandler(socket)
            val input = socket.openReadChannel()
            val output = socket.openWriteChannel()
            try {
                connectionHandlers.add(tcpMessageHandler)
                while (!socket.isClosed) {
                    try {
                        val message = tcpMessageHandler.receiveTcpData(input)
                        tcpMessageHandler.handleTcpMessage(message, output)
                    } catch (e: ClosedReceiveChannelException) {
                        // ignore - socket was closed
                        logger.debugIf { "Socket was closed by remote ${socket.remoteAddress}" }
                        withContext(Dispatchers.IO) {
                            tcpMessageHandler.connectionClosed(e)
                            socket.runCatching { this.close() }
                        }
                    } catch (e: SocketException) {
                        logger.error("Socket error: ${e.message} -> closing socket")
                        withContext(Dispatchers.IO) {
                            tcpMessageHandler.connectionClosed(e)
                            socket.runCatching { this.close() }
                        }
                    } catch (e: HeaderNegAckException) {
                        if (!socket.isClosed) {
                            logger.debug("Error in Header while parsing message, sending negative acknowledgment", e)
                            val response =
                                DoipTcpHeaderNegAck(DoipTcpDiagMessageNegAck.NACK_CODE_TRANSPORT_PROTOCOL_ERROR).asByteArray
                            output.writeFully(response)
                            withContext(Dispatchers.IO) {
                                tcpMessageHandler.connectionClosed(e)
                                socket.runCatching { this.close() }
                            }
                        }
                    } catch (e: DoipEntityHardResetException) {
                        logger.warn("Simulating Hard Reset on ${this@DoipEntity.name} for ${e.duration.inWholeMilliseconds} ms")
                        output.flush()
                        socket.close()

                        disableServerSocketCallback(e.duration)
                    } catch (e: Exception) {
                        if (!socket.isClosed) {
                            logger.error("Unknown error parsing/handling message, sending negative acknowledgment", e)
                            val response =
                                DoipTcpHeaderNegAck(DoipTcpDiagMessageNegAck.NACK_CODE_TRANSPORT_PROTOCOL_ERROR).asByteArray
                            output.writeFully(response)
                            withContext(Dispatchers.IO) {
                                tcpMessageHandler.connectionClosed(e)
                                socket.runCatching { this.close() }
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                logger.error("Unknown error inside socket processing loop, closing socket", e)
            } finally {
                try {
                    withContext(Dispatchers.IO) {
                        tcpMessageHandler.closeSocket()
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
        runBlocking {
            MDC.put("ecu", name)
            launch(MDCContext()) {
                try {
                    logger.traceIf { "Incoming UDP message for $name" }
                    val message = udpMessageHandler.parseMessage(datagram)
                    logger.traceIf { "Message for $name is of type $message" }
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
    }

    private val serverSockets: MutableList<ServerSocket> = mutableListOf()

    public fun pauseTcpServerSockets(duration: kotlin.time.Duration) {
        logger.warn("Closing serversockets")
        serverSockets.forEach { try { it.close() } catch (ignored: Exception) {} }
        serverSockets.clear()
        logger.warn("Pausing server sockets for ${duration.inWholeMilliseconds} ms")
        Thread.sleep(duration.inWholeMilliseconds)
        logger.warn("Restarting server sockets after ${duration.inWholeMilliseconds} ms")
        runBlocking {
            launch {
                startVamTimer(udpServerSocket)
            }
            launch {
                startTcpServerSockets()
            }
        }
    }

    public fun startTcpServerSockets() {
        thread(name = "TCP") {
            runBlocking {
                val serverSocket =
                    aSocket(ActorSelectorManager(Dispatchers.IO))
                        .tcp()
                        .bind(InetSocketAddress(config.localAddress, config.localPort))
                serverSockets.add(serverSocket)
                logger.info("Listening on tcp: ${serverSocket.localAddress}")
                while (!serverSocket.isClosed) {
                    val socket = serverSocket.accept()
                    handleTcpSocket(DelegatedKtorSocket(socket), ::pauseTcpServerSockets)
                }
            }
        }

// TLS with ktor-network doesn't work yet https://youtrack.jetbrains.com/issue/KTOR-694
        if (config.tlsMode != TlsMode.DISABLED) {
            val tlsOptions = config.tlsOptions
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
                            config.tlsPort,
                            50,
                            InetAddress.getByName(config.localAddress)
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
                        withContext(Dispatchers.IO) {
                            val socket = tlsServerSocket.accept() as SSLSocket
                            handleTcpSocket(SSLDoipTcpSocket(socket), ::pauseTcpServerSockets)
                        }
                    }
                }
            }
        }
    }

    public fun start() {
        this._ecus.addAll(this.config.ecuConfigList.map { createEcu(it) })

        targetEcusByLogical = this.ecus.associateBy { it.config.logicalAddress }
        targetEcusByFunctional = _ecus.groupByTo(mutableMapOf()) { it.config.functionalAddress }

        _ecus.forEach {
            it.simStarted()
        }

        thread(name = "UDP") {
            runBlocking {
                udpServerSocket =
                    aSocket(ActorSelectorManager(Dispatchers.IO))
                        .udp()
                        .bind(localAddress = InetSocketAddress(config.localAddress, 13400)) {
                            broadcast = true
                            reuseAddress = true
//                            reusePort = true // not supported on windows
                            typeOfService = TypeOfService.IPTOS_RELIABILITY
//                            socket.joinGroup(multicastAddress)
                        }
                logger.info("Listening on udp: ${udpServerSocket.localAddress}")
                startVamTimer(udpServerSocket)
                val udpMessageHandler = createDoipUdpMessageHandler()

                if (config.localAddress != "0.0.0.0" && config.bindOnAnyForUdpAdditional) {
                    logger.info("Also listening on udp 0.0.0.0 for broadcasts")
                    val localAddress = InetSocketAddress("0.0.0.0", 13400)
                    val anyServerSocket =
                        aSocket(ActorSelectorManager(Dispatchers.IO))
                            .udp()
                            .bind(localAddress = localAddress) {
                                broadcast = true
                                reuseAddress = true
//                                reusePort = true // not supported on windows
                                typeOfService = TypeOfService.IPTOS_RELIABILITY
                            }
                    thread(start = true, isDaemon = true) {
                        runBlocking {
                            while (!anyServerSocket.isClosed) {
                                val datagram = anyServerSocket.receive()
                                if (datagram.address is InetSocketAddress) {
                                    if (datagram.address == localAddress) {
                                        continue
                                    }
                                }
                                handleUdpMessage(udpMessageHandler, datagram, anyServerSocket)
                            }
                        }
                    }
                }

                while (!udpServerSocket.isClosed) {
                    val datagram = udpServerSocket.receive()
                    handleUdpMessage(udpMessageHandler, datagram, udpServerSocket)
                }
            }
        }

        startTcpServerSockets()
    }
}
