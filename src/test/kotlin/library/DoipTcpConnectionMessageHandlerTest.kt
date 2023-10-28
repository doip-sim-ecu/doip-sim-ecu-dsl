package library

import io.ktor.utils.io.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import kotlin.random.Random

class DoipTcpConnectionMessageHandlerTest {
    @Test
    fun `test tcp message handler`() {
        val tcpMessageHandler = DoipTcpConnectionMessageHandler()
        val data = Random.nextBytes(10)
        val out = mock<ByteWriteChannel>()
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
        val tcpMessageHandler = DoipTcpConnectionMessageHandler()
        val data = Random.nextBytes(10)
        runBlocking {
            tcpMessageHandler.receiveTcpData(ByteReadChannel(DoipTcpHeaderNegAck(0x11).asByteArray))
            tcpMessageHandler.receiveTcpData(ByteReadChannel(DoipTcpAliveCheckRequest().asByteArray))
            tcpMessageHandler.receiveTcpData(ByteReadChannel(DoipTcpAliveCheckResponse(0x1234.toShort()).asByteArray))
            tcpMessageHandler.receiveTcpData(ByteReadChannel(DoipTcpDiagMessage(0x1234.toShort(), 0x4321.toShort(), data).asByteArray))
            tcpMessageHandler.receiveTcpData(ByteReadChannel(DoipTcpDiagMessageNegAck(0x11.toShort(), 0x22.toShort(), 0x11).asByteArray))
            tcpMessageHandler.receiveTcpData(ByteReadChannel(DoipTcpDiagMessagePosAck(0x11.toShort(), 0x22.toShort(), 0x11).asByteArray))
            tcpMessageHandler.receiveTcpData(ByteReadChannel(DoipTcpRoutingActivationResponse(0x11.toShort(), 0x22.toShort(), 0x11).asByteArray))
            tcpMessageHandler.receiveTcpData(ByteReadChannel(DoipTcpRoutingActivationRequest(0x11.toShort()).asByteArray))
            assertThrows<ClosedReceiveChannelException> { tcpMessageHandler.receiveTcpData(ByteReadChannel(byteArrayOf(0x0))) }
            assertThrows<UnknownPayloadType> { tcpMessageHandler.receiveTcpData(ByteReadChannel(doipMessage(0xffff.toShort()))) }
        }
    }
}
