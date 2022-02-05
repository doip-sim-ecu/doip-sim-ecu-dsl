import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.util.network.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import kotlin.concurrent.thread

class DoipEntities {
    fun start() {
        thread(name = "UDP-RECV") {
            runBlocking {
                val socket =
                    aSocket(ActorSelectorManager(Dispatchers.IO)).udp().bind(InetSocketAddress("0.0.0.0", 13400)) {
                        this.broadcast = true
//                        socket.joinGroup(multicastAddress)
                    }

                while (!socket.isClosed) {
                    val datagram = socket.receive()
                    if (datagram.address.port != 13400) {
                        val data = datagram.packet.readText()
                        println(data)
                        socket.send(
                            Datagram(
                                ByteReadPacket(byteArrayOf(1, 2, 3)),
                                InetSocketAddress("255.255.255.255", 13400)
                            )
                        )
                    }
                }
            }
        }

        thread(name = "TCP_RECV") {
            runBlocking {
                val serverSocket =
                    aSocket(ActorSelectorManager(Dispatchers.IO)).tcp().bind(InetSocketAddress("0.0.0.0", 13400))
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
