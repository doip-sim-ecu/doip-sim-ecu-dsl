package library

data class EcuAdditionalVamData(
    val eid: EID,
    val gid: GID? = null,
    val vin: VIN? = null,
) {
    fun toVam(ecuConfig: EcuConfig, entityConfig: DoipEntityConfig): DoipUdpVehicleAnnouncementMessage =
        DoipUdpVehicleAnnouncementMessage(
            eid = this.eid,
            gid = this.gid ?: entityConfig.gid,
            vin = this.vin ?: entityConfig.vin,
            logicalAddress = ecuConfig.physicalAddress,
        )
}
