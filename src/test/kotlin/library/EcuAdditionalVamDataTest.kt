package library

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class EcuAdditionalVamDataTest {
    @Test
    fun `test additional vam data`() {
        val dataEid = Random.Default.nextBytes(6)
        val dataGid = Random.Default.nextBytes(6)
        val dataVin = Random.Default.nextBytes(17)
        val vamData =
            EcuAdditionalVamData(dataEid, dataGid, dataVin)
        val ecuConfig = EcuConfig("TEST", 0x2222, 0x3333)
        val doipConfig = DoipEntityConfig("TEST", 0x1111, dataEid, dataGid, dataVin)
        val vam = vamData.toVam(ecuConfig, doipConfig)

        val vamData2 =
            EcuAdditionalVamData(dataEid, dataGid, dataVin)
        assertThat(vam.eid).isEqualTo(dataEid)
        assertThat(vam.gid).isEqualTo(dataGid)
        assertThat(vam.vin).isEqualTo(dataVin)
        assertThat(vam.logicalAddress).isEqualTo(0x2222)
        assertThat(vamData == vamData2).isTrue()
        assertThat(vamData.hashCode()).isEqualTo(vamData2.hashCode())
    }
}
