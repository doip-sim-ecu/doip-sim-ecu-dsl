import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import library.EcuAdditionalVamData
import library.UdsMessage
import library.decodeHex
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.io.ByteArrayOutputStream
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SimDslTest {
    @AfterEach
    fun tearDown() {
        gateways.clear()
        gatewayInstances.clear()
    }

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

            onReset("RESETIT") {
            }

            ecu("ECU1") {
                request(byteArrayOf(0x10), "REQ1") { ack() }
                request("10", "REQ2") { ack() }
                request("10 []", "REQ3") { ack() }
                request(Regex("10.*"), "REQ4") { nrc(); addOrReplaceEcuInterceptor(duration = 1.seconds) { false } }
                additionalVam = EcuAdditionalVamData(eid = "1234".decodeHex())
            }
        }
        assertThat(gateways.size).isEqualTo(1)
        assertThat(gateways[0].name).isEqualTo("GW")
        assertThat(gateways[0].requests.size).isEqualTo(4)
        assertThat(gateways[0].resetHandler.size).isEqualTo(1)

        assertThat(gateways[0].ecus.size).isEqualTo(1)
        assertThat(gateways[0].ecus[0].name).isEqualTo("ECU1")
        assertThat(gateways[0].ecus[0].requests.size).isEqualTo(4)
        assertThat(gateways[0].ecus[0].additionalVam!!.eid).isEqualTo("1234".decodeHex())

        assertThat(gatewayInstances.size).isEqualTo(0)
    }

    @Test
    fun `test dsl insertAtTop`() {
        gateway("GW") {
            request(byteArrayOf(0x10), "REQ1") { respond(byteArrayOf(0x50)) }
            request("10", "REQ2") { respond("50") }
            request("10 []", "REQ3", insertAtTop = true) { ack() }
        }
        assertThat(gateways.size).isEqualTo(1)
        assertThat(gateways[0].requests[0].requestRegex?.pattern).isEqualTo("10.*")
    }

    @Test
    fun `test multibyte ack`() {
        gateway("GW") {
            ecu("ECU") {
                ackBytesLengthMap = mapOf(0x22.toByte() to 3)
                request(byteArrayOf(0x22, 0x10, 0x20), "REQ2") { ack() }
            }
        }

        assertThat(gateways.size).isEqualTo(1)
        val ecuData = gateways[0].ecus[0]
        val msg = UdsMessage(
            0x1,
            0x2,
            UdsMessage.PHYSICAL,
            byteArrayOf(0x22, 0x10, 0x20),
            Mockito.mock(ByteArrayOutputStream::class.java)
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
