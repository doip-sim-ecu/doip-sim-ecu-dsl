package library

import io.ktor.utils.io.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.runBlocking
import kotlinx.io.EOFException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import kotlin.random.Random

class DoipTcpConnectionMessageHandlerTest {
    @Test
    fun `test tcp message handler`() {
        val tcpMessageHandler = DoipTcpConnectionMessageHandler(mock())
        val data = Random.nextBytes(10)
        val out = mock<OutputChannel>()
        runBlocking {
            tcpMessageHandler.handleTcpMessage(DoipTcpHeaderNegAck(0x11), out)
            tcpMessageHandler.handleTcpMessage(DoipTcpAliveCheckRequest(), out)
            tcpMessageHandler.handleTcpMessage(DoipTcpAliveCheckResponse(0x1234.toShort()), out)
            tcpMessageHandler.handleTcpMessage(DoipTcpDiagMessage(0x1234.toShort(), 0x4321.toShort(), data), out)
            tcpMessageHandler.handleTcpMessage(DoipTcpDiagMessageNegAck(0x11.toShort(), 0x22.toShort(), 0x11), out)
            tcpMessageHandler.handleTcpMessage(DoipTcpDiagMessagePosAck(0x11.toShort(), 0x22.toShort(), 0x11), out)
            tcpMessageHandler.handleTcpMessage(DoipTcpRoutingActivationResponse(0x11.toShort(), 0x22.toShort(), 0x11), out)
            tcpMessageHandler.handleTcpMessage(DoipTcpRoutingActivationRequest(0x11.toShort()), out)
        }
    }

    @Test
    fun `test receive`() {
        val parser = DoipTcpMessageParser(65535)
        val data = Random.nextBytes(10)
        runBlocking {
            parser.parseDoipTcpMessage(ByteReadChannel(DoipTcpHeaderNegAck(0x11).asByteArray))
            parser.parseDoipTcpMessage(ByteReadChannel(DoipTcpAliveCheckRequest().asByteArray))
            parser.parseDoipTcpMessage(ByteReadChannel(DoipTcpAliveCheckResponse(0x1234.toShort()).asByteArray))
            parser.parseDoipTcpMessage(ByteReadChannel(DoipTcpDiagMessage(0x1234.toShort(), 0x4321.toShort(), data).asByteArray))
            parser.parseDoipTcpMessage(ByteReadChannel(DoipTcpDiagMessageNegAck(0x11.toShort(), 0x22.toShort(), 0x11).asByteArray))
            parser.parseDoipTcpMessage(ByteReadChannel(DoipTcpDiagMessagePosAck(0x11.toShort(), 0x22.toShort(), 0x11).asByteArray))
            parser.parseDoipTcpMessage(ByteReadChannel(DoipTcpRoutingActivationResponse(0x11.toShort(), 0x22.toShort(), 0x11).asByteArray))
            parser.parseDoipTcpMessage(ByteReadChannel(DoipTcpRoutingActivationRequest(0x11.toShort()).asByteArray))
            assertThrows<EOFException> { parser.parseDoipTcpMessage(ByteReadChannel(byteArrayOf(0x0))) }
            assertThrows<UnknownPayloadType> { parser.parseDoipTcpMessage(ByteReadChannel(doipMessage(0xffff.toShort()))) }
        }
    }
}
