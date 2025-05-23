import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import library.OutputChannel
import library.UdsMessage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SimDslTest {
    @AfterEach
    fun tearDown() {
        networks.clear()
    }

    @Test
    fun `test dsl`() {
        network {
            gateway("GW") {
                request(byteArrayOf(0x10), "REQ1") { respond(byteArrayOf(0x50)) }
                request("10", "REQ2", duplicateStrategy = DuplicateStrategy.APPEND) { respond("50") }
                request("10 []", "REQ3") { ack() }
                request("10.*", "REQ4", duplicateStrategy = DuplicateStrategy.APPEND) {
                    nrc()
                    addOrReplaceEcuTimer(name = "TEST", delay = 100.milliseconds) {
                        // do nothing
                    }
                }

                onReset("RESETIT") {
                }

                ecu("ECU1") {
                    request(byteArrayOf(0x10), "REQ1") { ack() }
                    request("10", "REQ2", duplicateStrategy = DuplicateStrategy.APPEND) { ack() }
                    request("10 []", "REQ3") { ack() }
                    request(
                        "10.*",
                        "REQ4",
                        duplicateStrategy = DuplicateStrategy.APPEND
                    ) { nrc(); addOrReplaceEcuInterceptor(duration = 1.seconds) { false } }
                }
            }
        }
        assertThat(networks.size).isEqualTo(1)

        val doipEntities = networks().first()._doipEntities
        assertThat(doipEntities.size).isEqualTo(1)
        assertThat(doipEntities[0].name).isEqualTo("GW")
        assertThat(doipEntities[0].requests.size).isEqualTo(4)
        assertThat(doipEntities[0].resetHandler.size).isEqualTo(1)

        assertThat(doipEntities[0].ecus.size).isEqualTo(1)
        assertThat(doipEntities[0].ecus[0].name).isEqualTo("ECU1")
        assertThat(doipEntities[0].ecus[0].requests.size).isEqualTo(4)

        assertThat(networkInstances.size).isEqualTo(0)
    }

    @Test
    fun `test multibyte ack`() {
        network {
            gateway("GW") {
                ecu("ECU") {
                    ackBytesLengthMap = mapOf(0x22.toByte() to 3)
                    request(byteArrayOf(0x22, 0x10, 0x20), "REQ2") { ack() }
                }
            }
        }

        assertThat(networks.size).isEqualTo(1)

        val doipEntities = networks().first()._doipEntities
        assertThat(doipEntities.size).isEqualTo(1)
        val ecuData = doipEntities[0].ecus[0]
        val msg = UdsMessage(
            0x1,
            0x2,
            UdsMessage.PHYSICAL,
            0x2,
            byteArrayOf(0x22, 0x10, 0x20),
            Mockito.mock(OutputChannel::class.java)
        )
        val simEcu = SimEcu(ecuData)
        val responseData = RequestResponseData(ecuData.requests[0], msg, simEcu)
        ecuData.requests[0].responseHandler.invoke(responseData)
        assertThat(responseData.response.size).isEqualTo(3)

        ecuData.ackBytesLengthMap = mapOf(0x22.toByte() to 4)
        assertThrows<IndexOutOfBoundsException> { ecuData.requests[0].responseHandler.invoke(responseData) }

        ecuData.ackBytesLengthMap = mapOf()
        ecuData.requests[0].responseHandler.invoke(responseData)
        assertThat(responseData.response.size).isEqualTo(2)
    }

    @Test
    fun `check createfunc signatures`() {
        fun createEcuFunc(createEcuFunc: CreateEcuFunc) {
            assertThat(createEcuFunc).isNotNull()
        }

        fun createGwFunc(createGwFunc: CreateDoipEntityFunc) {
            assertThat(createGwFunc).isNotNull()
            createGwFunc("TEST") {
                createEcuFunc(::ecu)
            }
        }

        fun createNetwork(createNetwork: CreateNetworkFunc) {
            assertThat(createNetwork).isNotNull()
            createNetwork {
                createGwFunc(::gateway)
            }
        }


        createNetwork(::network)
    }

    @Test
    fun `test request matcher list extensions`() {
        val list = listOf(
            RequestMatcher("TEST1", byteArrayOf(11)) { },
            RequestMatcher("TEST2", byteArrayOf(12)) { },
            RequestMatcher("TEST3", byteArrayOf(13)) { },
            RequestMatcher("TEST4", byteArrayOf(14)) { },
            RequestMatcher("TEST5", byteArrayOf(15)) { }
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
