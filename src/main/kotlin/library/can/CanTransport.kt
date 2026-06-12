package library.can

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Abstraction over a raw CAN bus connection (frame in/out), implemented by
 * SocketCAN, socketcand and loopback transports.
 */
public interface CanTransport : AutoCloseable {
    /**
     * Name of the transport (used in logs and thread names)
     */
    public val name: String

    /**
     * Whether this transport can send/receive CAN FD frames
     */
    public val supportsFd: Boolean

    /**
     * Hot stream of all frames received from the bus
     */
    public val incomingFrames: SharedFlow<CanFrame>

    /**
     * Connect/bind the transport and start reading frames into [incomingFrames] within [scope]
     */
    public suspend fun start(scope: CoroutineScope)

    public suspend fun sendFrame(frame: CanFrame)

    override fun close()
}

/**
 * In-memory CAN bus: every sent frame is echoed to all subscribers of [incomingFrames].
 * Useful for unit tests and simulations without any real CAN stack.
 */
public class LoopbackCanTransport(
    override val name: String = "loopback",
    override val supportsFd: Boolean = true,
    bufferCapacity: Int = 1024,
) : CanTransport {
    private val _incomingFrames = MutableSharedFlow<CanFrame>(extraBufferCapacity = bufferCapacity)

    override val incomingFrames: SharedFlow<CanFrame> = _incomingFrames

    override suspend fun start(scope: CoroutineScope) {
        // nothing to do
    }

    override suspend fun sendFrame(frame: CanFrame) {
        _incomingFrames.emit(frame)
    }

    override fun close() {
        // nothing to do
    }
}
