import assertk.assertThat
import assertk.assertions.*
import io.ktor.utils.io.*
import library.UdsMessage
import library.decodeHex
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.*
import java.lang.Thread.sleep
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class SimEcuTest {
    @Test
    fun `test request matching bytearray`() {
        var first = false
        var second = false

        val ecu = SimEcu(
            ecuData(
                name = "TEST",
                requests = listOf(
                    RequestMatcher("TEST", byteArrayOf(0x00, 0x10, 0x20)) { first = true },
                    RequestMatcher("TEST2", byteArrayOf(0x00, 0x10)) { second = true }
                )
            )
        )

        ecu.handleRequest(req(byteArrayOf(0x00, 0x10)))
        assertThat(first).isFalse()
        assertThat(second).isTrue()

        second = false

        ecu.handleRequest(req(byteArrayOf(0x00, 0x10, 0x20)))
        assertThat(first).isTrue()
        assertThat(second).isFalse()
    }

    @Test
    fun `test request matching continue matching`() {
        var first = false
        var second = false

        val ecu = spy(SimEcu(
            ecuData(
                name = "TEST",
                requests = listOf(
                    RequestMatcher("TEST", byteArrayOf(0x00, 0x10)) { first = true; continueMatching(true) },
                    RequestMatcher("TEST2", byteArrayOf(0x00, 0x10)) { second = true }
                )
            )
        ))

        ecu.handleRequest(req(byteArrayOf(0x00, 0x10)))
        assertThat(first).isTrue()
        assertThat(second).isTrue()
        verify(ecu, times(0)).sendResponse(any(), any())
    }

    @Test
    fun `test request matching stop matching`() {
        var first = false
        var second = false

        val ecu = spy(SimEcu(
            ecuData(
                name = "TEST",
                requests = listOf(
                    RequestMatcher("TEST", byteArrayOf(0x00, 0x10)) { first = true; ack() },
                    RequestMatcher("TEST2", byteArrayOf(0x00, 0x10)) { second = true; nrc() }
                )
            )
        ))

        ecu.handleRequest(req(byteArrayOf(0x00, 0x10)))
        assertThat(first).isTrue()
        assertThat(second).isFalse()
        verify(ecu, times(1)).sendResponse(any(), any())
    }

    @Test
    fun `test request matching start string`() {
        var first = false
        var second = false

        val ecu = SimEcu(
            ecuData(
                name = "TEST",
                requests = listOf(
                    RequestMatcher("TEST", "1020".decodeHex(), true) { first = true },
                    RequestMatcher("TEST2", "10".decodeHex(), true) { second = true },
                )
            )
        )

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
    fun `test request matching start long request`() {
        var called = false
        val ecu = SimEcu(
            ecuData(
                name = "TEST",
                requests = listOf(
                    RequestMatcher("TEST", "1020".decodeHex(), true) { called = true },
                )
            )
        )
        val array = ByteArray(4096)
        array[0] = 0x10
        array[1] = 0x20
        for (i in 2 until array.size) {
            array[i] = 0xFF.toByte()
        }
        ecu.handleRequest(req(array))
        assertThat(called).isTrue()

    }

    @Test
    fun `test request matching no match`() {
        val requests = listOf(
            RequestMatcher(null, byteArrayOf(0x10, 0x20)) { },
            RequestMatcher("TEST2", byteArrayOf(0x10, 0x30)) { },
        )
        val ecuWithNrc = spy(SimEcu(
            ecuData(
                name = "TEST",
                nrcOnNoMatch = true,
                requests = requests
            )
        ))

        val ecuNoNrc = spy(SimEcu(
            ecuData(
                name = "TEST",
                nrcOnNoMatch = false,
                requests = requests
            )
        ))

        ecuWithNrc.handleRequest(req(byteArrayOf(0x10, 0x21)))
        ecuNoNrc.handleRequest(req(byteArrayOf(0x10, 0x21)))
        verify(ecuWithNrc, times(1)).sendResponse(any(), any())
        verify(ecuNoNrc, times(0)).sendResponse(any(), any())
    }

    @Test
    fun `test inbound interceptor`() {
        val ecu = spy(SimEcu(ecuData(name = "TEST")))
        verify(ecu, times(0)).sendResponse(any(), any())
        ecu.handleRequest(req(byteArrayOf(0x11, 0x03)))

        // sendResponse got called, because there's no interceptor and NRC was sent
        verify(ecu, times(1)).sendResponse(any(), any())

        var beforeInterceptor = false
        var intercepted = false
        var afterInterceptor = false
        var removeInterceptor = false
        ecu.addOrReplaceEcuInterceptor("TESTREMOVE", 500.milliseconds) { removeInterceptor = true; false; }
        ecu.addOrReplaceEcuInterceptor("TESTBEFORE", 500.milliseconds) { beforeInterceptor = true; false; }
        ecu.addOrReplaceEcuInterceptor("TEST", 200.milliseconds) { intercepted = true; true }
        ecu.addOrReplaceEcuInterceptor("TESTAFTER", 500.milliseconds) { afterInterceptor = true; false }
        val autoNamedInterceptor = ecu.addOrReplaceEcuInterceptor { false }

        ecu.handleRequest(req(byteArrayOf(0x11, 0x03)))
        // sendResponse didn't get called again, because there's one true interceptor, therefore no response was sent
        verify(ecu, times(1)).sendResponse(any(), any())
        assertThat(removeInterceptor).isTrue()
        assertThat(beforeInterceptor).isTrue()
        assertThat(intercepted).isTrue()
        assertThat(afterInterceptor).isFalse()
        sleep(250)
        removeInterceptor = false
        beforeInterceptor = false
        intercepted = false
        ecu.removeInterceptor("TESTREMOVE")
        ecu.removeInterceptor(autoNamedInterceptor)
        // sendResponse did get called again, because there's no true-interceptor anymore
        ecu.handleRequest(req(byteArrayOf(0x11, 0x03)))
        verify(ecu, times(2)).sendResponse(any(), any())
        assertThat(removeInterceptor).isFalse()
        assertThat(beforeInterceptor).isTrue()
        assertThat(intercepted).isFalse() // expired
        assertThat(afterInterceptor).isTrue()
    }

    @Test
    fun `test outbound interceptor`() {
        val ecu = spy(SimEcu(ecuData(name = "TEST")))
        verify(ecu, times(0)).sendResponse(any(), any())
        ecu.handleRequest(req(byteArrayOf(0x11, 0x03)))

        // sendResponse got called, because there's no interceptor and NRC was sent
        verify(ecu, times(1)).sendResponse(any(), any())

        var outboundInterceptorCalled = false
        var outboundInterceptorCalled2 = false
        ecu.addOrReplaceEcuOutboundInterceptor("OUTBOUND", 200.milliseconds) {
            outboundInterceptorCalled = true
            continueMatching()
            true
        }
        ecu.addOrReplaceEcuOutboundInterceptor("OUTBOUND2", 200.milliseconds) {
            outboundInterceptorCalled2 = true
            true
        }
        ecu.handleRequest(req(byteArrayOf(0x11, 0x03)))
        verify(ecu, times(2)).sendResponse(any(), any())
        assertThat(outboundInterceptorCalled).isTrue()
        assertThat(outboundInterceptorCalled2).isTrue()
        outboundInterceptorCalled = false
        outboundInterceptorCalled2 = false
        ecu.removeOutboundInterceptor("OUTBOUND")
        ecu.handleRequest(req(byteArrayOf(0x11, 0x03)))
        verify(ecu, times(3)).sendResponse(any(), any())
        assertThat(outboundInterceptorCalled).isFalse()
        assertThat(outboundInterceptorCalled2).isTrue()
        outboundInterceptorCalled2 = false
        sleep(216)
        ecu.handleRequest(req(byteArrayOf(0x11, 0x03)))
        assertThat(outboundInterceptorCalled).isFalse()
        assertThat(outboundInterceptorCalled2).isFalse()
    }

    @Test
    fun `test busy interceptor`() {
        val ecu = SimEcu(ecuData(name = "TEST"))
        var noBusyCalled = false
        var busyCalled = false
        ecu.requests.add(RequestMatcher("TEST", byteArrayOf(0x10, 0x03)) { println("WAITING"); sleep(1500); println("DONE") })
        ecu.addOrReplaceEcuInterceptor("NOBUSY", 3500.milliseconds) { println("NOTBUSY"); noBusyCalled = true; false; }
        ecu.addOrReplaceEcuInterceptor("BUSY", 3500.milliseconds, true) { println("BUSY ${it.isBusy}"); if (it.isBusy) busyCalled = true; false; }

        thread {
            ecu.onIncomingUdsMessage(req(byteArrayOf(0x10, 0x03)))
        }
        sleep(600)
        assertThat(noBusyCalled).isTrue()
        assertThat(busyCalled).isFalse()
        noBusyCalled = false
        ecu.onIncomingUdsMessage(req(byteArrayOf(0x10, 0x03)))
        assertThat(noBusyCalled).isFalse()
        assertThat(busyCalled).isTrue()
    }

    @Test
    fun `test continue matching`() {
        var first = false
        var second = false

        val ecu = SimEcu(ecuData(
            name = "TEST",
            requests = listOf(
                // continueMatching is prioritized higher than ack()/responses
                RequestMatcher("TEST", byteArrayOf(0x00, 0x10)) { first = true; ack(); continueMatching() },
                RequestMatcher("TEST2", byteArrayOf(0x00, 0x10)) { second = true },
            )
        ))

        ecu.handleRequest(req(byteArrayOf(0x00, 0x10)))
        assertThat(first).isTrue()
        assertThat(second).isTrue()
    }

    @Test
    fun `test timer`() {
        var timerCalled = false

        val ecu = SimEcu(ecuData(
            name = "TEST",
            requests = listOf(
                RequestMatcher("TEST", byteArrayOf(0x3E, 0x00)) {
                    ack()
                    addOrReplaceEcuTimer("TESTER PRESENT", 200.milliseconds) {
                        timerCalled = true
                    }
                }
            )
        ))

        ecu.handleRequest(req(byteArrayOf(0x3E, 0x00)))
        assertThat(timerCalled).isFalse()
        sleep(20)
        assertThat(timerCalled).isFalse()
        ecu.handleRequest(req(byteArrayOf(0x3E, 0x00)))
        assertThat(timerCalled).isFalse()
        sleep(150)
        assertThat(timerCalled).isFalse()
        ecu.handleRequest(req(byteArrayOf(0x3E, 0x00)))
        assertThat(timerCalled).isFalse()
        sleep(220)
        assertThat(timerCalled).isTrue()
    }

    @Test
    fun `test request data storage`() {
        var counter = 0

        val ecu = SimEcu(ecuData(
            name = "TEST",
            requests = listOf(
                RequestMatcher("TEST", byteArrayOf(0x3E, 0x00)) {
                    ack()
                    var requestCounter by caller.storedProperty { 0 }
                    assertThat(requestCounter).isEqualTo(counter)
                    requestCounter++
                    counter++
                    assertThat(requestCounter).isEqualTo(requestCounter)
                }
            )
        ))

        ecu.handleRequest(req(byteArrayOf(0x3E, 0x00)))
        ecu.handleRequest(req(byteArrayOf(0x3E, 0x00)))
        counter = 0
        ecu.requests.forEach { it.clearStoredProperties() }
        ecu.handleRequest(req(byteArrayOf(0x3E, 0x00)))
        ecu.handleRequest(req(byteArrayOf(0x3E, 0x00)))
    }

    @Test
    fun `test ecu data storage`() {
        val ecu = SimEcu(ecuData(
            name = "TEST",
            requests = listOf(
                RequestMatcher("TEST", byteArrayOf(0x3E, 0x00)) {
                    ack()
                    var firstRequestCalled: Boolean by this.ecu.storedProperty { false }
                    assertThat(firstRequestCalled).isEqualTo(false)
                    firstRequestCalled = true
                },
                RequestMatcher("TEST", byteArrayOf(0x3E, 0x01)) {
                    ack()
                    val firstRequestCalled: Boolean by this.ecu.storedProperty { false }
                    assertThat(firstRequestCalled).isEqualTo(true)
                },
            )
        ))

        val firstRequestCalled: Boolean by ecu.storedProperty { false }
        assertThat(firstRequestCalled).isFalse()
        ecu.handleRequest(req(byteArrayOf(0x3E, 0x00)))
        assertThat(firstRequestCalled).isTrue()
        ecu.handleRequest(req(byteArrayOf(0x3E, 0x01)))
        assertThat(firstRequestCalled).isTrue()
        ecu.clearStoredProperties()
        assertThat(firstRequestCalled).isFalse()
        ecu.handleRequest(req(byteArrayOf(0x3E, 0x00)))
        assertThat(firstRequestCalled).isTrue()
        ecu.handleRequest(req(byteArrayOf(0x3E, 0x01)))
        assertThat(firstRequestCalled).isTrue()
    }

    @Test
    fun `test sequence stop at end`() {
        val ecu = spy(SimEcu(ecuData(
            name = "TEST",
            requests = listOf(
                RequestMatcher("TEST", byteArrayOf(0x3E, 0x00)) {
                  sequenceStopAtEnd(
                      "7E 3E",
                      "7F 10")
                },
            )
        )))

        for (i in 0 until 6) {
            ecu.handleRequest(req(byteArrayOf(0x3E, 0x00)))
        }

        val captor: KArgumentCaptor<ByteArray> = argumentCaptor()
        verify(ecu, times(6)).sendResponse(any(), captor.capture())
        assertThat(captor.firstValue).containsExactly(0x7E, 0x3E)
        captor.allValues.subList(1, 5).forEach {
            assertThat(it).containsExactly(0x7F, 0x10)
        }
    }

    @Test
    fun `test sequence wrap around`() {
        val ecu = spy(SimEcu(ecuData(
            name = "TEST",
            requests = listOf(
                RequestMatcher("TEST", byteArrayOf(0x3E, 0x00)) {
                    sequenceWrapAround(
                        "7E 3E",
                        "7F 10")
                },
            )
        )))

        for (i in 0 until 6) {
            ecu.handleRequest(req(byteArrayOf(0x3E, 0x00)))
        }

        val captor: KArgumentCaptor<ByteArray> = argumentCaptor()
        verify(ecu, times(6)).sendResponse(any(), captor.capture())
        assertThat(captor.firstValue).containsExactly(0x7E, 0x3E)
        captor.allValues.forEachIndexed{ index, it ->
            if (index % 2 == 0) {
                assertThat(it).containsExactly(0x7E, 0x3E)
            } else {
                assertThat(it).containsExactly(0x7F, 0x10)
            }
        }
    }

    @Test
    fun `test nrc exception`() {
        val ecu = spy(SimEcu(ecuData(
            name = "TEST",
            requests = listOf(
                RequestMatcher("TEST", byteArrayOf(0x3E, 0x00)) {
                  throw NrcException(0x99.toByte())
                },
            )
        )))
        ecu.handleRequest(req(byteArrayOf(0x3e, 0x00)))

        val captor: KArgumentCaptor<ByteArray> = argumentCaptor()
        verify(ecu, times(1)).sendResponse(any(), captor.capture())
        assertThat(captor.firstValue).containsExactly(0x7F, 0x3E, 0x99.toByte())
    }

    @Test
    fun `test pending for`() {
        var invokeAfterCalled = false
        val ecu = spy(SimEcu(ecuData(
            name = "TEST",
            requests = listOf(
                RequestMatcher("TEST", byteArrayOf(0x3E, 0x00)) {
                    pendingFor(950.milliseconds) {
                        invokeAfterCalled = true
                    }
                    ack()
                },
            )
        )))
        ecu.handleRequest(req(byteArrayOf(0x3e, 0x00)))

        val captor: KArgumentCaptor<ByteArray> = argumentCaptor()
        verify(ecu, atLeast(10)).sendResponse(any(), captor.capture())
        val values = captor.allValues
        for (i in 0 until values.size - 1) {
            assertThat(values[i]).containsExactly(0x7F, 0x3E, 0x78.toByte())
        }
        assertThat(captor.lastValue).containsExactly(0x7e, 0x00)
        assertThat(values.size).isBetween(10, 11)
        assertThat(invokeAfterCalled).isTrue()
    }

    private fun ecuData(
        name: String,
        logicalAddress: Short = 0x0001,
        functionalAddress: Short = 0x0002,
        nrcOnNoMatch: Boolean = true,
        pendingNrcSendInterval: Duration = 100.milliseconds,
        requests: List<RequestMatcher> = emptyList()
    ): EcuData =
        EcuData(
            name = name,
            logicalAddress = logicalAddress,
            functionalAddress = functionalAddress,
            nrcOnNoMatch = nrcOnNoMatch,
            requests = requests,
            pendingNrcSendInterval = pendingNrcSendInterval,
        )

    private fun req(
        data: ByteArray,
        sourceAddress: Short = 0x0000,
        targetAddress: Short = 0x0001,
        targetAddressType: Int = UdsMessage.PHYSICAL
    ): UdsMessage =
        UdsMessage(
            sourceAddress = sourceAddress,
            targetAddress = targetAddress,
            targetAddressType = targetAddressType,
            message = data,
            output = Mockito.mock(ByteWriteChannel::class.java)
        )
}
