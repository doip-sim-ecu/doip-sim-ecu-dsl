package library

import io.ktor.network.sockets.*
import io.ktor.util.network.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import java.io.Closeable
import java.io.OutputStream
import javax.net.ssl.SSLSocket

enum class SocketType {
    TCP_DATA,
    TLS_DATA,
}

interface DoipTcpSocket : AutoCloseable, Closeable {
    val remoteAddress: SocketAddress
        get() = getSocketRemoteAddress()

    val isClosed: Boolean
        get() = isSocketClosed()

    val socketType: SocketType

    fun isSocketClosed(): Boolean
    fun getSocketRemoteAddress(): SocketAddress
    fun openReadChannel(): ByteReadChannel
    fun openOutputStream(): OutputStream
    override fun close()
}

class DelegatedKtorSocket(private val socket: Socket) : DoipTcpSocket {
    override fun isSocketClosed(): Boolean =
        socket.isClosed

    override fun getSocketRemoteAddress(): SocketAddress =
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
    private val _remoteAddress = InetSocketAddress(socket.remoteSocketAddress.hostname, socket.remoteSocketAddress.port)

    override fun isSocketClosed(): Boolean =
        socket.isClosed

    override fun getSocketRemoteAddress(): SocketAddress =
        _remoteAddress

    override fun openReadChannel(): ByteReadChannel =
        socket.inputStream.toByteReadChannel()

    override fun openOutputStream(): OutputStream =
        socket.outputStream

    override fun close() =
        socket.close()

    override val socketType: SocketType
        get() = SocketType.TLS_DATA
}
