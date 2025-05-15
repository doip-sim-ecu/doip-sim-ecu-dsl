package library

import io.ktor.network.sockets.*
import io.ktor.util.network.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import io.ktor.utils.io.streams.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.io.Sink
import java.io.Closeable
import javax.net.ssl.SSLSocket

public enum class SocketType {
    TCP_DATA,
    TLS_DATA,
}

public interface DoipTcpSocket : AutoCloseable, Closeable {
    public val remoteAddress: SocketAddress
        get() = getSocketRemoteAddress()

    public val isClosed: Boolean
        get() = isSocketClosed()

    public val socketType: SocketType

    public fun isSocketClosed(): Boolean
    public fun getSocketRemoteAddress(): SocketAddress
    public fun openReadChannel(): ByteReadChannel
    public fun openWriteChannel(): ByteWriteChannel
    override fun close()
}

internal class DelegatedKtorSocket(private val socket: Socket) : DoipTcpSocket {
    override fun isSocketClosed(): Boolean =
        socket.isClosed

    override fun getSocketRemoteAddress(): SocketAddress =
        socket.remoteAddress

    override fun openReadChannel(): ByteReadChannel =
        socket.openReadChannel()

    override fun openWriteChannel(): ByteWriteChannel =
        socket.openWriteChannel(true)

    override fun close() =
        socket.close()

    override val socketType: SocketType
        get() = SocketType.TCP_DATA
}

internal class SSLDoipTcpSocket(private val socket: SSLSocket) : DoipTcpSocket {
    private val _remoteAddress = InetSocketAddress(socket.remoteSocketAddress.hostname, socket.remoteSocketAddress.port)

    override fun isSocketClosed(): Boolean =
        socket.isClosed

    override fun getSocketRemoteAddress(): SocketAddress =
        _remoteAddress

    override fun openReadChannel(): ByteReadChannel =
        socket.inputStream.toByteReadChannel()

    override fun openWriteChannel(): ByteWriteChannel =
        socket.outputStream.asByteWriteChannel()

    override fun close() =
        socket.close()

    override val socketType: SocketType
        get() = SocketType.TLS_DATA
}

internal class OutputStreamByteWriteChannel(private val sink: Sink) : ByteWriteChannel {
    private var closed = false
    private var cancelCause: Throwable? = null

    @InternalAPI
    override val writeBuffer: Sink
        get() = sink

    override suspend fun flush() {
        withContext(Dispatchers.IO) {
            sink.flush()
        }
    }

    override val isClosedForWrite: Boolean
        get() = closed

    override val closedCause: Throwable
        get() = closedCause

    override suspend fun flushAndClose() {
        sink.flush()
        sink.close()
        closed = true
    }

    override fun cancel(cause: Throwable?) {
        cancelCause = cause
        runBlocking {
            flushAndClose()
        }
    }
}
