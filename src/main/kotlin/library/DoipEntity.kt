package library

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import nl.altindag.ssl.SSLFactory
import nl.altindag.ssl.util.PemUtils
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Paths
import javax.net.ssl.*
import kotlin.concurrent.fixedRateTimer
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

typealias GID = ByteArray
typealias EID = ByteArray
typealias VIN = ByteArray


enum class DoipNodeType(val value: Byte) {
    GATEWAY(0),
    NODE(1)
}

enum class TlsMode {
    DISABLED,
    OPTIONAL,
    MANDATORY,
}

data class TlsOptions(
    val tlsCert: File? = null,
    val tlsKey: File? = null,
    val tlsKeyPassword: String? = null,
    val tlsCiphers: List<String>? = DefaultTlsCiphers,
    val tlsProtocols: List<String>? = DefaultTlsProtocols,
)

@Suppress("unused")
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
    val pendingNrcSendInterval: kotlin.time.Duration = 2.seconds,
    val tlsMode: TlsMode = TlsMode.DISABLED,
    val tlsPort: Int = 3496,
    val tlsOptions: TlsOptions = TlsOptions(),
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

/**
 * DoIP-Entity
 */
open class DoipEntity(
    val config: DoipEntityConfig,
) : DiagnosticMessageHandler {
    val name: String =
        config.name

    protected val logger = LoggerFactory.getLogger(DoipEntity::class.java)

    protected var targetEcusByPhysical: Map<Short, SimulatedEcu> = emptyMap()
    protected var targetEcusByFunctional: MutableMap<Short, MutableList<SimulatedEcu>> = mutableMapOf()

    val connectionHandlers: MutableList<DoipTcpConnectionMessageHandler> = mutableListOf()

    private val _ecus: MutableList<SimulatedEcu> = mutableListOf()

    val ecus: List<SimulatedEcu>
        get() = _ecus

    protected open fun createEcu(config: EcuConfig): SimulatedEcu =
        SimulatedEcu(config)

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

    protected open suspend fun sendVams(vam: DoipUdpVehicleAnnouncementMessage, socket: BoundDatagramSocket) {
        var vamSentCounter = 0

        fixedRateTimer("VAM", daemon = true, initialDelay = 500, period = 500) {
            MDC.put("ecu", name)
            if (vamSentCounter >= 3) {
                this.cancel()
                return@fixedRateTimer
            }
            logger.info("Sending VAM for ${vam.logicalAddress.toByteArray().toHexString()}")
            runBlocking(Dispatchers.IO) {
                socket.send(
                    Datagram(
                        packet = ByteReadPacket(vam.asByteArray),
                        address = InetSocketAddress(config.broadcastAddress, 13400)
                    )
                )
            }

            vamSentCounter++
        }
    }

    protected open suspend fun startVamTimer(socket: BoundDatagramSocket) {
        if (config.broadcastEnabled) {
            val vam = DefaultDoipEntityUdpMessageHandler.generateVamByEntityConfig(config)
            sendVams(vam, socket)
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
        targetEcusByPhysical.containsKey(targetAddress) || targetEcusByFunctional.containsKey(targetAddress)

    override suspend fun onIncomingDiagMessage(diagMessage: DoipTcpDiagMessage, output: OutputStream) {
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

    protected open fun CoroutineScope.handleTcpSocket(socket: DoipTcpSocket) {
        launch {
            logger.debugIf { "New incoming data connection from ${socket.remoteAddress}" }
            val tcpMessageHandler = createDoipTcpMessageHandler(socket)
            val input = socket.openReadChannel()
            val output = socket.openOutputStream()
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
                                DoipTcpHeaderNegAck(DoipTcpDiagMessageNegAck.NACK_CODE_TRANSPORT_PROTOCOL_ERROR).asByteArray
                            output.writeFully(response)
                        }
                    } catch (e: Exception) {
                        if (!socket.isClosed) {
                            logger.error("Unknown error parsing/handling message, sending negative acknowledgment", e)
                            val response =
                                DoipTcpHeaderNegAck(DoipTcpDiagMessageNegAck.NACK_CODE_TRANSPORT_PROTOCOL_ERROR).asByteArray
                            output.writeFully(response)
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
                val serverSocket =
                    aSocket(ActorSelectorManager(Dispatchers.IO))
                        .udp()
                        .bind(localAddress = InetSocketAddress(config.localAddress, 13400)) {
                            broadcast = true
                            reuseAddress = true
//                        socket.joinGroup(multicastAddress)
                        }
                logger.info("Listening on udp: ${serverSocket.localAddress}")
                startVamTimer(serverSocket)
                val udpMessageHandler = createDoipUdpMessageHandler()
                while (!serverSocket.isClosed) {
                    val datagram = serverSocket.receive()
                    handleUdpMessage(udpMessageHandler, datagram, serverSocket)
                }
            }
        }

        thread(name = "TCP") {
            runBlocking {
                val serverSocket =
                    aSocket(ActorSelectorManager(Dispatchers.IO))
                        .tcp()
                        .bind(InetSocketAddress(config.localAddress, config.localPort))
                logger.info("Listening on tcp: ${serverSocket.localAddress}")
                while (!serverSocket.isClosed) {
                    val socket = serverSocket.accept()
                    handleTcpSocket(DelegatedKtorSocket(socket))
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
                    val key = PemUtils.loadIdentityMaterial(Paths.get(tlsOptions.tlsCert.toURI()), Paths.get(tlsOptions.tlsKey.toURI()), tlsOptions.tlsKeyPassword?.toCharArray())
                    val trustMaterial = PemUtils.loadTrustMaterial(Paths.get(tlsOptions.tlsCert.toURI()))

                    val sslFactory = SSLFactory.builder()
                        .withIdentityMaterial(key)
                        .withTrustMaterial(trustMaterial)
                        .build()

                    val tlsServerSocket = withContext(Dispatchers.IO) {
                        (sslFactory.sslServerSocketFactory.createServerSocket(config.tlsPort, 50, config.localAddress) as SSLServerSocket)
                    }
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

                    logger.debug("Enabled TLS protocols: ${tlsServerSocket.enabledProtocols.joinToString(", ")}")
                    logger.debug("Enabled TLS cipher suites: ${tlsServerSocket.enabledCipherSuites.joinToString(", ")}")

                    while (!tlsServerSocket.isClosed) {
                        withContext(Dispatchers.IO) {
                            val socket = tlsServerSocket.accept() as SSLSocket
                            handleTcpSocket(SSLDoipTcpSocket(socket))
                        }
                    }
                }
            }
        }
    }
}
