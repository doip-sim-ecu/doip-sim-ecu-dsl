package library

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

class DoipEntityConfigTest {
    @Test
    fun `test constructor`() {
        val data = Random.nextBytes(6)
        val vin = Random.nextBytes(17)
        assertThat(assertThrows<IllegalArgumentException> {
            DoipEntityConfig("", 0x1234, data, data, vin)
        }.message).isEqualTo("name must be not empty")
        assertThat(assertThrows<IllegalArgumentException> {
            DoipEntityConfig("test", 0x1234, byteArrayOf(1), data, vin)
        }.message).isEqualTo("gid must be 6 bytes")
        assertThat(assertThrows<IllegalArgumentException> {
            DoipEntityConfig("test", 0x1234, data, byteArrayOf(2), vin)
        }.message).isEqualTo("eid must be 6 bytes")
        assertThat(assertThrows<IllegalArgumentException> {
            DoipEntityConfig("test", 0x1234, data, data, byteArrayOf())
        }.message).isEqualTo("vin must be 17 bytes")

        DoipEntityConfig("1234", 0x1234, data, data, vin)
    }
}
