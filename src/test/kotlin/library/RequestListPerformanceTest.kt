package library

import RequestMatcher
import assertk.assertThat
import assertk.assertions.isTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.measureTime

class RequestListPerformanceTest {
    private lateinit var requestList: RequestList
    private lateinit var generatedRequests: List<ByteArray>

    private val repetitions = 15

    @BeforeEach
    fun setUp() {
        val servicesList = mapOf(
            0x36 to 100,
            0x31 to 4,
            0x22 to 3,
            0x2e to 3,
        )
        val rnd = Random()
        generatedRequests = servicesList.map {
            val l = mutableListOf<ByteArray>()
            for (i in 0..200) {
                val ar = ByteArray(it.value)
                rnd.nextBytes(ar)
                l.add(byteArrayOf(it.key.toByte(), *ar))
                l.add(byteArrayOf(0x36, *ar))
                l.add(byteArrayOf(0x36, *ar))
                l.add(byteArrayOf(0x36, *ar))
                l.add(byteArrayOf(0x36, *ar))
            }
            l
        }.flatten()

        requestList = RequestList(
            listOf(RequestMatcher("data", byteArrayOf(0x36), true) {}) +
            generatedRequests
                .filter { it[0] != 0x36.toByte() }
                .map { RequestMatcher(it.toHexString(), it) { } }
        )

    }

    @Test
    fun `test non indexed`() {
        RequestList.indexActive = false
        val c = AtomicInteger(0)
        val duration = measureTime {
            for (i in 0 until repetitions) {
                generatedRequests.forEach {
                    c.incrementAndGet()
                    assertThat(requestList.findMessageAndHandle("TEST", it) {
                        true
                    }).isTrue()
                }
            }
        }
        println("duration non indexed $duration for ${c.get()} accesses")
    }

    @Test
    fun `test indexed`() {
        RequestList.indexActive = true
        val c = AtomicInteger(0)
        val duration = measureTime {
            for (i in 0 until repetitions) {
                generatedRequests.forEach {
                    c.incrementAndGet()
                    assertThat(requestList.findMessageAndHandle("TEST", it) {
                        true
                    }).isTrue()
                }
            }
        }
        println("duration indexed $duration for ${c.get()} accesses")
    }
}
