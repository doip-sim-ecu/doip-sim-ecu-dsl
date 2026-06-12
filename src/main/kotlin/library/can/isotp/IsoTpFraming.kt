package library.can.isotp

import library.can.CanDlc
import library.can.CanFrame

public sealed class IsoTpPdu

public class IsoTpSingleFrame(public val payload: ByteArray) : IsoTpPdu()

public class IsoTpFirstFrame(public val totalLength: Int, public val payload: ByteArray) : IsoTpPdu()

public class IsoTpConsecutiveFrame(public val sequenceNumber: Int, public val payload: ByteArray) : IsoTpPdu()

public class IsoTpFlowControlFrame(public val status: Int, public val blockSize: Int, public val stMin: Int) : IsoTpPdu() {
    public companion object {
        public const val CONTINUE_TO_SEND: Int = 0
        public const val WAIT: Int = 1
        public const val OVERFLOW: Int = 2
    }
}

/**
 * Stateless encoding/decoding of ISO 15765-2 protocol data units, including the
 * CAN FD escape encodings (SF_DL escape byte, 32-bit FF_DL)
 */
public object IsoTpFraming {
    internal const val PCI_SINGLE_FRAME = 0x0
    internal const val PCI_FIRST_FRAME = 0x1
    internal const val PCI_CONSECUTIVE_FRAME = 0x2
    internal const val PCI_FLOW_CONTROL = 0x3

    private const val MAX_FF_DL_12BIT = 0xFFF
    private const val DEFAULT_FD_PADDING = 0xCC.toByte()

    /**
     * Decodes the data bytes of a CAN frame into an ISO-TP PDU, or null when the
     * content isn't a valid ISO-TP frame
     */
    public fun decode(data: ByteArray): IsoTpPdu? {
        if (data.isEmpty()) {
            return null
        }
        val b0 = data[0].toInt() and 0xFF
        return when (b0 shr 4) {
            PCI_SINGLE_FRAME -> decodeSingleFrame(data, b0)
            PCI_FIRST_FRAME -> decodeFirstFrame(data, b0)
            PCI_CONSECUTIVE_FRAME -> IsoTpConsecutiveFrame(b0 and 0x0F, data.copyOfRange(1, data.size))
            PCI_FLOW_CONTROL ->
                if (data.size < 3 || (b0 and 0x0F) > IsoTpFlowControlFrame.OVERFLOW) {
                    null
                } else {
                    IsoTpFlowControlFrame(b0 and 0x0F, data[1].toInt() and 0xFF, data[2].toInt() and 0xFF)
                }
            else -> null
        }
    }

    private fun decodeSingleFrame(data: ByteArray, b0: Int): IsoTpSingleFrame? {
        if (b0 == 0x00) {
            // CAN FD escape: SF_DL in the second byte
            if (data.size < 2) {
                return null
            }
            val sfdl = data[1].toInt() and 0xFF
            if (sfdl == 0 || 2 + sfdl > data.size) {
                return null
            }
            return IsoTpSingleFrame(data.copyOfRange(2, 2 + sfdl))
        }
        val sfdl = b0 and 0x0F
        if (sfdl > 7 || 1 + sfdl > data.size) {
            return null
        }
        return IsoTpSingleFrame(data.copyOfRange(1, 1 + sfdl))
    }

    private fun decodeFirstFrame(data: ByteArray, b0: Int): IsoTpFirstFrame? {
        if (data.size < 2) {
            return null
        }
        val ffdl12 = ((b0 and 0x0F) shl 8) or (data[1].toInt() and 0xFF)
        if (ffdl12 != 0) {
            return IsoTpFirstFrame(ffdl12, data.copyOfRange(2, data.size))
        }
        // 32-bit FF_DL escape
        if (data.size < 7) {
            return null
        }
        val ffdl = ((data[2].toInt() and 0xFF) shl 24) or
                ((data[3].toInt() and 0xFF) shl 16) or
                ((data[4].toInt() and 0xFF) shl 8) or
                (data[5].toInt() and 0xFF)
        if (ffdl <= MAX_FF_DL_12BIT) {
            return null
        }
        return IsoTpFirstFrame(ffdl, data.copyOfRange(6, data.size))
    }

    /**
     * Maximum payload that fits into a single frame with the given options
     */
    public fun maxSingleFramePayload(options: IsoTpOptions): Int =
        if (options.fd && options.txDlc > 8) {
            options.txDlc - 2
        } else {
            minOf(options.txDlc, 8) - 1
        }

    /**
     * Number of payload bytes carried by the first frame
     */
    public fun firstFramePayloadSize(totalLength: Int, options: IsoTpOptions): Int =
        if (totalLength > MAX_FF_DL_12BIT) options.txDlc - 6 else options.txDlc - 2

    /**
     * Maximum number of payload bytes per consecutive frame
     */
    public fun consecutiveFramePayloadSize(options: IsoTpOptions): Int =
        options.txDlc - 1

    public fun encodeSingleFrame(payload: ByteArray, options: IsoTpOptions): ByteArray {
        require(payload.size <= maxSingleFramePayload(options)) {
            "Payload too large for a single frame (${payload.size} bytes)"
        }
        val frame = if (payload.size <= 7) {
            byteArrayOf((PCI_SINGLE_FRAME shl 4 or payload.size).toByte(), *payload)
        } else {
            byteArrayOf(0x00, payload.size.toByte(), *payload)
        }
        return padFrame(frame, options)
    }

    public fun encodeFirstFrame(totalLength: Int, payload: ByteArray, options: IsoTpOptions): ByteArray {
        require(payload.size == firstFramePayloadSize(totalLength, options))
        return if (totalLength <= MAX_FF_DL_12BIT) {
            byteArrayOf(
                (PCI_FIRST_FRAME shl 4 or (totalLength shr 8)).toByte(),
                (totalLength and 0xFF).toByte(),
                *payload
            )
        } else {
            byteArrayOf(
                (PCI_FIRST_FRAME shl 4).toByte(),
                0x00,
                (totalLength shr 24).toByte(),
                (totalLength shr 16 and 0xFF).toByte(),
                (totalLength shr 8 and 0xFF).toByte(),
                (totalLength and 0xFF).toByte(),
                *payload
            )
        }
    }

    public fun encodeConsecutiveFrame(sequenceNumber: Int, payload: ByteArray, options: IsoTpOptions): ByteArray {
        require(payload.size <= consecutiveFramePayloadSize(options))
        return padFrame(byteArrayOf((PCI_CONSECUTIVE_FRAME shl 4 or (sequenceNumber and 0x0F)).toByte(), *payload), options)
    }

    public fun encodeFlowControlFrame(status: Int, blockSize: Int, stMin: Int, options: IsoTpOptions): ByteArray =
        // flow control always fits a classic frame, padFrame caps padding at 8 bytes
        padFrame(
            byteArrayOf(
                (PCI_FLOW_CONTROL shl 4 or status).toByte(),
                blockSize.toByte(),
                stMin.toByte()
            ),
            options
        )

    private fun padFrame(frame: ByteArray, options: IsoTpOptions): ByteArray =
        if (options.fd && frame.size > 8) {
            // FD frames must be padded up to the next valid frame length
            CanDlc.pad(frame, CanDlc.nextValidLength(frame.size), options.paddingByte ?: DEFAULT_FD_PADDING)
        } else if (options.paddingByte != null) {
            CanDlc.pad(frame, minOf(options.txDlc, 8), options.paddingByte!!)
        } else {
            frame
        }

    /**
     * Delay to honor for an STmin byte value: 0x00-0x7F = milliseconds,
     * 0xF1-0xF9 = 100-900 µs (rounded up to 1 ms here); reserved values are
     * treated as the maximum of 127 ms as required by ISO 15765-2
     */
    public fun stMinToMillis(stMin: Int): Long =
        when (stMin) {
            in 0x00..0x7F -> stMin.toLong()
            in 0xF1..0xF9 -> 1L
            else -> 0x7FL
        }

    internal fun toCanFrame(id: Int, data: ByteArray, options: IsoTpOptions): CanFrame =
        CanFrame(id = id, data = data, fd = options.fd && data.size > 8)
}
