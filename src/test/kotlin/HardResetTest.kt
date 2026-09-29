import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import client.DoipClient
import io.ktor.network.sockets.*
import io.ktor.utils.io.ClosedWriteChannelException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import library.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.EOFException
import java.lang.Thread.sleep
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for the scoped hard reset of a DoIP entity on a [TcpNetworkBinding]:
 * a reset may only affect the entity that triggered it, not other entities that
 * share the same binding (ip address/port).
 */
class HardResetTest {
    /**
     * Sending on a connection closed by the sim fails - depending on whether the
     * peer's close already arrived this surfaces as a closed read or write
     * channel, or an EOF while parsing the (missing) response.
     */
    private fun assertConnectionClosed(block: () -> Unit) {
        val e = assertThrows<Exception>(executable = block)
        assertThat(
            e is ClosedReceiveChannelException || e is EOFException || e is ClosedWriteChannelException
        ).isEqualTo(true)
    }

    private fun networkingData(localPort: Int): NetworkingData =
        NetworkingData().also {
            it.networkInterface = "0.0.0.0"
            it.localPort = localPort
            it.broadcastEnable = false
            it.bindOnAnyForUdpAdditional = false
        }

    private fun entity(name: String, logicalAddress: Short, receiver: DoipEntityData.() -> Unit): SimDoipEntity =
        SimDoipEntity(
            DoipEntityData(name = name).also {
                it.logicalAddress = logicalAddress
                it.gid = GID(6)
                it.eid = EID(6)
                it.vin = "01234567890123456"
                it.functionalAddress = 0x3030
                receiver.invoke(it)
            }
        )

    @OptIn(ExperimentalDoipDslApi::class)
    private fun DoipEntityData.addHardResetRequest() {
        requests.add(
            RequestMatcher(
                name = "Hard_Reset",
                requestBytes = byteArrayOf(0x11, 0x01),
                onlyStartsWith = true,
                responseHandler = {
                    this.hardResetEntityFor(2.seconds)
                    this.ack()
                }
            )
        )
    }

    private fun DoipEntityData.addAckRequest(name: String, request: ByteArray) {
        requests.add(
            RequestMatcher(
                name = name,
                requestBytes = request,
                onlyStartsWith = true,
                responseHandler = { this.ack() }
            )
        )
    }

    @Test
    fun `hard reset on shared binding only affects the resetting entity`() {
        val entityA = entity("ENTITY_A", 0x1010) {
            addHardResetRequest()
            addAckRequest("RDBI_A", byteArrayOf(0x22, 0xF1.toByte(), 0x98.toByte()))
            ecu("ECU_A") {
                logicalAddress = 0x2010
                functionalAddress = 0x3030
                requests.add(
                    RequestMatcher(
                        name = "RDBI_ECU_A",
                        requestBytes = byteArrayOf(0x22, 0xF1.toByte(), 0x91.toByte()),
                        onlyStartsWith = true,
                        responseHandler = { this.ack() }
                    )
                )
            }
        }
        val entityB = entity("ENTITY_B", 0x1020) {
            addAckRequest("RDBI_B", byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte()))
        }

        // With a single available ip address both entities share one TcpNetworkBinding
        entityA.start()
        entityB.start()
        val networkManager = NetworkManager(networkingData(13401), listOf(entityA, entityB))
        networkManager.start()
        sleep(500)

        val c = DoipClient()
        // distinct tester addresses: the binding denies a second routing
        // activation for an address that already has an active connection
        val connA = c.connectToEntity(InetSocketAddress("localhost", 13401), testerAddress = 0xe80)
        val connB = c.connectToEntity(InetSocketAddress("localhost", 13401), testerAddress = 0xe81)
        val connEcuA = c.connectToEntity(InetSocketAddress("localhost", 13401), testerAddress = 0xe82)

        connB.sendDiagnosticMessage(0x1020, byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte()), true) {
            assertThat(it[0]).isEqualTo(0x62)
        }
        connEcuA.sendDiagnosticMessage(0x2010, byteArrayOf(0x22, 0xF1.toByte(), 0x91.toByte()), true) {
            assertThat(it[0]).isEqualTo(0x62)
        }

        connA.sendDiagnosticMessage(0x1010, byteArrayOf(0x11, 0x01), true) {
            assertThat(it[0]).isEqualTo(0x51)
        }
        sleep(300)

        // the triggering connection and connections that addressed A or one of
        // its ecus are closed by the reset
        assertConnectionClosed {
            connA.sendDiagnosticMessage(0x1010, byteArrayOf(0x22, 0xF1.toByte(), 0x98.toByte()), true) {}
        }
        assertConnectionClosed {
            connEcuA.sendDiagnosticMessage(0x2010, byteArrayOf(0x22, 0xF1.toByte(), 0x91.toByte()), true) {}
        }

        // connB only ever talked to B - it stays usable while A resets
        connB.sendDiagnosticMessage(0x1020, byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte()), true) {
            assertThat(it[0]).isEqualTo(0x62)
        }

        // the server socket stays open: new connections are accepted during the
        // reset, but requests to A only get the DoIP ack, no UDS response
        val c2 = DoipClient()
        val connNew = c2.connectToEntity(InetSocketAddress("localhost", 13401), testerAddress = 0xe83, timeout = 500.milliseconds)
        val e = assertThrows<RuntimeException> {
            connNew.sendDiagnosticMessage(
                0x1010,
                byteArrayOf(0x22, 0xF1.toByte(), 0x98.toByte()),
                true,
                waitTimeout = 500.milliseconds
            ) {}
        }
        assertThat(e.message ?: "").contains("No response")

        // after the reset duration entity A answers again
        sleep(2600)
        val c3 = DoipClient()
        val connAfter = c3.connectToEntity(InetSocketAddress("localhost", 13401), testerAddress = 0xe84, timeout = 500.milliseconds)
        connAfter.sendDiagnosticMessage(0x1010, byteArrayOf(0x22, 0xF1.toByte(), 0x98.toByte()), true) {
            assertThat(it[0]).isEqualTo(0x62)
        }
    }

    @Test
    fun `hard reset on exclusive binding closes server sockets and restarts`() {
        val entity = entity("ENTITY", 0x1030) {
            addHardResetRequest()
            addAckRequest("RDBI", byteArrayOf(0x22, 0xF1.toByte(), 0x98.toByte()))
        }

        entity.start()
        val networkManager = NetworkManager(networkingData(13402), listOf(entity))
        networkManager.start()
        sleep(500)

        val c = DoipClient()
        val con = c.connectToEntity(InetSocketAddress("localhost", 13402), testerAddress = 0xe80)
        con.sendDiagnosticMessage(0x1030, byteArrayOf(0x11, 0x01), true) {
            assertThat(it[0]).isEqualTo(0x51)
        }
        sleep(300)

        assertConnectionClosed {
            con.sendDiagnosticMessage(0x1030, byteArrayOf(0x22, 0xF1.toByte(), 0x98.toByte()), true) {}
        }

        // new connections are refused while the server sockets are down
        assertThrows<java.net.ConnectException> {
            val c2 = DoipClient()
            c2.connectToEntity(InetSocketAddress("localhost", 13402), testerAddress = 0xe81, timeout = 200.milliseconds)
        }

        // after the reset duration the entity is reachable again
        sleep(2600)
        val c3 = DoipClient()
        val con3 = c3.connectToEntity(InetSocketAddress("localhost", 13402), testerAddress = 0xe82, timeout = 500.milliseconds)
        con3.sendDiagnosticMessage(0x1030, byteArrayOf(0x22, 0xF1.toByte(), 0x98.toByte()), true) {
            assertThat(it[0]).isEqualTo(0x62)
        }
    }
}
