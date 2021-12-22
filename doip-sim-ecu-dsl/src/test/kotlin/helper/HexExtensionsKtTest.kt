package helper

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HexExtensionsKtTest {
    @Test
    fun `test toHexString`() {
        val str = "TEST"
        assertEquals("54 45 53 54", str.toHexString())
        assertEquals("54,45,53,54", str.toHexString(","))
        assertEquals("54455354", str.toHexString(""))

        val ba = byteArrayOf(0, 0x10, 0x20, 0xFF.toByte(), 0x7F, 0x99.toByte())
        assertEquals("00 10 20 FF 7F 99", ba.toHexString())
        assertEquals("00,10,20,FF,7F,99", ba.toHexString(","))
        assertEquals("001020FF7F99", ba.toHexString(""))
        assertEquals("0010", ba.toHexString("", limit = 2))
        assertEquals("", ba.toHexString("", limit = 0))
    }

    @Test
    fun `test decodeHex`() {
        assertArrayEquals(byteArrayOf(0x54, 0x45, 0x53, 0x54), "54 45 53 54".decodeHex())
        assertArrayEquals(byteArrayOf(0x54, 0x45, 0x53, 0x54), "54455354".decodeHex())
        assertArrayEquals(byteArrayOf(0x54, 0x45, 0x53, 0x54), "54 4553 54".decodeHex())
    }
}
