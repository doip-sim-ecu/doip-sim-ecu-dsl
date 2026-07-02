import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import library.can.CanFrame
import library.can.CanTransport
import library.can.LoopbackCanTransport
import library.can.isotp.IsoTpEndpoint
import library.can.isotp.IsoTpException
import library.can.isotp.IsoTpFraming
import library.can.isotp.IsoTpOptions
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class IsoTpEndpointTest {
    private class TestEndpoint(
        scope: CoroutineScope,
        transport: LoopbackCanTransport,
        rxId: Int,
        functionalRxId: Int?,
        txId: Int,
        options: IsoTpOptions = IsoTpOptions(),
    ) {
        val received = Channel<Pair<ByteArray, Boolean>>(Channel.UNLIMITED)
        val endpoint = IsoTpEndpoint(transport, rxId, functionalRxId, txId, options) { payload, functional ->
            received.trySend(payload to functional)
        }

        init {
            endpoint.start(scope)
        }

        suspend fun send(payload: ByteArray) = endpoint.send(payload)
    }

    @Test
    fun `single frame request and response`() = runTest {
        val bus = LoopbackCanTransport()
        val ecu = TestEndpoint(backgroundScope, bus, 0x712, 0x7DF, 0x7A2)
        val tester = TestEndpoint(backgroundScope, bus, 0x7A2, null, 0x712)

        tester.send(byteArrayOf(0x3E, 0x00))
        val (request, functional) = ecu.received.receive()
        assertThat(request.toList()).isEqualTo(listOf<Byte>(0x3E, 0x00))
        assertThat(functional).isFalse()

        ecu.send(byteArrayOf(0x7E, 0x00))
        val (response, _) = tester.received.receive()
        assertThat(response.toList()).isEqualTo(listOf<Byte>(0x7E, 0x00))
    }

    @Test
    fun `multi frame roundtrip with 4095 bytes`() = runTest {
        val bus = LoopbackCanTransport()
        val ecu = TestEndpoint(backgroundScope, bus, 0x712, null, 0x7A2)
        val tester = TestEndpoint(backgroundScope, bus, 0x7A2, null, 0x712)

        val payload = ByteArray(4095) { it.toByte() }
        tester.send(payload)
        val (request, _) = ecu.received.receive()
        assertThat(request.toList()).isEqualTo(payload.toList())

        val response = ByteArray(2048) { (it * 3).toByte() }
        ecu.send(response)
        val (received, _) = tester.received.receive()
        assertThat(received.toList()).isEqualTo(response.toList())
    }

    @Test
    fun `multi frame with 32 bit escape length`() = runTest {
        val bus = LoopbackCanTransport()
        val ecu = TestEndpoint(backgroundScope, bus, 0x712, null, 0x7A2)
        val tester = TestEndpoint(backgroundScope, bus, 0x7A2, null, 0x712)

        val payload = ByteArray(100_000) { it.toByte() }
        tester.send(payload)
        val (request, _) = ecu.received.receive()
        assertThat(request.contentEquals(payload)).isTrue()
    }

    @Test
    fun `block size and stmin are honored`() = runTest {
        val bus = LoopbackCanTransport()
        val ecu = TestEndpoint(backgroundScope, bus, 0x712, null, 0x7A2, IsoTpOptions(blockSize = 2, stMin = 10))
        val tester = TestEndpoint(backgroundScope, bus, 0x7A2, null, 0x712)

        val payload = ByteArray(100) { it.toByte() }
        val start = currentTime
        tester.send(payload)
        val (request, _) = ecu.received.receive()
        assertThat(request.toList()).isEqualTo(payload.toList())
        // 94 bytes after the first frame = 14 consecutive frames; STmin separates
        // them, so 13 gaps of 10 ms each (no separation before the first frame)
        assertThat(currentTime - start >= 130).isTrue()
    }

    @Test
    fun `fd framing with escape single frame`() = runTest {
        val bus = LoopbackCanTransport()
        val options = IsoTpOptions(fd = true, txDlc = 64)
        val ecu = TestEndpoint(backgroundScope, bus, 0x712, null, 0x7A2, options)
        val tester = TestEndpoint(backgroundScope, bus, 0x7A2, null, 0x712, options)

        // fits a single FD frame with escape SF_DL
        val small = ByteArray(50) { it.toByte() }
        tester.send(small)
        assertThat(ecu.received.receive().first.toList()).isEqualTo(small.toList())

        // segmented FD transfer
        val large = ByteArray(1000) { (it * 7).toByte() }
        tester.send(large)
        assertThat(ecu.received.receive().first.toList()).isEqualTo(large.toList())
    }

    @Test
    fun `timeout when no flow control arrives`() = runTest {
        val bus = LoopbackCanTransport()
        // nobody listens on 0x713, so no flow control will ever arrive
        val tester = TestEndpoint(backgroundScope, bus, 0x7A2, null, 0x713, IsoTpOptions(nBsTimeout = 1.seconds))

        assertFailure {
            tester.send(ByteArray(100))
        }.isInstanceOf(IsoTpException::class)
    }

    @Test
    fun `receiver overflow aborts transmission`() = runTest {
        val bus = LoopbackCanTransport()
        TestEndpoint(backgroundScope, bus, 0x712, null, 0x7A2, IsoTpOptions(maxMessageSize = 50))
        val tester = TestEndpoint(backgroundScope, bus, 0x7A2, null, 0x712)

        assertFailure {
            tester.send(ByteArray(100))
        }.isInstanceOf(IsoTpException::class)
    }

    @Test
    fun `functional single frame is dispatched as functional`() = runTest {
        val bus = LoopbackCanTransport()
        val ecu = TestEndpoint(backgroundScope, bus, 0x712, 0x7DF, 0x7A2)

        bus.sendFrame(CanFrame(0x7DF, IsoTpFraming.encodeSingleFrame(byteArrayOf(0x3E, 0x00), IsoTpOptions())))
        val (request, functional) = ecu.received.receive()
        assertThat(request.toList()).isEqualTo(listOf<Byte>(0x3E, 0x00))
        assertThat(functional).isTrue()
    }

    @Test
    fun `segmented message on functional id is ignored`() = runTest {
        val bus = LoopbackCanTransport()
        val ecu = TestEndpoint(backgroundScope, bus, 0x712, 0x7DF, 0x7A2)

        val options = IsoTpOptions()
        bus.sendFrame(CanFrame(0x7DF, IsoTpFraming.encodeFirstFrame(100, ByteArray(6), options)))
        testScheduler.advanceUntilIdle()
        assertThat(ecu.received.tryReceive().isSuccess).isFalse()

        // physical requests still work afterwards
        bus.sendFrame(CanFrame(0x712, IsoTpFraming.encodeSingleFrame(byteArrayOf(0x3E, 0x00), options)))
        val (request, functional) = ecu.received.receive()
        assertThat(request.toList()).isEqualTo(listOf<Byte>(0x3E, 0x00))
        assertThat(functional).isFalse()
    }

    @Test
    fun `concurrent transmissions are serialized`() = runTest {
        val bus = LoopbackCanTransport()
        val ecu = TestEndpoint(backgroundScope, bus, 0x712, null, 0x7A2)
        val tester = TestEndpoint(backgroundScope, bus, 0x7A2, null, 0x712)

        val first = ByteArray(100) { 0x11 }
        val second = ByteArray(100) { 0x22 }
        val sendJobs = listOf(
            backgroundScope.launch { ecu.send(first) },
            backgroundScope.launch { ecu.send(second) },
        )
        val received = listOf(tester.received.receive().first, tester.received.receive().first)
        sendJobs.forEach { it.join() }
        assertThat(received.map { it.toList() }.toSet()).isEqualTo(setOf(first.toList(), second.toList()))
    }

    @Test
    fun `failing transport send does not kill the frame processing loop`() = runTest {
        // a transport whose sends fail, like socketcand while disconnected/reconnecting
        val incoming = MutableSharedFlow<CanFrame>(extraBufferCapacity = 16)
        val transport = object : CanTransport {
            override val name = "failing"
            override val supportsFd = false
            override val incomingFrames = incoming
            override suspend fun start(scope: CoroutineScope) {}
            override suspend fun sendFrame(frame: CanFrame) {
                throw IllegalStateException("Not connected")
            }

            override fun close() {}
        }
        val received = Channel<ByteArray>(Channel.UNLIMITED)
        val endpoint = IsoTpEndpoint(transport, 0x712, null, 0x7A2, IsoTpOptions()) { payload, _ ->
            received.trySend(payload)
        }
        endpoint.start(backgroundScope)

        val options = IsoTpOptions()
        // the first frame provokes a flow control response, whose send fails
        incoming.emit(CanFrame(0x712, IsoTpFraming.encodeFirstFrame(20, ByteArray(6), options)))
        testScheduler.advanceUntilIdle()
        assertThat(received.tryReceive().isSuccess).isFalse()

        // the endpoint must still process frames afterwards
        incoming.emit(CanFrame(0x712, IsoTpFraming.encodeSingleFrame(byteArrayOf(0x3E, 0x00), options)))
        assertThat(received.receive().toList()).isEqualTo(listOf<Byte>(0x3E, 0x00))
    }

    @Test
    fun `sequence number mismatch aborts reception and allows new requests`() = runTest {
        val bus = LoopbackCanTransport()
        val options = IsoTpOptions()
        val ecu = TestEndpoint(backgroundScope, bus, 0x712, null, 0x7A2)

        bus.sendFrame(CanFrame(0x712, IsoTpFraming.encodeFirstFrame(20, ByteArray(6), options)))
        // wrong sequence number (expected 1)
        bus.sendFrame(CanFrame(0x712, IsoTpFraming.encodeConsecutiveFrame(3, ByteArray(7), options)))
        testScheduler.advanceUntilIdle()
        assertThat(ecu.received.tryReceive().isSuccess).isFalse()

        bus.sendFrame(CanFrame(0x712, IsoTpFraming.encodeSingleFrame(byteArrayOf(0x3E, 0x00), options)))
        assertThat(ecu.received.receive().first.toList()).isEqualTo(listOf<Byte>(0x3E, 0x00))
    }
}
