package library

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

class DoipEntityConfigTest {
    @Test
    fun `test constructor`() {
        val data = Random.nextBytes(6)
        val vin = Random.nextBytes(17)
        val e = assertThrows<IllegalArgumentException> {
            DoipEntityConfig("", 0x1234, data, data, vin)
        }
        assertThat(e.message).isEqualTo("name must be not empty")
        val e2 = assertThrows<IllegalArgumentException> {
            DoipEntityConfig("test", 0x1234, byteArrayOf(1), data, vin)
        }
        assertThat(e2.message).isEqualTo("gid must be 6 bytes")
        val e3 = assertThrows<IllegalArgumentException> {
            DoipEntityConfig("test", 0x1234, data, byteArrayOf(2), vin)
        }
        assertThat(e3.message).isEqualTo("eid must be 6 bytes")
        val e4 = assertThrows<IllegalArgumentException> {
            DoipEntityConfig("test", 0x1234, data, data, byteArrayOf())
        }
        assertThat(e4.message).isEqualTo("vin must be 17 bytes")

        DoipEntityConfig("1234", 0x1234, data, data, vin)
    }
}
