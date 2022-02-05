import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test
import kotlin.test.assertIs

class DoipUdpMessageParserTest {
    @Test
    fun `test udp messages`() {
        val eid = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60)
        val gid = byteArrayOf(0x60, 0x50, 0x40, 0x30, 0x20, 0x10)
        val vin = "01234567891234567".encodeToByteArray()

        val negAckMsg = doipMessage(0x0000, 0x10)
        val negAck = DoipUdpMessageParser.parseUDP(negAckMsg)
        assertIs<DoipUdpHeaderNegAck>(negAck)
        assertThat(negAck.code).isEqualTo(0x10)
        assertThat(negAck.message).isEqualTo(negAckMsg)

        val virMsg = doipMessage(0x0001)
        val vir = DoipUdpMessageParser.parseUDP(virMsg)
        assertIs<DoipUdpVehicleInformationRequest>(vir)
        assertThat(vir.message).isEqualTo(virMsg)

        val virEIDMsg = doipMessage(0x0002, *eid)
        val virEid = DoipUdpMessageParser.parseUDP(virEIDMsg)
        assertIs<DoipUdpVehicleInformationRequestWithEid>(virEid)
        assertThat(virEid.eid).isEqualTo(eid)
        assertThat(virEid.message).isEqualTo(virEIDMsg)

        val virVINMsg = doipMessage(0x0003, *vin)
        val virVIN = DoipUdpMessageParser.parseUDP(virVINMsg)
        assertIs<DoipUdpVehicleInformationRequestWithVIN>(virVIN)
        assertThat(virVIN.vin).isEqualTo(vin)
        assertThat(virVIN.message).isEqualTo(virVINMsg)

        val vamMsg = doipMessage(0x0004, *vin, 0x10, 0x01, *eid, *gid, 0x02, 0x03)
        val vam = DoipUdpMessageParser.parseUDP(vamMsg)
        assertIs<DoipUdpVehicleAnnouncementMessage>(vam)
        assertThat(vam.vin).isEqualTo(vin)
        assertThat(vam.logicalAddress).isEqualTo(0x1001)
        assertThat(vam.gid).isEqualTo(gid)
        assertThat(vam.eid).isEqualTo(eid)
        assertThat(vam.furtherActionRequired).isEqualTo(0x02)
        assertThat(vam.syncStatus).isEqualTo(0x03)
        assertThat(vam.message).isEqualTo(vamMsg)

        val esReqMsg = doipMessage(0x4001)
        val esReq = DoipUdpMessageParser.parseUDP(esReqMsg)
        assertIs<DoipUdpEntityStatusRequest>(esReq)
        assertThat(esReq.message).isEqualTo(esReqMsg)

        val esResMsg = doipMessage(0x4002, 0x01, 0x02, 0x03, 0xff.toByte(), 0x00, 0xff.toByte(), 0x00)
        val esRes = DoipUdpMessageParser.parseUDP(esResMsg)
        assertIs<DoipUdpEntityStatusResponse>(esRes)
        assertThat(esRes.nodeType).isEqualTo(0x01)
        assertThat(esRes.numberOfSockets).isEqualTo(0x02)
        assertThat(esRes.currentNumberOfSockets).isEqualTo(0x03)
        assertThat(esRes.maxDataSize).isEqualTo(0xff00ff00.toInt())
        assertThat(esRes.message).isEqualTo(esResMsg)

        val pmReqMsg = doipMessage(0x4003)
        val pmReq = DoipUdpMessageParser.parseUDP(pmReqMsg)
        assertIs<DoipUdpDiagnosticPowerModeRequest>(pmReq)
        assertThat(pmReq.message).isEqualTo(pmReqMsg)

        val pmResMsg = doipMessage(0x4004, 0x01)
        val pmRes = DoipUdpMessageParser.parseUDP(pmResMsg)
        assertIs<DoipUdpDiagnosticPowerModeResponse>(pmRes)
        assertThat(pmRes.diagPowerMode).isEqualTo(0x01)
        assertThat(pmRes.message).isEqualTo(pmResMsg)
    }
}
