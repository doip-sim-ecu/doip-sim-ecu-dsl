import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isSameInstanceAs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import library.OutputChannel
import library.UdsMessage
import library.can.LoopbackCanTransport
import library.can.isotp.IsoTpEndpoint
import library.can.isotp.IsoTpOptions
import library.decodeHex
import org.junit.jupiter.api.Test

class MultiTransportTest {
    private fun multiTransportNetwork(loop: LoopbackCanTransport): NetworkingData {
        val networkingData = NetworkingData()
        networkingData.multiTransport(
            networkingData.gateway("GW") {
                logicalAddress = 0x1010
            },
            networkingData.canBus("BUS1") {
                transport = loopback(loop)
            },
        ) {
            ecu("ECU1") {
                logicalAddress = 0x1011
                requestId = 0x712
                responseId = 0x7A2
                request("22 01") {
                    var counter by ecu.storedProperty { 0 }
                    counter += 1
                    respond(byteArrayOf(0x62, 0x01, counter.toByte()))
                }
            }
        }
        return networkingData
    }

    @Test
    fun `multiTransport registers the same ecu data on both transports`() {
        val networkingData = multiTransportNetwork(LoopbackCanTransport())
        val gatewayEcu = networkingData.doipEntities.first().ecus.single()
        val canEcu = networkingData.canBuses.first().ecus.single()
        assertThat(canEcu as EcuData).isSameInstanceAs(gatewayEcu)
        assertThat(gatewayEcu.logicalAddress).isEqualTo(0x1011.toShort())
        assertThat(canEcu.requestId).isEqualTo(0x712)
    }

    @Test
    fun `dual homed ecu is a single shared instance`() {
        val networking = SimDoipNetworking(multiTransportNetwork(LoopbackCanTransport()))
        networking.start()

        val viaDoip = networking.findEcuByName("ECU1")
        val viaCan = networking.canBusBindings.first().findEcuByName("ECU1")
        assertThat(viaDoip).isNotNull()
        assertThat(viaCan).isNotNull()
        assertThat(viaCan!!).isSameInstanceAs(viaDoip!!)
    }

    @Test
    fun `state is shared between can and doip requests`() {
        val loop = LoopbackCanTransport()
        val networking = SimDoipNetworking(multiTransportNetwork(loop))
        networking.start()
        val binding = networking.canBusBindings.first()
        binding.startNetworking()

        val scope = CoroutineScope(Dispatchers.IO)
        try {
            runBlocking {
                withTimeout(5000) {
                    // first request via CAN
                    val received = Channel<ByteArray>(Channel.UNLIMITED)
                    val tester = IsoTpEndpoint(loop, 0x7A2, null, 0x712, IsoTpOptions()) { payload, _ ->
                        received.trySend(payload)
                    }
                    tester.start(scope)
                    tester.send("22 01".decodeHex())
                    assertThat(received.receive().toList()).isEqualTo("62 01 01".decodeHex().toList())

                    // second request via the DoIP path of the same (shared) instance
                    val recorded = Channel<ByteArray>(Channel.UNLIMITED)
                    val recordingOutput = object : OutputChannel {
                        override suspend fun writeFully(data: ByteArray) {
                            recorded.trySend(data)
                        }

                        override suspend fun flush() {}
                    }
                    val simEcu = networking.findEcuByName("ECU1")!!
                    simEcu.onIncomingUdsMessage(
                        UdsMessage(
                            sourceAddress = 0x0E00,
                            targetAddress = 0x1011,
                            targetAddressType = UdsMessage.PHYSICAL,
                            targetAddressPhysical = 0x1011,
                            message = "22 01".decodeHex(),
                            output = recordingOutput,
                        )
                    )
                    // counter was incremented by the CAN request before - DoIP-framed response ends with 62 01 02
                    val doipResponse = recorded.receive()
                    assertThat(doipResponse.takeLast(3)).isEqualTo("62 01 02".decodeHex().toList())
                }
            }
        } finally {
            scope.cancel()
            binding.stop()
        }
    }

    @Test
    fun `ecu shared between two can buses is a single instance`() {
        val networkingData = NetworkingData()
        networkingData.multiTransport(
            networkingData.canBus("BUS1") { transport = loopback() },
            networkingData.canBus("BUS2") { transport = loopback() },
        ) {
            ecu("ECU1") {
                requestId = 0x712
                responseId = 0x7A2
            }
        }
        val networking = SimDoipNetworking(networkingData)
        networking.start()
        val viaBus1 = networking.canBusBindings[0].findEcuByName("ECU1")
        val viaBus2 = networking.canBusBindings[1].findEcuByName("ECU1")
        assertThat(viaBus1).isNotNull()
        assertThat(viaBus2!!).isSameInstanceAs(viaBus1!!)
    }

    @Test
    fun `multiTransport rejects invalid target combinations`() {
        val networkingData = NetworkingData()
        val gw1 = networkingData.gateway("GW1") { logicalAddress = 0x1010 }
        val gw2 = networkingData.gateway("GW2") { logicalAddress = 0x2010 }
        assertFailure {
            networkingData.multiTransport(gw1, gw2) {}
        }.isInstanceOf(IllegalArgumentException::class)

        assertFailure {
            networkingData.multiTransport() {}
        }.isInstanceOf(IllegalArgumentException::class)

        assertFailure {
            networkingData.multiTransport(gw1, gw1) {}
        }.isInstanceOf(IllegalArgumentException::class)
    }
}
