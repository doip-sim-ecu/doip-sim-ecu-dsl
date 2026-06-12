import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import library.can.isotp.IsoTpEndpoint
import library.can.isotp.IsoTpOptions
import library.can.socketcan.SocketCanTransport
import library.decodeHex
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * Requires a Linux host with a vcan interface (excluded by default, enable with
 * `gradlew test -PcanIntegrationTests`):
 * ```
 * sudo modprobe vcan
 * sudo ip link add dev vcan0 type vcan
 * sudo ip link set up vcan0
 * ```
 * Can be cross-checked with can-utils, e.g. `candump vcan0` or
 * `isotpsend -s 712 -d 7a2 vcan0`.
 */
@Tag("linux-can")
@EnabledOnOs(OS.LINUX)
class SocketCanIntegrationTest {
    @Test
    fun `request and response over vcan`() {
        val busData = CanBusData("VCAN").apply {
            transport = socketCan("vcan0")
            ecu("ECU1") {
                requestId = 0x712
                responseId = 0x7A2
                request("22 F1 86") { respond("62 F1 86 01 02 03") }
                request("22 10 20") { respond(ByteArray(500) { it.toByte() }) }
            }
        }
        val binding = CanBusBinding(busData)
        binding.start()
        binding.startNetworking()
        val scope = CoroutineScope(Dispatchers.IO)
        val testerTransport = SocketCanTransport("vcan0")
        try {
            runBlocking {
                withTimeout(10_000) {
                    testerTransport.start(scope)
                    val received = Channel<ByteArray>(Channel.UNLIMITED)
                    val tester = IsoTpEndpoint(testerTransport, 0x7A2, null, 0x712, IsoTpOptions()) { payload, _ ->
                        received.trySend(payload)
                    }
                    tester.start(scope)

                    tester.send("22 F1 86".decodeHex())
                    assertThat(received.receive().toList()).isEqualTo("62 F1 86 01 02 03".decodeHex().toList())

                    // segmented response with flow control
                    tester.send("22 10 20".decodeHex())
                    assertThat(received.receive().toList()).isEqualTo(ByteArray(500) { it.toByte() }.toList())
                }
            }
        } finally {
            testerTransport.close()
            scope.cancel()
            binding.stop()
        }
    }
}
