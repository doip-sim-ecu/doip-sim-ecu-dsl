package library

public data class EcuAdditionalVamData(
    public val eid: EID,
    public val gid: GID? = null,
    public val vin: VIN? = null,
) {
    public fun toVam(ecuConfig: EcuConfig, entityConfig: DoipEntityConfig): DoipUdpVehicleAnnouncementMessage =
        DoipUdpVehicleAnnouncementMessage(
            eid = this.eid,
            gid = this.gid ?: entityConfig.gid,
            vin = this.vin ?: entityConfig.vin,
            logicalAddress = ecuConfig.logicalAddress,
        )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EcuAdditionalVamData

        if (!eid.contentEquals(other.eid)) return false
        if (gid != null) {
            if (other.gid == null) return false
            if (!gid.contentEquals(other.gid)) return false
        } else if (other.gid != null) return false
        if (vin != null) {
            if (other.vin == null) return false
            if (!vin.contentEquals(other.vin)) return false
        } else if (other.vin != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = eid.contentHashCode()
        result = 31 * result + (gid?.contentHashCode() ?: 0)
        result = 31 * result + (vin?.contentHashCode() ?: 0)
        return result
    }


}
