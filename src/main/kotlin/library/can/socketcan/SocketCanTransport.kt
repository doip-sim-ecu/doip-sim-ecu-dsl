package library.can.socketcan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import library.can.CanFrame
import library.can.CanTransport
import org.slf4j.LoggerFactory
import tel.schich.javacan.CanChannels
import tel.schich.javacan.CanSocketOptions
import tel.schich.javacan.NetworkDevice
import tel.schich.javacan.RawCanChannel
import java.util.concurrent.atomic.AtomicBoolean
import tel.schich.javacan.CanFrame as JavaCanFrame

/**
 * CAN transport using a SocketCAN raw socket via the optional
 * `tel.schich:javacan-core` dependency (Linux only). Works with any SocketCAN
 * network device: real hardware, vcan and vxcan pairs.
 */
public class SocketCanTransport(
    private val interfaceName: String,
    private val enableFd: Boolean = false,
) : CanTransport {
    private val logger = LoggerFactory.getLogger(SocketCanTransport::class.java)

    override val name: String = "socketcan://$interfaceName"

    override val supportsFd: Boolean = true

    private val _incomingFrames = MutableSharedFlow<CanFrame>(extraBufferCapacity = 1024)

    override val incomingFrames: SharedFlow<CanFrame> = _incomingFrames

    private val closed = AtomicBoolean(false)
    private val writeMutex = Mutex()

    @Volatile
    private var channel: RawCanChannel? = null

    override suspend fun start(scope: CoroutineScope) {
        val channel = withContext(Dispatchers.IO) {
            val ch = CanChannels.newRawChannel()
            try {
                ch.bind(NetworkDevice.lookup(interfaceName))
                if (enableFd) {
                    ch.setOption(CanSocketOptions.FD_FRAMES, true)
                }
            } catch (e: Exception) {
                ch.close()
                throw e
            }
            ch
        }
        this.channel = channel
        scope.launch(Dispatchers.IO) {
            readLoop(channel)
        }
    }

    override suspend fun sendFrame(frame: CanFrame) {
        val channel = this.channel ?: throw IllegalStateException("Not connected to $name")
        val flags = if (frame.fd) JavaCanFrame.FD_FLAG_FD_FRAME else JavaCanFrame.FD_NO_FLAGS
        val javaCanFrame = if (frame.extended) {
            JavaCanFrame.createExtended(frame.id, flags, frame.data)
        } else {
            JavaCanFrame.create(frame.id, flags, frame.data)
        }
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                channel.write(javaCanFrame)
            }
        }
    }

    override fun close() {
        closed.set(true)
        channel?.close()
        channel = null
    }

    private suspend fun readLoop(channel: RawCanChannel) {
        while (!closed.get() && currentCoroutineContext().isActive) {
            val frame = try {
                channel.read()
            } catch (e: Exception) {
                if (!closed.get()) {
                    logger.error("Error reading from $name, stopping", e)
                }
                break
            }
            val data = ByteArray(frame.dataLength)
            frame.getData(data, 0, data.size)
            _incomingFrames.emit(
                CanFrame(
                    id = frame.id,
                    data = data,
                    fd = frame.isFDFrame,
                    extended = frame.isExtended,
                )
            )
        }
    }
}
