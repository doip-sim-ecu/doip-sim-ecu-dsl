import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import io.ktor.network.selector.ActorSelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readByte
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import library.can.CanFrame
import library.can.socketcand.SocketcandMessage
import library.can.socketcand.SocketcandProtocol
import library.can.socketcand.SocketcandTokenizer
import library.can.socketcand.SocketcandTransport
import org.junit.jupiter.api.Test

class SocketcandTest {
    @Test
    fun `tokenizer handles split and coalesced messages`() {
        val tokenizer = SocketcandTokenizer()
        assertThat(tokenizer.feed("< hi >< ok >")).isEqualTo(listOf("< hi >", "< ok >"))
        assertThat(tokenizer.feed("< fra")).isEmpty()
        assertThat(tokenizer.feed("me 123 23.4242")).isEmpty()
        assertThat(tokenizer.feed("42 1122 >< o")).isEqualTo(listOf("< frame 123 23.424242 1122 >"))
        assertThat(tokenizer.feed("k >")).isEqualTo(listOf("< ok >"))
        // garbage between messages is skipped
        assertThat(tokenizer.feed("garbage< ok >")).isEqualTo(listOf("< ok >"))
    }

    @Test
    fun `parse standard messages`() {
        assertThat(SocketcandProtocol.parse("< hi >")).isInstanceOf(SocketcandMessage.Hi::class)
        assertThat(SocketcandProtocol.parse("< ok >")).isInstanceOf(SocketcandMessage.Ok::class)
        val error = SocketcandProtocol.parse("< error could not open bus >")
        assertThat(error).isInstanceOf(SocketcandMessage.Error::class)
        assertThat((error as SocketcandMessage.Error).text).isEqualTo("could not open bus")
        assertThat(SocketcandProtocol.parse("< echo >")).isInstanceOf(SocketcandMessage.Unknown::class)
    }

    @Test
    fun `parse frame with contiguous hex data`() {
        val message = SocketcandProtocol.parse("< frame 123 23.424242 112233 >")
        val frame = (message as SocketcandMessage.Frame).frame
        assertThat(frame.id).isEqualTo(0x123)
        assertThat(frame.extended).isFalse()
        assertThat(frame.data.toList()).isEqualTo(listOf(0x11.toByte(), 0x22.toByte(), 0x33.toByte()))
        assertThat(message.timestamp).isEqualTo(23.424242)
    }

    @Test
    fun `parse frame with extended id and empty data`() {
        val message = SocketcandProtocol.parse("< frame 0000059A 42.0 >")
        val frame = (message as SocketcandMessage.Frame).frame
        assertThat(frame.id).isEqualTo(0x59A)
        assertThat(frame.extended).isTrue()
        assertThat(frame.data.size).isEqualTo(0)
    }

    @Test
    fun `serialize send`() {
        val frame = CanFrame(0x712, byteArrayOf(0x02, 0x3E, 0x00))
        assertThat(SocketcandProtocol.serializeSend(frame)).isEqualTo("< send 712 3 02 3e 00 >")
        val extended = CanFrame(0x59A, ByteArray(0), extended = true)
        assertThat(SocketcandProtocol.serializeSend(extended)).isEqualTo("< send 0000059a 0 >")
    }

    @Test
    fun `handshake and frame exchange against a fake server`() {
        val selector = ActorSelectorManager(Dispatchers.IO)
        val scope = CoroutineScope(Dispatchers.IO)
        runBlocking {
            val server = aSocket(selector).tcp().bind(InetSocketAddress("127.0.0.1", 0))
            val port = (server.localAddress as InetSocketAddress).port
            val receivedSends = Channel<String>(Channel.UNLIMITED)

            val serverJob = scope.launch {
                val client = server.accept()
                val readChannel = client.openReadChannel()
                val writeChannel = client.openWriteChannel(autoFlush = true)
                writeChannel.writeStringUtf8("< hi >")
                assertThat(readMessage(readChannel)).isEqualTo("< open can0 >")
                writeChannel.writeStringUtf8("< ok >")
                assertThat(readMessage(readChannel)).isEqualTo("< rawmode >")
                writeChannel.writeStringUtf8("< ok >")
                // first frame sent by the transport is acknowledged with a frame from the "bus"
                receivedSends.send(readMessage(readChannel))
                writeChannel.writeStringUtf8("< frame 7a2 0.000000 62f18601 >")
            }

            val transport = SocketcandTransport(host = "127.0.0.1", port = port, busName = "can0", reconnect = false)
            try {
                withTimeout(5000) {
                    transport.start(scope)
                    val incoming = Channel<CanFrame>(Channel.UNLIMITED)
                    // UNDISPATCHED so the subscription is registered before sendFrame triggers the reply
                    val collector = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        transport.incomingFrames.collect { incoming.trySend(it) }
                    }
                    transport.sendFrame(CanFrame(0x712, byteArrayOf(0x03, 0x22, 0xF1.toByte(), 0x86.toByte())))
                    assertThat(receivedSends.receive()).isEqualTo("< send 712 4 03 22 f1 86 >")

                    val frame = incoming.receive()
                    assertThat(frame.id).isEqualTo(0x7A2)
                    assertThat(frame.data.toList()).isEqualTo("62F18601".chunked(2).map { it.toInt(16).toByte() })
                    collector.cancel()
                    serverJob.join()
                }
            } finally {
                transport.close()
                server.close()
                scope.cancel()
                selector.close()
            }
        }
    }

    @Test
    fun `error during handshake is reported`() {
        val selector = ActorSelectorManager(Dispatchers.IO)
        val scope = CoroutineScope(Dispatchers.IO)
        runBlocking {
            val server = aSocket(selector).tcp().bind(InetSocketAddress("127.0.0.1", 0))
            val port = (server.localAddress as InetSocketAddress).port
            val serverJob = scope.launch {
                val client = server.accept()
                val readChannel = client.openReadChannel()
                val writeChannel = client.openWriteChannel(autoFlush = true)
                writeChannel.writeStringUtf8("< hi >")
                readMessage(readChannel)
                writeChannel.writeStringUtf8("< error no such bus >")
            }

            val transport = SocketcandTransport(host = "127.0.0.1", port = port, busName = "nosuchbus", reconnect = false)
            try {
                withTimeout(5000) {
                    val exception = runCatching { transport.start(scope) }.exceptionOrNull()
                    assertThat(exception is IllegalStateException).isTrue()
                    assertThat(exception!!.message!!.contains("no such bus")).isTrue()
                    serverJob.join()
                }
            } finally {
                transport.close()
                server.close()
                scope.cancel()
                selector.close()
            }
        }
    }

    private suspend fun readMessage(channel: ByteReadChannel): String {
        val sb = StringBuilder()
        while (true) {
            val c = channel.readByte().toInt().toChar()
            sb.append(c)
            if (c == '>') {
                return sb.toString().trim()
            }
        }
    }
}
