import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import library.can.CanDlc
import library.can.isotp.IsoTpConsecutiveFrame
import library.can.isotp.IsoTpFirstFrame
import library.can.isotp.IsoTpFlowControlFrame
import library.can.isotp.IsoTpFraming
import library.can.isotp.IsoTpOptions
import library.can.isotp.IsoTpSingleFrame
import org.junit.jupiter.api.Test

class IsoTpFramingTest {
    private val classic = IsoTpOptions()
    private val classicNoPadding = IsoTpOptions(paddingByte = null)
    private val fd64 = IsoTpOptions(fd = true, txDlc = 64)

    @Test
    fun `single frame roundtrip classic`() {
        val payload = byteArrayOf(0x22, 0xF1.toByte(), 0x86.toByte())
        val encoded = IsoTpFraming.encodeSingleFrame(payload, classic)
        assertThat(encoded.size).isEqualTo(8) // padded
        assertThat(encoded[0]).isEqualTo(0x03.toByte())
        assertThat(encoded[4]).isEqualTo(0xCC.toByte())
        val decoded = IsoTpFraming.decode(encoded) as IsoTpSingleFrame
        assertThat(decoded.payload.toList()).isEqualTo(payload.toList())
    }

    @Test
    fun `single frame without padding keeps exact length`() {
        val payload = byteArrayOf(0x3E, 0x00)
        val encoded = IsoTpFraming.encodeSingleFrame(payload, classicNoPadding)
        assertThat(encoded.size).isEqualTo(3)
    }

    @Test
    fun `single frame fd escape roundtrip`() {
        val payload = ByteArray(40) { it.toByte() }
        val encoded = IsoTpFraming.encodeSingleFrame(payload, fd64)
        assertThat(encoded[0]).isEqualTo(0x00.toByte())
        assertThat(encoded[1]).isEqualTo(40.toByte())
        assertThat(encoded.size).isEqualTo(CanDlc.nextValidLength(42))
        val decoded = IsoTpFraming.decode(encoded) as IsoTpSingleFrame
        assertThat(decoded.payload.toList()).isEqualTo(payload.toList())
    }

    @Test
    fun `small payload on fd bus uses classic single frame`() {
        val payload = ByteArray(5) { it.toByte() }
        val encoded = IsoTpFraming.encodeSingleFrame(payload, fd64)
        assertThat(encoded[0]).isEqualTo(0x05.toByte())
        assertThat(encoded.size).isEqualTo(8)
    }

    @Test
    fun `first frame roundtrip 12 bit length`() {
        val totalLength = 4095
        val payload = ByteArray(IsoTpFraming.firstFramePayloadSize(totalLength, classic)) { it.toByte() }
        assertThat(payload.size).isEqualTo(6)
        val encoded = IsoTpFraming.encodeFirstFrame(totalLength, payload, classic)
        assertThat(encoded[0]).isEqualTo(0x1F.toByte())
        assertThat(encoded[1]).isEqualTo(0xFF.toByte())
        val decoded = IsoTpFraming.decode(encoded) as IsoTpFirstFrame
        assertThat(decoded.totalLength).isEqualTo(totalLength)
        assertThat(decoded.payload.toList()).isEqualTo(payload.toList())
    }

    @Test
    fun `first frame roundtrip 32 bit escape length`() {
        val totalLength = 100_000
        val payload = ByteArray(IsoTpFraming.firstFramePayloadSize(totalLength, classic)) { it.toByte() }
        assertThat(payload.size).isEqualTo(2)
        val encoded = IsoTpFraming.encodeFirstFrame(totalLength, payload, classic)
        assertThat(encoded[0]).isEqualTo(0x10.toByte())
        assertThat(encoded[1]).isEqualTo(0x00.toByte())
        val decoded = IsoTpFraming.decode(encoded) as IsoTpFirstFrame
        assertThat(decoded.totalLength).isEqualTo(totalLength)
        assertThat(decoded.payload.toList()).isEqualTo(payload.toList())
    }

    @Test
    fun `consecutive frame roundtrip`() {
        val payload = ByteArray(7) { (it + 1).toByte() }
        val encoded = IsoTpFraming.encodeConsecutiveFrame(5, payload, classic)
        assertThat(encoded[0]).isEqualTo(0x25.toByte())
        val decoded = IsoTpFraming.decode(encoded) as IsoTpConsecutiveFrame
        assertThat(decoded.sequenceNumber).isEqualTo(5)
        assertThat(decoded.payload.toList()).isEqualTo(payload.toList())
    }

    @Test
    fun `short last consecutive frame is padded`() {
        val payload = ByteArray(2) { it.toByte() }
        val encoded = IsoTpFraming.encodeConsecutiveFrame(2, payload, classic)
        assertThat(encoded.size).isEqualTo(8)
        assertThat(encoded[7]).isEqualTo(0xCC.toByte())
    }

    @Test
    fun `flow control roundtrip`() {
        val encoded = IsoTpFraming.encodeFlowControlFrame(IsoTpFlowControlFrame.CONTINUE_TO_SEND, 8, 20, classic)
        assertThat(encoded[0]).isEqualTo(0x30.toByte())
        assertThat(encoded.size).isEqualTo(8)
        val decoded = IsoTpFraming.decode(encoded) as IsoTpFlowControlFrame
        assertThat(decoded.status).isEqualTo(IsoTpFlowControlFrame.CONTINUE_TO_SEND)
        assertThat(decoded.blockSize).isEqualTo(8)
        assertThat(decoded.stMin).isEqualTo(20)
    }

    @Test
    fun `flow control on fd bus stays classic sized`() {
        val encoded = IsoTpFraming.encodeFlowControlFrame(IsoTpFlowControlFrame.WAIT, 0, 0, fd64)
        assertThat(encoded.size).isEqualTo(8)
        assertThat(IsoTpFraming.decode(encoded)).isNotNull().isInstanceOf(IsoTpFlowControlFrame::class)
    }

    @Test
    fun `invalid frames decode to null`() {
        assertThat(IsoTpFraming.decode(ByteArray(0))).isNull()
        // SF length 0
        assertThat(IsoTpFraming.decode(byteArrayOf(0x00))).isNull()
        // SF length exceeds frame
        assertThat(IsoTpFraming.decode(byteArrayOf(0x07, 0x01))).isNull()
        // FC with invalid status
        assertThat(IsoTpFraming.decode(byteArrayOf(0x3F, 0x00, 0x00))).isNull()
        // unknown PCI
        assertThat(IsoTpFraming.decode(byteArrayOf(0x40, 0x00))).isNull()
    }

    @Test
    fun `stmin conversion`() {
        assertThat(IsoTpFraming.stMinToMillis(0)).isEqualTo(0L)
        assertThat(IsoTpFraming.stMinToMillis(0x7F)).isEqualTo(127L)
        assertThat(IsoTpFraming.stMinToMillis(0xF1)).isEqualTo(1L)
        assertThat(IsoTpFraming.stMinToMillis(0xF9)).isEqualTo(1L)
        // reserved values fall back to the maximum
        assertThat(IsoTpFraming.stMinToMillis(0x80)).isEqualTo(127L)
        assertThat(IsoTpFraming.stMinToMillis(0xFF)).isEqualTo(127L)
    }

    @Test
    fun `dlc helper next valid length`() {
        assertThat(CanDlc.nextValidLength(0)).isEqualTo(0)
        assertThat(CanDlc.nextValidLength(8)).isEqualTo(8)
        assertThat(CanDlc.nextValidLength(9)).isEqualTo(12)
        assertThat(CanDlc.nextValidLength(33)).isEqualTo(48)
        assertThat(CanDlc.nextValidLength(64)).isEqualTo(64)
    }
}
