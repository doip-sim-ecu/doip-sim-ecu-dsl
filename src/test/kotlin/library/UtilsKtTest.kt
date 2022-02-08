package library

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class UtilsKtTest {
    @Test
    fun `test short to ByteArray`() {
        assertThat(0.toShort().toByteArray()).isEqualTo(byteArrayOf(0x00, 0x00))
        assertThat(1.toShort().toByteArray()).isEqualTo(byteArrayOf(0x00, 0x01))
        assertThat(32768.toShort().toByteArray()).isEqualTo(byteArrayOf(0x80.toByte(), 0x00))
        assertThat(65535.toShort().toByteArray()).isEqualTo(byteArrayOf(0xff.toByte(), 0xff.toByte()))
        assertThat((-1).toShort().toByteArray()).isEqualTo(byteArrayOf(0xff.toByte(), 0xff.toByte()))
        assertThat(Short.MAX_VALUE.toByteArray()).isEqualTo(byteArrayOf(0x7f, 0xff.toByte()))
        assertThat(Short.MIN_VALUE.toByteArray()).isEqualTo(byteArrayOf(0x80.toByte(), 0x00.toByte()))
    }

    @Test
    fun `test int to ByteArray`() {
        assertThat(0.toByteArray()).isEqualTo(byteArrayOf(0x00, 0x00, 0x00, 0x00))
        assertThat(255.toByteArray()).isEqualTo(byteArrayOf(0x00, 0x00, 0x00, 0xff.toByte()))
        assertThat(Integer.MIN_VALUE.toByteArray()).isEqualTo(byteArrayOf(0x80.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()))
        assertThat(Integer.MAX_VALUE.toByteArray()).isEqualTo(byteArrayOf(0x7f.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte()))
        assertThat((-1).toByteArray()).isEqualTo(byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte()))
        assertThat(0xFFFFFFFF.toInt().toByteArray()).isEqualTo(byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte()))
    }
}
