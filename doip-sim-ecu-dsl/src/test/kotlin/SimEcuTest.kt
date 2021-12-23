import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import doip.library.message.UdsMessage
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.spy
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import kotlin.time.Duration.Companion.milliseconds

class SimEcuTest {
    @Test
    fun `test request matching bytearray`() {
        val data = ecuData("TEST")

        var first = false
        var second = false

        val ecu = SimEcu(data)
        data.requests.add(RequestMatcher("TEST", byteArrayOf(0x00, 0x10, 0x20), null) { first = true })
        data.requests.add(RequestMatcher("TEST2", byteArrayOf(0x00, 0x10), null) { second = true })

        ecu.handleRequest(req(byteArrayOf(0x00, 0x10)))
        assertThat(first).isFalse()
        assertThat(second).isTrue()

        second = false

        ecu.handleRequest(req(byteArrayOf(0x00, 0x10, 0x20)))
        assertThat(first).isTrue()
        assertThat(second).isFalse()
    }

    @Test
    fun `test request matching regex`() {
        val data = ecuData("TEST")

        var first = false
        var second = false

        val ecu = SimEcu(data)
        data.requests.add(RequestMatcher("TEST", null, Regex("1020.*")) { first = true })
        data.requests.add(RequestMatcher("TEST2", null, Regex("10.*")) { second = true })

        ecu.handleRequest(req(byteArrayOf(0x10, 0x20)))
        assertThat(first).isTrue()
        assertThat(second).isFalse()

        first = false
        second = false

        ecu.handleRequest(req(byteArrayOf(0x10, 0x10, 0x20)))
        assertThat(first).isFalse()
        assertThat(second).isTrue()
    }

    @Test
    fun `test interceptor`() {
        val data = EcuData("TEST")
        val ecu = spy(SimEcu(data))
        verify(ecu, times(0)).sendResponse(any(), any())
        ecu.handleRequest(UdsMessage(0x0000, 0x0001, byteArrayOf(0x11, 0x03)))

        // sendResponse got called, because there's no interceptor and NRC was sent
        verify(ecu, times(1)).sendResponse(any(), any())

        var beforeInterceptor = false
        var intercepted = false
        var afterInterceptor = false
        var removeInterceptor = false
        ecu.addInterceptor("TESTREMOVE", 1000.milliseconds) { removeInterceptor = true; false; }
        ecu.addInterceptor("TESTBEFORE", 1000.milliseconds) { beforeInterceptor = true; false; }
        ecu.addInterceptor("TEST", 200.milliseconds) { intercepted = true; true }
        ecu.addInterceptor("TESTAFTER", 1000.milliseconds) { afterInterceptor = true; false }

        ecu.handleRequest(UdsMessage(0x0000, 0x0001, byteArrayOf(0x11, 0x03)))
        // sendResponse didn't get called again, because there's one true interceptor, therefor no response was sent
        verify(ecu, times(1)).sendResponse(any(), any())
        assertThat(removeInterceptor).isTrue()
        assertThat(beforeInterceptor).isTrue()
        assertThat(intercepted).isTrue()
        assertThat(afterInterceptor).isFalse()
        Thread.sleep(200)
        removeInterceptor = false
        beforeInterceptor = false
        intercepted = false
        ecu.removeInterceptor("TESTREMOVE")
        // sendResponse did get called again, because there's no true-interceptor anymore
        ecu.handleRequest(UdsMessage(0x0000, 0x0001, byteArrayOf(0x11, 0x03)))
        verify(ecu, times(2)).sendResponse(any(), any())
        assertThat(removeInterceptor).isFalse()
        assertThat(beforeInterceptor).isTrue()
        assertThat(intercepted).isFalse() // expired
        assertThat(afterInterceptor).isTrue()
    }

    @Test
    fun `test continue matching`() {
        val data = ecuData("TEST")

        var first = false
        var second = false

        val ecu = SimEcu(data)
        data.requests.add(RequestMatcher("TEST", byteArrayOf(0x00, 0x10), null) { first = true; continueMatching() })
        data.requests.add(RequestMatcher("TEST2", byteArrayOf(0x00, 0x10), null) { second = true })

        ecu.handleRequest(req(byteArrayOf(0x00, 0x10)))
        assertThat(first).isTrue()
        assertThat(second).isTrue()
    }

    @Test
    fun `test timer`() {
        val data = ecuData("TEST")

        val ecu = SimEcu(data)
        var timerCalled = false

        data.requests.add(RequestMatcher("TEST", byteArrayOf(0x3E, 0x00), null) {
            ack()
            addOrReplaceEcuTimer("TESTER PRESENT", 200.milliseconds) {
                timerCalled = true
            }
        })
        ecu.handleRequest(req(byteArrayOf(0x3E, 0x00)))
        assertThat(timerCalled).isFalse()
        Thread.sleep(20)
        assertThat(timerCalled).isFalse()
        ecu.handleRequest(req(byteArrayOf(0x3E, 0x00)))
        assertThat(timerCalled).isFalse()
        Thread.sleep(150)
        assertThat(timerCalled).isFalse()
        ecu.handleRequest(req(byteArrayOf(0x3E, 0x00)))
        assertThat(timerCalled).isFalse()
        Thread.sleep(220)
        assertThat(timerCalled).isTrue()
    }

    @Test
    fun `test request data storage`() {
        val data = ecuData("TEST")

        val ecu = SimEcu(data)

        var counter = 0

        data.requests.add(RequestMatcher("TEST", byteArrayOf(0x3E, 0x00), null) {
            ack()
            var storedValue: Boolean by caller.storedProperty { false }
            assertThat(storedValue).isEqualTo(counter > 0)
            storedValue = true
            counter++
            assertThat(storedValue).isTrue()
        })

        ecu.handleRequest(req(byteArrayOf(0x3E, 0x00)))
        ecu.handleRequest(req(byteArrayOf(0x3E, 0x00)))
        counter = 0
        data.requests.forEach { it.clearStoredProperties() }
        ecu.handleRequest(req(byteArrayOf(0x3E, 0x00)))
        ecu.handleRequest(req(byteArrayOf(0x3E, 0x00)))
    }

    @Test
    fun `test ecu data storage`() {
        val data = ecuData("TEST")

        val ecu = SimEcu(data)

        data.requests.add(RequestMatcher("TEST", byteArrayOf(0x3E, 0x00), null) {
            ack()
            var storedValue: Boolean by simEcu.storedProperty { false }
            assertThat(storedValue).isEqualTo(false)
            @Suppress("UNUSED_VALUE")
            storedValue = true
        })

        data.requests.add(RequestMatcher("TEST", byteArrayOf(0x3E, 0x01), null) {
            ack()
            val storedValue: Boolean by simEcu.storedProperty { false }
            assertThat(storedValue).isEqualTo(true)
        })

        ecu.handleRequest(req(byteArrayOf(0x3E, 0x00)))
        ecu.handleRequest(req(byteArrayOf(0x3E, 0x01)))
        ecu.clearStoredProperties()
        ecu.handleRequest(req(byteArrayOf(0x3E, 0x00)))
        ecu.handleRequest(req(byteArrayOf(0x3E, 0x01)))
    }

    private fun ecuData(name: String, physicalAddress: Int = 0x0001, functionalAddress: Int = 0x0002): EcuData {
        val data = EcuData(name)
        data.physicalAddress = physicalAddress
        data.functionalAddress = functionalAddress
        return data
    }

    private fun req(data: ByteArray, sourceAddress: Int = 0x0000, targetAddress: Int = 0x0001): UdsMessage =
        UdsMessage(sourceAddress, targetAddress, data)
}
