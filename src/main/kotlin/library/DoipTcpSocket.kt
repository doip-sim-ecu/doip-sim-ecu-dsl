package library

import io.ktor.network.sockets.*
import io.ktor.util.*
import io.ktor.util.network.*
import io.ktor.utils.io.*
import io.ktor.utils.io.bits.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.core.internal.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.io.OutputStream
import java.nio.ByteBuffer
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
        OutputStreamByteWriteChannel(socket.outputStream)

    override fun close() =
        socket.close()

    override val socketType: SocketType
        get() = SocketType.TLS_DATA
}

internal class OutputStreamByteWriteChannel(private val outputStream: OutputStream) : ByteWriteChannel {
    private var closed = false
    private var exception: Throwable? = null
    private var _totalBytesWritten: Long = 0
    override val autoFlush: Boolean
        get() = true
    override val availableForWrite: Int
        get() = Integer.MAX_VALUE
    override val closedCause: Throwable?
        get() = exception
    override val isClosedForWrite: Boolean
        get() = closed
    override val totalBytesWritten: Long
        get() = _totalBytesWritten

    override suspend fun awaitFreeSpace() {
    }

    override fun close(cause: Throwable?): Boolean {
        exception = cause
        outputStream.close()
        return true
    }

    override fun flush() {
        outputStream.flush()
    }

    override suspend fun write(min: Int, block: (ByteBuffer) -> Unit) {
        val bb = ByteBuffer.allocate(min)
        block.invoke(bb)
        runBlocking {
            outputStream.write(bb.moveToByteArray())
        }
    }

    @Suppress("DEPRECATION")
    override suspend fun writeAvailable(src: ChunkBuffer): Int {
        TODO("Not yet implemented")
    }

    override suspend fun writeAvailable(src: ByteBuffer): Int {
        TODO("Not yet implemented")
    }

    override suspend fun writeAvailable(src: ByteArray, offset: Int, length: Int): Int {
        TODO("Not yet implemented")
    }

    override fun writeAvailable(min: Int, block: (ByteBuffer) -> Unit): Int {
        TODO("Not yet implemented")
    }

    override suspend fun writeByte(b: Byte) {
        TODO("Not yet implemented")
    }

    override suspend fun writeDouble(d: Double) {
        TODO("Not yet implemented")
    }

    override suspend fun writeFloat(f: Float) {
        TODO("Not yet implemented")
    }

    override suspend fun writeFully(memory: Memory, startIndex: Int, endIndex: Int) {
        TODO("Not yet implemented")
    }

    @Suppress("DEPRECATION")
    override suspend fun writeFully(src: Buffer) {
        TODO("Not yet implemented")
    }

    override suspend fun writeFully(src: ByteBuffer) {
        runBlocking {
            outputStream.write(src.moveToByteArray())
        }
    }

    override suspend fun writeFully(src: ByteArray, offset: Int, length: Int) {
        TODO("Not yet implemented")
    }

    override suspend fun writeInt(i: Int) {
        TODO("Not yet implemented")
    }

    override suspend fun writeLong(l: Long) {
        TODO("Not yet implemented")
    }

    override suspend fun writePacket(packet: ByteReadPacket) {
        TODO("Not yet implemented")
    }

    override suspend fun writeShort(s: Short) {
        TODO("Not yet implemented")
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override suspend fun writeSuspendSession(visitor: suspend WriterSuspendSession.() -> Unit) {
        TODO("Not yet implemented")
    }

    override suspend fun writeWhile(block: (ByteBuffer) -> Boolean) {
        TODO("Not yet implemented")
    }
}
