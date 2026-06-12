import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.hasSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import library.can.CanFrame
import library.can.LoopbackCanTransport
import library.can.isotp.IsoTpEndpoint
import library.can.isotp.IsoTpFraming
import library.can.isotp.IsoTpOptions
import library.decodeHex
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class CanBusBindingTest {
    private fun testBus(loop: LoopbackCanTransport, busReceiver: CanBusData.() -> Unit = {}): CanBusData =
        CanBusData("BUS1").apply {
            transport = loopback(loop)
            functionalRequestId = 0x7DF
            ecu("ECU1") {
                requestId = 0x712
                responseId = 0x7A2
                request("22 F1 86") { respond("62 F1 86 01 02 03") }
                request("3E 00") { ack() }
                request("10 01") {
                    pendingFor(50.milliseconds)
                    respond("50 01")
                }
                request("22 10 20") { respond(ByteArray(100) { it.toByte() }) }
            }
            busReceiver()
        }

    private fun withStartedBus(
        busData: CanBusData,
        loop: LoopbackCanTransport,
        block: suspend (tester: IsoTpEndpoint, received: Channel<ByteArray>) -> Unit,
    ) {
        val binding = CanBusBinding(busData)
        binding.start()
        binding.startNetworking()
        val scope = CoroutineScope(Dispatchers.IO)
        try {
            val received = Channel<ByteArray>(Channel.UNLIMITED)
            val tester = IsoTpEndpoint(loop, 0x7A2, null, 0x712, IsoTpOptions()) { payload, _ ->
                received.trySend(payload)
            }
            tester.start(scope)
            runBlocking {
                withTimeout(5000) {
                    block(tester, received)
                }
            }
        } finally {
            scope.cancel()
            binding.stop()
        }
    }

    @Test
    fun `request and response over the dsl defined bus`() {
        val loop = LoopbackCanTransport()
        withStartedBus(testBus(loop), loop) { tester, received ->
            tester.send("22 F1 86".decodeHex())
            assertThat(received.receive().toList()).isEqualTo("62 F1 86 01 02 03".decodeHex().toList())
        }
    }

    @Test
    fun `multi frame response is segmented and reassembled`() {
        val loop = LoopbackCanTransport()
        withStartedBus(testBus(loop), loop) { tester, received ->
            tester.send("22 10 20".decodeHex())
            assertThat(received.receive().toList()).isEqualTo(ByteArray(100) { it.toByte() }.toList())
        }
    }

    @Test
    fun `nrc is sent when no request matches`() {
        val loop = LoopbackCanTransport()
        withStartedBus(testBus(loop), loop) { tester, received ->
            tester.send("11 22".decodeHex())
            assertThat(received.receive().toList()).isEqualTo(listOf(0x7F.toByte(), 0x11.toByte(), 0x31.toByte()))
        }
    }

    @Test
    fun `pendingFor sends pending nrc before the final response`() {
        val loop = LoopbackCanTransport()
        withStartedBus(testBus(loop), loop) { tester, received ->
            tester.send("10 01".decodeHex())
            assertThat(received.receive().toList()).isEqualTo(listOf(0x7F.toByte(), 0x10.toByte(), 0x78.toByte()))
            assertThat(received.receive().toList()).isEqualTo(listOf(0x50.toByte(), 0x01.toByte()))
        }
    }

    @Test
    fun `functional request is answered on the physical response id`() {
        val loop = LoopbackCanTransport()
        withStartedBus(testBus(loop), loop) { _, received ->
            loop.sendFrame(CanFrame(0x7DF, IsoTpFraming.encodeSingleFrame("3E 00".decodeHex(), IsoTpOptions())))
            assertThat(received.receive().toList()).isEqualTo(listOf(0x7E.toByte(), 0x00.toByte()))
        }
    }

    @Test
    fun `find ecu by name`() {
        val binding = CanBusBinding(testBus(LoopbackCanTransport()))
        binding.start()
        assertThat(binding.findEcuByName("ecu1")).isNotNull()
        assertThat(binding.findEcuByName("unknown")).isNull()
    }

    @Test
    fun `duplicate request ids are rejected`() {
        val busData = CanBusData("BUS1").apply {
            transport = loopback()
            ecu("ECU1") { requestId = 0x712; responseId = 0x7A2 }
            ecu("ECU2") { requestId = 0x712; responseId = 0x7A3 }
        }
        val binding = CanBusBinding(busData)
        binding.start()
        assertFailure { binding.startNetworking() }.isInstanceOf(IllegalArgumentException::class)
    }

    @Test
    fun `missing addressing is rejected`() {
        val busData = CanBusData("BUS1").apply {
            transport = loopback()
            ecu("ECU1") { requestId = 0x712 }
        }
        val binding = CanBusBinding(busData)
        binding.start()
        assertFailure { binding.startNetworking() }.isInstanceOf(IllegalArgumentException::class)
    }

    @Test
    fun `fd requires a transport with fd support`() {
        val busData = CanBusData("BUS1").apply {
            transport = loopback(LoopbackCanTransport(supportsFd = false))
            fd = true
            txDlc = 64
            ecu("ECU1") { requestId = 0x712; responseId = 0x7A2 }
        }
        val binding = CanBusBinding(busData)
        binding.start()
        assertFailure { binding.startNetworking() }.isInstanceOf(IllegalArgumentException::class)
    }

    @Test
    fun `can buses are parsed by the network dsl`() {
        val networkingData = NetworkingData()
        networkingData.canBus("BUS1") {
            transport = loopback()
            isoTp { blockSize = 4 }
            ecu("ECU1") {
                requestId = 0x712
                responseId = 0x7A2
                isoTp { stMin = 5 }
                request("3E 00") { ack() }
            }
        }
        assertThat(networkingData.canBuses).hasSize(1)
        val bus = networkingData.canBuses.first()
        assertThat(bus.name).isEqualTo("BUS1")
        assertThat(bus.ecus).hasSize(1)
        assertThat(bus.ecus.first().requestId).isEqualTo(0x712)
        assertThat(bus.ecus.first().requests.size).isEqualTo(1)
    }
}
