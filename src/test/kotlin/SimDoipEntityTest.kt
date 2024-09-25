import assertk.assertThat
import assertk.assertions.isEqualTo
import client.DoipClient
import io.ktor.network.sockets.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import library.*
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.Thread.sleep
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Disabled("only for manual testing")
class SimDoipEntityTest {
    @Test
    fun `test byte read channel`() {
        val doipEntity = SimDoipEntity(
            DoipEntityData(
                name = "TEST"
            ).also {
                it.logicalAddress = 0x1010
                it.gid = GID(6)
                it.eid = EID(6)
                it.vin = "01234567890123456"
                it.functionalAddress = 0x3030
                it.requests.add(
                    RequestMatcher(
                        name= "ALL",
                        requestBytes = byteArrayOf(),
                        onlyStartsWith = true,
                        responseHandler = {
                            this.ack()
                        }
                    )
                )
            }
        )
        val element = EcuConfig("TEST", 0x1010, 0x2030)
        doipEntity.config.ecuConfigList.add(element)

        doipEntity.start()

        val c = DoipClient()
        val con = c.connectToEntity(InetSocketAddress("localhost", 13400))
        val data = ByteArray(10000000)
        con.sendDiagnosticMessage(0x1010, data, true) {
            assertThat(it[0]).isEqualTo(0x40)
        }
        con.sendDiagnosticMessage(0x1010, data, true) {
            assertThat(it[0]).isEqualTo(0x40)
        }
        con.sendDiagnosticMessage(0x1010, data, true) {
            assertThat(it[0]).isEqualTo(0x40)
        }
        con.sendDiagnosticMessage(0x1010, data, true) {
            assertThat(it[0]).isEqualTo(0x40)
        }
    }

    @OptIn(ExperimentalDoipDslApi::class)
    @Test
    fun `test hard reset`() {
        val doipEntity = SimDoipEntity(
            DoipEntityData(
                name = "TEST"
            ).also {
                it.logicalAddress = 0x1010
                it.gid = GID(6)
                it.eid = EID(6)
                it.vin = "01234567890123456"
                it.functionalAddress = 0x3030
                it.requests.add(
                    RequestMatcher(
                        name= "Hard_Reset",
                        requestBytes = byteArrayOf(0x11, 0x01),
                        onlyStartsWith = true,
                        responseHandler = {
                            this.hardResetEntityFor(2.seconds)
                            this.ack()
                        }
                    ))
            }
        )
        val element = EcuConfig("TEST", 0x1010, 0x2030)
        doipEntity.config.ecuConfigList.add(element)

        doipEntity.start()

        val c = DoipClient()
        val con = c.connectToEntity(InetSocketAddress("localhost", 13400))
        con.sendDiagnosticMessage(0x1010, byteArrayOf(0x11, 0x01), true) {
            assertThat(it[0]).isEqualTo(0x51)
        }
        assertThrows<ClosedReceiveChannelException> {
            con.sendDiagnosticMessage(0x1010, byteArrayOf(0x11, 0x01), true) {
            }
        }
        sleep(100)
        assertThrows<java.net.ConnectException> {
            val c2 = DoipClient()
            c2.connectToEntity(InetSocketAddress("localhost", 13400), timeout = 200.milliseconds)
        }
        sleep(3000)
        val c3 = DoipClient()
        c3.connectToEntity(InetSocketAddress("localhost", 13400), timeout = 200.milliseconds)
    }
}
