@file:Suppress("unused")

package client

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import library.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.nio.ByteBuffer
import kotlin.concurrent.fixedRateTimer
import kotlin.concurrent.thread
import kotlin.time.Duration

public class DoipClient(
    private val broadcastAddress: SocketAddress = InetSocketAddress("255.255.255.255", 13400),
) : Closeable, AutoCloseable {
    private lateinit var udpServerSocket: BoundDatagramSocket
    private val _doipEntities: MutableMap<Short, DoipEntityAnnouncement> = mutableMapOf()

    public val doipEntities: Map<Short, DoipEntityAnnouncement>
        get() = _doipEntities.toMap()

    init {
        startListening()
        sendVirs()
    }

    public fun connectToEntity(
        address: SocketAddress,
        testerAddress: Short = 0xe80.toShort(),
        timeout: Duration = Duration.INFINITE
    ): DoipEntityTcpConnection {
        return runBlocking {
            withTimeout(timeout) {
                val socket = aSocket(ActorSelectorManager(Dispatchers.IO))
                    .tcp()
                    .connect(address)
                val connection = DoipEntityTcpConnection(socket, testerAddress)
                connection
            }
        }
    }

    public fun waitForVAM(timeout: Duration = Duration.INFINITE, logicalAddress: Short? = null): Boolean =
        runBlocking {
            withTimeoutOrNull(timeout) {
                val job = launch {
                    while (
                        _doipEntities.isEmpty() ||
                        (logicalAddress != null && !_doipEntities.containsKey(logicalAddress))
                    ) {
                        delay(10)
                    }
                }
                job.join()
                true
            } ?: false
        }

    private fun sendVirs() {
        var virSentCounter = 0

        fixedRateTimer("UDP_SEND_VIR", daemon = true, initialDelay = 100, period = 1000) {
            if (virSentCounter >= 3) {
                this.cancel()
                return@fixedRateTimer
            }

            runBlocking {
                logger.info("Sending VIR")
                udpServerSocket.send(
                    Datagram(
                        packet = ByteReadPacket(DoipUdpVehicleInformationRequest().asByteArray),
                        address = broadcastAddress
                    )
                )
                virSentCounter++
            }
        }
    }

    private fun startListening() {
        udpServerSocket = aSocket(ActorSelectorManager(Dispatchers.IO))
            .udp()
            .bind {
                broadcast = true
                reuseAddress = true
            }

        thread(name = "UDP_RECV", isDaemon = true) {
            runBlocking {
                val handler = DoipClientUdpMessageHandler()
                while (!udpServerSocket.isClosed) {
                    try {
                        val datagram = udpServerSocket.receive()
                        val message = handler.parseMessage(datagram)
                        if (message is DoipUdpVehicleAnnouncementMessage) {
                            val sourceAddress = datagram.address
                            _doipEntities[message.logicalAddress] = DoipEntityAnnouncement(sourceAddress, message)
                            logger.info("Received VAM for address ${message.logicalAddress.toString(16)} from udp:$sourceAddress")
                        }
                    } catch (e: CancellationException) {
                        // ignore
                    }
                }
            }
        }
    }

    override fun close() {
        udpServerSocket.close()
    }

    private companion object {
        private val logger: Logger = LoggerFactory.getLogger(DoipClient::class.java)
    }
}

public class DoipEntityTcpConnection(socket: Socket, private val testerAddress: Short) {
    private val writeChannel = socket.openWriteChannel()
    private val readChannel = socket.openReadChannel()

    init {
        runBlocking {
            writeChannel.writeFully(ByteBuffer.wrap(DoipTcpRoutingActivationRequest(testerAddress).asByteArray))
            writeChannel.flush()

            val msg = DoipTcpConnectionMessageHandler().receiveTcpData(readChannel) as DoipTcpRoutingActivationResponse
            if (msg.responseCode != DoipTcpRoutingActivationResponse.RC_OK) {
                throw ConnectException("Routing activation failed (${msg.responseCode})")
            }
        }
    }

//    inline fun <reified T, reified Y> callEcu(
//        targetAddress: Short,
//        message: Y,
//        waitTimeout: Duration = 5.seconds
//    ): T {
//        lateinit var messageOut: ByteArray
//        if (message is ByteArray) {
//            messageOut = message
//        }
//        sendDiagnosticMessage(targetAddress, messageOut, waitTimeout = waitTimeout) {
//            val returnClass = T::class
//            if (returnClass == ByteArray::class) {
//            }
//        }
//    }

    public fun sendDiagnosticMessage(
        targetAddress: Short,
        message: ByteArray,
        waitForResponse: Boolean = true,
        waitTimeout: Duration = Duration.INFINITE,
        responseHandler: (ByteArray) -> Unit = {},
    ) {
        return runBlocking {
            val request = DoipTcpDiagMessage(
                testerAddress,
                targetAddress,
                message
            )
            writeChannel.writeFully(ByteBuffer.wrap(request.asByteArray))
            writeChannel.flush()
            val handler = DoipTcpConnectionMessageHandler()
            val diagResponse = handler.receiveTcpData(readChannel)

            if (diagResponse is DoipTcpDiagMessagePosAck) {
                if (waitForResponse) {
                    val response = withTimeoutOrNull(timeout = waitTimeout) {
                        var msg: DoipTcpMessage?
                        do {
                            msg = handler.receiveTcpData(readChannel)
                        } while (msg !is DoipTcpDiagMessage)
                        msg
                    } ?: throw RuntimeException("No response within $waitTimeout")

                    responseHandler.invoke(response.payload)
                }
            } else {
                throw RuntimeException("Response isn't positive ($diagResponse)")
            }
        }
    }
}

public data class DoipEntityAnnouncement(val sourceAddress: SocketAddress, val message: DoipUdpVehicleAnnouncementMessage)

private class DoipClientUdpMessageHandler : DoipUdpMessageHandler

public class ConnectException(msg: String) : RuntimeException(msg)

