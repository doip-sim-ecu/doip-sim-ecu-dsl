package library.can

import library.toHexString

/**
 * A single CAN frame as seen on the bus.
 */
public class CanFrame(
    /**
     * Raw CAN identifier (11-bit standard, or 29-bit when [extended] is set)
     */
    public val id: Int,
    public val data: ByteArray,
    /**
     * CAN FD frame (payload up to 64 bytes)
     */
    public val fd: Boolean = false,
    /**
     * 29-bit extended identifier
     */
    public val extended: Boolean = false,
) {
    init {
        require(data.size <= if (fd) MAX_FD_DATA_LENGTH else MAX_DATA_LENGTH) {
            "CAN frame payload is too large (${data.size} bytes, fd=$fd)"
        }
        require(id <= if (extended) MAX_EXTENDED_ID else MAX_STANDARD_ID) {
            "CAN id ${id.toString(16)} out of range (extended=$extended)"
        }
    }

    override fun toString(): String =
        "CanFrame(id=0x${id.toString(16)}, data=${data.toHexString(limit = 16)}${if (fd) ", fd" else ""}${if (extended) ", eff" else ""})"

    public companion object {
        public const val MAX_DATA_LENGTH: Int = 8
        public const val MAX_FD_DATA_LENGTH: Int = 64
        public const val MAX_STANDARD_ID: Int = 0x7FF
        public const val MAX_EXTENDED_ID: Int = 0x1FFFFFFF
    }
}

/**
 * Valid CAN(-FD) frame payload lengths and padding
 */
public object CanDlc {
    private val validLengths = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 12, 16, 20, 24, 32, 48, 64)

    public fun isValidLength(length: Int): Boolean =
        validLengths.contains(length)

    /**
     * Returns the smallest valid CAN(-FD) frame length that can hold [length] bytes
     */
    public fun nextValidLength(length: Int): Int =
        validLengths.firstOrNull { it >= length }
            ?: throw IllegalArgumentException("No valid CAN frame length for $length bytes")

    /**
     * Pads [data] with [paddingByte] up to [length] (returns [data] unchanged if it's already long enough)
     */
    public fun pad(data: ByteArray, length: Int, paddingByte: Byte): ByteArray =
        if (data.size >= length) {
            data
        } else {
            data.copyOf(length).also { it.fill(paddingByte, data.size, length) }
        }
}
