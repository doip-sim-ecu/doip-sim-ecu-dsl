package library

import DefaultAckBytesLengthMap
import RequestMatcher
import assertk.assertThat
import assertk.assertions.isTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.measureTime

class RequestListPerformanceTest {
    private lateinit var requestList: RequestList
    private lateinit var generatedRequests: List<ByteArray>

    private val repetitions = 150

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
                l.add(byteArrayOf(0x36, *ar))
                l.add(byteArrayOf(0x36, *ar))
                l.add(byteArrayOf(0x36, *ar))
                l.add(byteArrayOf(0x36, *ar))
                val data = ByteArray(100)
                rnd.nextBytes(data)
                l.add(byteArrayOf(it.key.toByte(), *ar, *data))
            }
            l
        }.flatten()

        requestList = RequestList(
            listOf(RequestMatcher("data", byteArrayOf(0x36), true) { ack() }) +
            generatedRequests
                .filter { it[0] != 0x36.toByte() }
                .map {
                    val l = DefaultAckBytesLengthMap[it[0]] ?: 2
                    RequestMatcher(it.toHexString(), it.toHexString(limit = l).decodeHex(), onlyStartsWith = true) {
                        ack()
                    }
                }
        )

    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `test indexed`() {
        val c = AtomicInteger(0)
        val duration = measureTime {
            for (i in 0 until repetitions) {
                generatedRequests.forEach {
                    c.incrementAndGet()
                    assertThat(requestList.findMessageAndHandle(it) {
                        true
                    }).isTrue()
                }
            }
        }
        println("duration indexed $duration for ${c.get()} accesses")
    }
}
