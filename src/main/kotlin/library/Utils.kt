package library

import java.nio.ByteBuffer

/**
 * Convert an int to a big-endian bytearray
 */
public fun Int.toByteArray(): ByteArray =
    byteArrayOf((this and 0xFF000000.toInt() shr 24).toByte(), (this and 0xFF0000 shr 16).toByte(), (this and 0xFF00 shr 8).toByte(), (this and 0xFF).toByte())

/**
 * Convert a short to a big-endian bytearray
 */
public fun Short.toByteArray(): ByteArray =
    byteArrayOf((this.toInt() and 0xFF00 shr 8).toByte(), this.toByte())

public fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (prefix.size > this.size) {
        return false
    }
    for (i in prefix.indices) {
        if (prefix[i] != this[i]) {
            return false
        }
    }
    return true
}

/**
 * Convenience function to create a doip message
 */
public fun doipMessage(payloadType: Short, vararg data: Byte): ByteArray {
    val bb = ByteBuffer.allocate(8 + data.size)
    if (payloadType == TYPE_UDP_VIR || payloadType == TYPE_UDP_VIR_EID || payloadType == TYPE_UDP_VIR_VIN) {
        /// Protocol version for vehicle identification request messages
        bb.put(0xFF.toByte())
        bb.put(0x00)
    } else {
        // protocol version ISO 13400-2:2012
        bb.put(0x02)
        bb.put(0xFD.toByte())
    }
    bb.putShort(payloadType)
    bb.putInt(data.size)
    bb.put(data)
    return bb.array()
}

