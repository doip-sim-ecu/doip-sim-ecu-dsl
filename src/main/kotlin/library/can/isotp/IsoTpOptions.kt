package library.can.isotp

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * ISO-TP (ISO 15765-2) parameters for one endpoint.
 *
 * Note: this implementation deliberately omits the N_As/N_Ar timeouts (transmit
 * confirmation isn't observable above the raw frame transports) and never sends
 * flow control WAIT frames itself (a simulator can always allocate the buffer).
 */
public class IsoTpOptions(
    /**
     * Block size announced in flow control frames we send while receiving
     * (0 = all consecutive frames without further flow control)
     */
    public var blockSize: Int = 0,
    /**
     * STmin announced in flow control frames we send while receiving.
     * Raw byte value: 0x00-0x7F = milliseconds, 0xF1-0xF9 = 100-900 µs
     * (sub-millisecond values are rounded up to 1 ms when honoring them)
     */
    public var stMin: Int = 0,
    /**
     * Maximum payload length of transmitted frames (tx_dl): 8 for classic CAN,
     * up to 64 (12, 16, 20, 24, 32, 48, 64) with CAN FD
     */
    public var txDlc: Int = 8,
    /**
     * Byte used to pad transmitted frames (null = no padding; CAN FD frames are
     * always padded up to the next valid frame length)
     */
    public var paddingByte: Byte? = 0xCC.toByte(),
    /**
     * Use CAN FD framing (escape DLC, payloads > 8 bytes per frame)
     */
    public var fd: Boolean = false,
    /**
     * Timeout waiting for a flow control frame after sending a first frame / block (N_Bs)
     */
    public var nBsTimeout: Duration = 1.seconds,
    /**
     * Timeout waiting for the next consecutive frame while receiving (N_Cr)
     */
    public var nCrTimeout: Duration = 1.seconds,
    /**
     * Maximum number of flow control WAIT frames accepted before aborting a transmission (WFTmax)
     */
    public var maxWaitFrames: Int = 8,
    /**
     * Maximum size of a single received message before answering with flow control OVERFLOW
     */
    public var maxMessageSize: Int = 8 * 1024 * 1024,
) {
    public fun copy(): IsoTpOptions =
        IsoTpOptions(
            blockSize = blockSize,
            stMin = stMin,
            txDlc = txDlc,
            paddingByte = paddingByte,
            fd = fd,
            nBsTimeout = nBsTimeout,
            nCrTimeout = nCrTimeout,
            maxWaitFrames = maxWaitFrames,
            maxMessageSize = maxMessageSize,
        )
}
