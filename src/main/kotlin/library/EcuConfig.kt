package library

open class EcuConfig(
    val name: String,
    val physicalAddress: Short,
    val functionalAddress: Short,
    val pendingNrcSendInterval: kotlin.time.Duration,
    val additionalVam: EcuAdditionalVamData? = null,
)
