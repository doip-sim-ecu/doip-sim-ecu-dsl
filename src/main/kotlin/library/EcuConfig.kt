package library

import kotlin.time.Duration.Companion.seconds

public open class EcuConfig(
    public val name: String,
    public val logicalAddress: Short,
    public val functionalAddress: Short,
    public val pendingNrcSendInterval: kotlin.time.Duration = 2.seconds,
)
