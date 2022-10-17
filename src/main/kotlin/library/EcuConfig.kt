package library

import kotlin.time.Duration.Companion.seconds

open class EcuConfig(
    val name: String,
    val physicalAddress: Short,
    val functionalAddress: Short,
    val pendingNrcSendInterval: kotlin.time.Duration = 2.seconds,
    val additionalVam: EcuAdditionalVamData? = null,
)
