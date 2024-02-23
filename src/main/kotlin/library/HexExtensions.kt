package library

import java.nio.Buffer
import java.nio.ByteBuffer
import kotlin.math.min

public fun String.decodeHex(): ByteArray {
    // Maximum size for the resulting bytearray is string-length / 2
    val bb = ByteBuffer.allocate(this.length / 2)

    var nibble: Int
    var b = 0
    var counter = 0
    for (i in this.indices) {
        val c = this[i]
        nibble = when (c) {
            in '0'..'9' -> c - '0'
            in 'A'..'F' -> c - 'A' + 10
            in 'a'..'f' -> c - 'a' + 10
            else -> continue // ignore non-hex chars
        }

        if (counter++ % 2 == 0) {
            // msb
            b = nibble shl 4
        } else {
            b = b or nibble
            bb.put(b.toByte())
        }
    }

    if (counter % 2 != 0) {
        throw IllegalArgumentException("Length of hex chars isn't even in string '$this'")
    }

    // cast to buffer, so it runs on jdk1.8 and jdk 11+
    // cause: jdk 11's bytebuffer implements its own flip method with a different return type
    (bb as Buffer).flip()
    val buf = ByteArray((bb as Buffer).limit())
    bb.get(buf)
    return buf
// Simpler yet worse performing version
//    val s = this.replace(" ", "")
//    check(s.length % 2 == 0) { "String must have an even length" }
//    return s.chunked(2)
//        .map { it.toInt(16).toByte() }
//        .toByteArray()
}

public fun String.encodedAsHexString(separator: String = " "): String =
    this.encodeToByteArray().toHexString(separator)

private val nibbleToHexUppercase = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F')
private val nibbleToHexLowercase = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f')

public fun ByteArray.toHexString(
    separator: String = " ",
    limit: Int = Integer.MAX_VALUE,
    limitExceededSuffix: String = "...",
    useLowerCase: Boolean = false,
    limitExceededByteCount: Boolean = false,
): String {
    val len = min(limit, this.size)
    if (len == 0) {
        if (this.size > limit) {
            return limitExceededSuffix
        }
        return ""
    }
    val nibbleToHex = if (useLowerCase) nibbleToHexLowercase else nibbleToHexUppercase
    val sb = StringBuilder((len * 2) + ((len - 1) * separator.length))
    for (i in 0 until len) {
        if (separator.isNotEmpty() && i > 0) {
            sb.append(separator)
        }
        sb.append(nibbleToHex[(this[i].toInt() and 0xF0) shr 4])
        sb.append(nibbleToHex[this[i].toInt() and 0x0F])
    }
    if (this.size > limit) {
        sb.append(limitExceededSuffix)
        if (limitExceededByteCount) {
            sb.append(" (${this.size} bytes total)")
        }
    }
    return sb.toString()
}
