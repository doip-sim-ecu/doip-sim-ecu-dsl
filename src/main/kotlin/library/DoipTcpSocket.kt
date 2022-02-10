package library

import io.ktor.network.sockets.*
import io.ktor.util.network.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.jvm.javaio.*
import java.io.OutputStream
import javax.net.ssl.SSLSocket

enum class SocketType {
    TCP_DATA,
    TLS_DATA,
}

interface DoipTcpSocket {
    val remoteAddress: NetworkAddress
        get() = getSocketRemoteAddress()

    val isClosed: Boolean
        get() = isSocketClosed()

    val socketType: SocketType

    fun isSocketClosed(): Boolean
    fun getSocketRemoteAddress(): NetworkAddress
    fun openReadChannel(): ByteReadChannel
    fun openOutputStream(): OutputStream
    fun close()
}

class DelegatedKtorSocket(private val socket: Socket) : DoipTcpSocket  {
    override fun isSocketClosed(): Boolean =
        socket.isClosed

    override fun getSocketRemoteAddress(): NetworkAddress =
        socket.remoteAddress

    override fun openReadChannel(): ByteReadChannel =
        socket.openReadChannel()

    override fun openOutputStream(): OutputStream =
        socket.openWriteChannel(true).toOutputStream()

    override fun close() =
        socket.close()

    override val socketType: SocketType
        get() = SocketType.TCP_DATA
}

class SSLDoipTcpSocket(private val socket: SSLSocket) : DoipTcpSocket {
    override fun isSocketClosed(): Boolean =
        socket.isClosed

    override fun getSocketRemoteAddress(): NetworkAddress =
        socket.remoteSocketAddress

    @OptIn(ExperimentalIoApi::class)
    override fun openReadChannel(): ByteReadChannel =
        socket.inputStream.toByteReadChannel()

    override fun openOutputStream(): OutputStream =
        socket.outputStream

    override fun close() =
        socket.close()

    override val socketType: SocketType
        get() = SocketType.TLS_DATA
}
