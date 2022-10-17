import assertk.assertThat
import assertk.assertions.isEqualTo
import client.DoipClient
import io.ktor.network.sockets.*
import library.*
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

@Disabled("only for manual testing")
class SimGatewayTest {
    @Test
    fun `test byte read channel`() {
        val doipEntity = SimGateway(
            GatewayData(
                name = "TEST"
            ).also {
                it.logicalAddress = 0x1010
                it.gid = GID(6)
                it.eid = EID(6)
                it.vin = "01234567890123456"
                it.localAddress = "127.0.0.1"
                it.functionalAddress = 0x3030
                it.requests.add(
                    RequestMatcher(
                        name= "ALL",
                        requestBytes = null,
                        requestRegex = Regex(".*"),
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
}