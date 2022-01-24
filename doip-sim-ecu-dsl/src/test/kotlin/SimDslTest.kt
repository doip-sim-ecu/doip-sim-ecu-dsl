import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SimDslTest {
    @Test
    fun `test dsl`() {
        gateway("GW") {
            request(byteArrayOf(0x10), "REQ1") { respond(byteArrayOf(0x50)) }
            request("10", "REQ2") { respond("50") }
            request("10 []", "REQ3") { ack() }
            request(Regex("10.*"), "REQ4") {
                nrc()
                addOrReplaceEcuTimer(name = "TEST", delay = 100.milliseconds) {
                    // do nothing
                }
            }

            ecu("ECU1") {
                request(byteArrayOf(0x10), "REQ1") { ack() }
                request("10", "REQ2") { ack() }
                request("10 []", "REQ3") { ack() }
                request(Regex("10.*"), "REQ4") { nrc(); addEcuInterceptor(duration = 1.seconds) { false } }
            }
        }
        assertThat(gateways.size).isEqualTo(1)
        assertThat(gateways[0].name).isEqualTo("GW")
        assertThat(gateways[0].requests.size).isEqualTo(4)

        assertThat(gateways[0].ecus.size).isEqualTo(1)
        assertThat(gateways[0].ecus[0].name).isEqualTo("ECU1")
        assertThat(gateways[0].ecus[0].requests.size).isEqualTo(4)

        assertThat(gatewayInstances.size).isEqualTo(0)
        gateways.clear()
    }

    @Test
    fun `check createfunc signatures`() {
        fun createEcuFunc(createEcuFunc: CreateEcuFunc) {
            assertThat(createEcuFunc).isNotNull()
        }

        fun createGwFunc(createGwFunc: CreateGatewayFunc) {
            assertThat(createGwFunc).isNotNull()
            createGwFunc("TEST") {
                createEcuFunc(::ecu)
            }
        }

        createGwFunc(::gateway)
    }

    @Test
    fun `test request matcher list extensions`() {
        val list = listOf(
            RequestMatcher("TEST1", byteArrayOf(11), null) { },
            RequestMatcher("TEST2", byteArrayOf(12), null) { },
            RequestMatcher("TEST3", byteArrayOf(13), null) { },
            RequestMatcher("TEST4", byteArrayOf(14), null) { },
            RequestMatcher("TEST5", byteArrayOf(15), null) { }
        )
        assertThat(list.size).isEqualTo(5)
        assertThat(list.findByName("TEST3")?.name).isEqualTo("TEST3")
        assertThat(list.findByName("TEST99")).isNull()
        val mutableList = list.toMutableList()
        assertThat(mutableList.removeByName("TEST3")?.name).isEqualTo("TEST3")
        assertThat(mutableList.removeByName("TEST1")?.name).isEqualTo("TEST1")
        assertThat(mutableList.removeByName("TEST98")).isNull()
        assertThat(mutableList.size).isEqualTo(3)
    }
}
