package library

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HexExtensionsKtTest {
    @Test
    fun `test encodedAsHexString`() {
        val str = "TEST"
        assertEquals("54 45 53 54", str.encodedAsHexString())
        assertEquals("54,45,53,54", str.encodedAsHexString(","))
        assertEquals("54455354", str.encodedAsHexString(""))
    }

    @Test
    fun `test toHexString`() {
        val ba = byteArrayOf(0, 0x10, 0x20, 0xFF.toByte(), 0x7F, 0x99.toByte())
        assertEquals("00 10 20 FF 7F 99", ba.toHexString())
        assertEquals("00,10,20,FF,7F,99", ba.toHexString(","))
        assertEquals("001020FF7F99", ba.toHexString(""))
        assertEquals("0010...", ba.toHexString("", limit = 2))
        assertEquals("0010", ba.toHexString("", limit = 2, limitExceededSuffix = ""))
        assertEquals("...", ba.toHexString("", limit = 0))
        assertEquals("", ba.toHexString("", limit = 0, limitExceededSuffix = ""))
    }

    @Test
    fun `test decodeHex`() {
        assertArrayEquals(byteArrayOf(0x54, 0x45, 0x7e, 0x54), "54 45 7e 54".decodeHex())
        assertArrayEquals(byteArrayOf(0x54, 0x4a, 0xb3.toByte(), 0x54), "544ab354".decodeHex())
        assertArrayEquals(byteArrayOf(0x54, 0x45, 0x53, 0x54), "54 4553 54".decodeHex())
        assertArrayEquals(
            byteArrayOf(
                0xAB.toByte(),
                0xCD.toByte(),
                0xEF.toByte(),
                0x01,
                0x2E,
                0x1e,
                0x1f,
                0x00,
                0xfe.toByte()
            ), "AB CD EF 01 2E 1e 1f 00 fE".decodeHex()
        )
        assertThrows<IllegalArgumentException> { "54 45 53 5".decodeHex() }
    }
}
