import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.concurrent.thread
import kotlin.concurrent.timer

typealias GID = ByteArray
typealias EID = ByteArray
typealias VIN = ByteArray

open class DoipGatewayConfig(
    val name: String,
    val logicalAddress: Short,
    val gid: GID,
    val eid: EID,
    val vin: VIN,
    val maxDataSize: UInt = 0xFFFF.toUInt(),
    val localAddress: InetAddress = InetAddress.getByName("0.0.0.0"),
    val localPort: Int = 13400,
    val broadcastEnabled: Boolean = true,
    val broadcastAddress: InetAddress = InetAddress.getByName("255.255.255.255"),
    // TODO tlsPort
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

open class DoipGateway(
    val config: DoipGatewayConfig,
) {
    private val logger = LoggerFactory.getLogger(DoipGateway::class.java)

    protected fun createDoipUdpMessageHandler(): DoipUdpMessageHandler =
        DefaultDoipUdpMessageHandler(
            config
        )

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
                val udpMessageHandler = createDoipUdpMessageHandler()
                while (!socket.isClosed) {
                    val datagram = socket.receive()
                    val message = DoipUDPMessageParser.parseUDP(datagram.packet)
                    MDC.put("ecuName", config.name)
                    udpMessageHandler.handleUdpMessage(socket.outgoing, datagram.address, message)
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
                        val input = socket.openReadChannel()
                        val output = socket.openWriteChannel(autoFlush = true)
                        try {
                            while (!socket.isClosed) {
                                val line = input.readUTF8Line() ?: break

                                println("${socket.remoteAddress}: $line")
                                output.writeStringUtf8("$line\r\n")
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
