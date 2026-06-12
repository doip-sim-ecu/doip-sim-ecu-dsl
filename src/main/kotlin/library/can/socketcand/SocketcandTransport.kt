package library.can.socketcand

import io.ktor.network.selector.ActorSelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import library.can.CanFrame
import library.can.CanTransport
import org.slf4j.LoggerFactory
import java.io.EOFException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * CAN transport connecting to a socketcand daemon
 * (https://github.com/linux-can/socketcand) in rawmode over TCP.
 *
 * Note: the socketcand protocol has no CAN FD frame support.
 */
public class SocketcandTransport(
    private val host: String,
    private val port: Int = SocketcandProtocol.DEFAULT_PORT,
    private val busName: String,
    private val reconnect: Boolean = true,
) : CanTransport {
    private val logger = LoggerFactory.getLogger(SocketcandTransport::class.java)

    override val name: String = "socketcand://$host:$port/$busName"

    override val supportsFd: Boolean = false

    private val _incomingFrames = MutableSharedFlow<CanFrame>(extraBufferCapacity = 1024)

    override val incomingFrames: SharedFlow<CanFrame> = _incomingFrames

    private val selectorManager = ActorSelectorManager(Dispatchers.IO)
    private val writeMutex = Mutex()
    private val closed = AtomicBoolean(false)

    @Volatile
    private var connection: Connection? = null

    private class Connection(
        val socket: Socket,
        val readChannel: ByteReadChannel,
        val writeChannel: ByteWriteChannel,
    ) {
        private val tokenizer = SocketcandTokenizer()
        private val pendingMessages = ArrayDeque<SocketcandMessage>()
        private val readBuffer = ByteArray(4096)

        suspend fun readMessage(): SocketcandMessage {
            while (pendingMessages.isEmpty()) {
                val count = readChannel.readAvailable(readBuffer, 0, readBuffer.size)
                if (count == -1) {
                    throw EOFException("Connection closed by socketcand")
                }
                tokenizer.feed(String(readBuffer, 0, count, Charsets.US_ASCII))
                    .forEach { pendingMessages.add(SocketcandProtocol.parse(it)) }
            }
            return pendingMessages.removeFirst()
        }
    }

    override suspend fun start(scope: CoroutineScope) {
        connect()
        scope.launch {
            readLoop()
        }
    }

    override suspend fun sendFrame(frame: CanFrame) {
        require(!frame.fd) { "socketcand doesn't support CAN FD frames" }
        val conn = connection ?: throw IllegalStateException("Not connected to $name")
        writeMutex.withLock {
            conn.writeChannel.writeStringUtf8(SocketcandProtocol.serializeSend(frame))
            conn.writeChannel.flush()
        }
    }

    override fun close() {
        closed.set(true)
        connection?.socket?.close()
        connection = null
        selectorManager.close()
    }

    private suspend fun connect() {
        logger.debug("Connecting to socketcand at $host:$port (bus $busName)")
        val socket = aSocket(selectorManager).tcp().connect(host, port)
        try {
            val conn = Connection(socket, socket.openReadChannel(), socket.openWriteChannel(autoFlush = true))
            expect(conn, SocketcandMessage.Hi::class.java, "greeting")
            conn.writeChannel.writeStringUtf8(SocketcandProtocol.serializeOpen(busName))
            expect(conn, SocketcandMessage.Ok::class.java, "response to '< open >'")
            conn.writeChannel.writeStringUtf8(SocketcandProtocol.RAWMODE)
            expect(conn, SocketcandMessage.Ok::class.java, "response to '< rawmode >'")
            connection = conn
            logger.info("Connected to $name")
        } catch (e: Exception) {
            socket.close()
            throw e
        }
    }

    private suspend fun expect(conn: Connection, expected: Class<out SocketcandMessage>, context: String) {
        while (true) {
            when (val message = conn.readMessage()) {
                is SocketcandMessage.Error ->
                    throw IllegalStateException("socketcand at $host:$port returned an error while waiting for $context: ${message.text}")
                else -> {
                    if (expected.isInstance(message)) {
                        return
                    }
                    if (message !is SocketcandMessage.Frame) {
                        throw IllegalStateException("Unexpected message from socketcand at $host:$port while waiting for $context")
                    }
                    // ignore frames that were already in flight
                }
            }
        }
    }

    private suspend fun readLoop() {
        var reconnectAttempt = 0
        while (!closed.get() && currentCoroutineContextIsActive()) {
            val conn = connection
            if (conn == null) {
                if (!reconnect) {
                    break
                }
                try {
                    delay(minOf(30.seconds, 1.seconds * (1 shl minOf(reconnectAttempt, 5))))
                    reconnectAttempt++
                    connect()
                    reconnectAttempt = 0
                } catch (e: Exception) {
                    logger.warn("Reconnect to $name failed (attempt $reconnectAttempt): ${e.message}")
                }
                continue
            }
            try {
                when (val message = conn.readMessage()) {
                    is SocketcandMessage.Frame -> _incomingFrames.emit(message.frame)
                    is SocketcandMessage.Error -> logger.warn("socketcand error on $name: ${message.text}")
                    else -> logger.debug("Ignoring unexpected socketcand message on $name")
                }
            } catch (e: Exception) {
                if (closed.get()) {
                    break
                }
                logger.warn("Connection to $name lost: ${e.message}")
                conn.socket.close()
                connection = null
                if (!reconnect) {
                    break
                }
            }
        }
    }

    private suspend fun currentCoroutineContextIsActive(): Boolean =
        kotlinx.coroutines.currentCoroutineContext().isActive
}
