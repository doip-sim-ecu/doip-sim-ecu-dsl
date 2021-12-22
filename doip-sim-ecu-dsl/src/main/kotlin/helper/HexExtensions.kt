package helper
// These functions are certainly not optimized for performance. If this proves to be an issue, it shall be done

fun String.decodeHex(): ByteArray {
    val s = this.replace(" ", "")
    check(s.length % 2 == 0) { "String must have an even length" }
    return s.chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
}

fun String.toHexString(separator: String = " "): String =
    this.encodeToByteArray()
        .joinToString(separator = separator) { it.toUByte().toString(16).padStart(2, '0').uppercase() }

fun ByteArray.toHexString(separator: String = " ", limit: Int = Integer.MAX_VALUE): String {
    // Replace byte to string conversion with a performant version if necessary
    return this.take(limit)
        .joinToString(separator = separator) { it.toUByte().toString(16).padStart(2, '0').uppercase() }
// Maybe this, optimize single byte to string?
//    if (this.isEmpty() || limit == 0) {
//        return ""
//    } else if (this.size == 1) {
//        return this[0].toUByte().toString(16).padStart(2, '0').uppercase()
//    }
//
//    val sb = StringBuilder()
//    if (separator.isEmpty()) {
//        sb.append(this[0].toUByte().toString(16).padStart(2, '0').uppercase())
//        for (i in 1 until Integer.min(this.size, limit)) {
//            sb.append(this[i].toUByte().toString(16).padStart(2, '0').uppercase())
//        }
//    } else {
//        sb.append(this[0].toUByte().toString(16).padStart(2, '0').uppercase())
//        for (i in 1 until Integer.min(this.size, limit)) {
//            sb.append(separator)
//            sb.append(this[i].toUByte().toString(16).padStart(2, '0').uppercase())
//        }
//    }
//    return sb.toString()
}


