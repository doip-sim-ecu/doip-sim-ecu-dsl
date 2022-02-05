import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.SendChannel
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.concurrent.fixedRateTimer
import kotlin.concurrent.thread

typealias GID = ByteArray
typealias EID = ByteArray
typealias VIN = ByteArray

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
    val ecuConfigList: MutableList<EcuConfig> = mutableListOf()
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
    protected val logger = LoggerFactory.getLogger(DoipEntity::class.java)

    protected var targetEcusByPhysical: Map<Short, SimulatedEcu>
    protected var targetEcusByFunctional: MutableMap<Short, MutableList<SimulatedEcu>>

    protected var vamSentCounter = 0

    init {
        targetEcusByPhysical = this.config.ecuConfigList.associate { Pair(it.physicalAddress, createEcu(it)) }
        targetEcusByFunctional = mutableMapOf()
        targetEcusByPhysical.forEach {
            val list = targetEcusByFunctional[it.key]
            if (list == null) {
                targetEcusByFunctional[it.key] = mutableListOf(it.value)
            } else {
                list.add(it.value)
            }
        }
    }

    protected open fun createEcu(config: EcuConfig): SimulatedEcu =
        SimulatedEcu(config)

    protected open fun createDoipUdpMessageHandler(): DoipUdpMessageHandler =
        DefaultDoipUdpMessageHandler(
            config
        )

    protected open fun createDoipTcpMessageHandler(socket: Socket): DoipTcpConnectionMessageHandler =
        DefaultDoipTcpConnectionMessageHandler(
            socket = socket,
            logicalAddress = config.logicalAddress,
            maxPayloadLength = config.maxDataSize - 8,
            diagMessageHandler = this
        )

    protected suspend fun startVamTimer(sendChannel: SendChannel<Datagram>) {
        if (config.broadcastEnabled) {
            fixedRateTimer("${config.name}-VAM", daemon = true, initialDelay = 500, period = 500) {
                if (vamSentCounter >= 3) {
                    this.cancel()
                    return@fixedRateTimer
                }
                val vam = DefaultDoipUdpMessageHandler.generateVamByEntityConfig(config)
                runBlocking(Dispatchers.IO) {
                    sendChannel.send(
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
        ecu?.run { onIncomingUdsMessage(diagMessage.toUdsMessage(UdsMessage.PHYSICAL, output)) }

        val ecus = targetEcusByFunctional[diagMessage.targetAddress]
        ecus?.forEach {
            it.onIncomingUdsMessage(diagMessage.toUdsMessage(UdsMessage.FUNCTIONAL, output))
        }
    }

    fun start() {
        thread(name = "UDP-RECV") {
            runBlocking {
                val socket =
                    aSocket(ActorSelectorManager(Dispatchers.IO))
                        .udp()
                        .bind(InetSocketAddress(config.localAddress, config.localPort)) {
                            this.broadcast = true
//                        socket.joinGroup(multicastAddress)
                        }
                startVamTimer(socket.outgoing)
                val udpMessageHandler = createDoipUdpMessageHandler()
                while (!socket.isClosed) {
                    val datagram = socket.receive()
                    try {
                        MDC.put("ecuName", config.name)
                        val message = udpMessageHandler.parseMessage(datagram)
                        udpMessageHandler.handleUdpMessage(socket.outgoing, datagram.address, message)
                    } catch (e: HeaderNegAckException) {
                        val code = when (e) {
                            is IncorrectPatternFormat -> DoipUdpHeaderNegAck.NACK_INCORRECT_PATTERN_FORMAT
                            is HeaderTooShort -> DoipUdpHeaderNegAck.NACK_INCORRECT_PATTERN_FORMAT
                            is InvalidPayloadLength -> DoipUdpHeaderNegAck.NACK_INVALID_PAYLOAD_LENGTH
                            is UnknownPayloadType -> DoipUdpHeaderNegAck.NACK_UNKNOWN_PAYLOAD_TYPE
                            else -> {
                                e.printStackTrace(); DoipUdpHeaderNegAck.NACK_UNKNOWN_PAYLOAD_TYPE
                            } // TODO log message
                        }
                        udpMessageHandler.respondHeaderNegAck(
                            socket.outgoing,
                            datagram.address,
                            code
                        )
                    } catch (e: Exception) {
                        // TODO log
                        e.printStackTrace()
                    }
                }
            }
        }

        thread(name = "TCP-RECV") {
            runBlocking {
                val serverSocket =
                    aSocket(ActorSelectorManager(Dispatchers.IO))
                        .tcp()
                        .bind(InetSocketAddress(config.localAddress, config.localPort))
                while (!serverSocket.isClosed) {
                    val socket = serverSocket.accept()
                    launch {
                        val tcpMessageReceiver = createDoipTcpMessageHandler(socket)
                        val input = socket.openReadChannel()
                        val output = socket.openWriteChannel(autoFlush = tcpMessageReceiver.isAutoFlushEnabled())
                        try {
                            while (!socket.isClosed) {
                                try {
                                    val message = tcpMessageReceiver.receiveTcpData(input)
                                    tcpMessageReceiver.handleTcpMessage(message, output)
                                } catch (e: HeaderNegAckException) {
                                    e.printStackTrace()
                                    val response =
                                        DoipTcpHeaderNegAck(DoipTcpDiagMessageNegAck.NACK_CODE_TRANSPORT_PROTOCOL_ERROR).message
                                    output.writeFully(response, 0, response.size)
                                    output.flush()
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    val response =
                                        DoipTcpHeaderNegAck(DoipTcpDiagMessageNegAck.NACK_CODE_TRANSPORT_PROTOCOL_ERROR).message
                                    output.writeFully(response, 0, response.size)
                                    output.flush()
                                }
                            }
                        } catch (e: Throwable) {
                            e.printStackTrace()
                        } finally {
                            withContext(Dispatchers.IO) {
                                socket.close()
                            }
                        }
                    }
                }

            }
        }
    }
}
