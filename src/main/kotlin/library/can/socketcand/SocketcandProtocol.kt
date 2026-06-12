package library.can.socketcand

import library.can.CanFrame

internal sealed class SocketcandMessage {
    object Hi : SocketcandMessage()
    object Ok : SocketcandMessage()
    class Error(val text: String) : SocketcandMessage()
    class Frame(val frame: CanFrame, val timestamp: Double) : SocketcandMessage()
    class Unknown(val raw: String) : SocketcandMessage()
}

/**
 * Incrementally extracts `< ... >` messages from a TCP byte stream
 * (messages may arrive split or coalesced across reads)
 */
internal class SocketcandTokenizer {
    private val buffer = StringBuilder()

    fun feed(chunk: String): List<String> {
        buffer.append(chunk)
        val messages = mutableListOf<String>()
        while (true) {
            val start = buffer.indexOf('<')
            if (start < 0) {
                buffer.setLength(0)
                break
            }
            val end = buffer.indexOf('>', start)
            if (end < 0) {
                if (start > 0) {
                    buffer.delete(0, start)
                }
                break
            }
            messages.add(buffer.substring(start, end + 1))
            buffer.delete(0, end + 1)
        }
        return messages
    }
}

/**
 * Parsing/serializing of the socketcand rawmode protocol messages
 * (https://github.com/linux-can/socketcand/blob/master/doc/protocol.md)
 */
internal object SocketcandProtocol {
    const val DEFAULT_PORT: Int = 29536

    fun parse(message: String): SocketcandMessage {
        val tokens = message.trim()
            .removePrefix("<")
            .removeSuffix(">")
            .split(' ', '\t', '\n', '\r')
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) {
            return SocketcandMessage.Unknown(message)
        }
        return when (tokens[0]) {
            "hi" -> SocketcandMessage.Hi
            "ok" -> SocketcandMessage.Ok
            "error" -> SocketcandMessage.Error(tokens.drop(1).joinToString(" "))
            "frame" -> parseFrame(tokens) ?: SocketcandMessage.Unknown(message)
            else -> SocketcandMessage.Unknown(message)
        }
    }

    private fun parseFrame(tokens: List<String>): SocketcandMessage.Frame? {
        // < frame can_id seconds.usecs data >
        if (tokens.size < 3) {
            return null
        }
        val idToken = tokens[1]
        val id = idToken.toIntOrNull(16) ?: return null
        val timestamp = tokens[2].toDoubleOrNull() ?: return null
        // the data bytes are contiguous hex, but some implementations separate them with spaces
        val dataHex = tokens.drop(3).joinToString("")
        if (dataHex.length % 2 != 0 || dataHex.length > 2 * CanFrame.MAX_DATA_LENGTH) {
            return null
        }
        val data = try {
            ByteArray(dataHex.length / 2) { dataHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            return null
        }
        val extended = idToken.length > 3 || id > CanFrame.MAX_STANDARD_ID
        if (id > CanFrame.MAX_EXTENDED_ID || id < 0) {
            return null
        }
        return SocketcandMessage.Frame(CanFrame(id = id, data = data, extended = extended), timestamp)
    }

    fun serializeOpen(busName: String): String =
        "< open $busName >"

    const val RAWMODE: String = "< rawmode >"

    fun serializeSend(frame: CanFrame): String {
        val id = frame.id.toString(16).padStart(if (frame.extended) 8 else 3, '0')
        val data = frame.data.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        return if (frame.data.isEmpty()) {
            "< send $id ${frame.data.size} >"
        } else {
            "< send $id ${frame.data.size} $data >"
        }
    }
}
