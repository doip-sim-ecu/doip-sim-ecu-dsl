import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.slf4j.MDCContext
import library.*
import org.slf4j.MDC

@Suppress("unused")
public open class DoipEntityData(name: String, public val nodeType: DoipNodeType = DoipNodeType.GATEWAY) : EcuData(name) {
    /**
     * Vehicle identifier, 17 chars, will be filled with '0`, or if left null, set to 0xFF
     */
    public var vin: String? = null // 17 byte VIN

    /**
     * Group ID of the gateway
     */
    public var gid: ByteArray = byteArrayOf(0, 0, 0, 0, 0, 0) // 6 byte group identification (used before mac is set)

    /**
     * Entity ID of the gateway
     */
    public var eid: ByteArray = byteArrayOf(0, 0, 0, 0, 0, 0) // 6 byte entity identification (usually MAC)

    /**
     * Maximum payload data size allowed for a DoIP message
     */
    public var maxDataSize: Int = Int.MAX_VALUE

    private val _ecus: MutableList<EcuData> = mutableListOf()

    public val ecus: List<EcuData>
        get() = this._ecus.toList()

    /**
     * Defines an ecu and its properties as behind this gateway
     */
    public fun ecu(name: String, receiver: EcuData.() -> Unit) {
        val ecuData = EcuData(name)
        receiver.invoke(ecuData)
        _ecus.add(ecuData)
    }
}

private fun DoipEntityData.toDoipEntityConfig(): DoipEntityConfig {
    val config = DoipEntityConfig(
        name = this.name,
        gid = this.gid,
        eid = this.eid,
        logicalAddress = this.logicalAddress,
        pendingNrcSendInterval = this.pendingNrcSendInterval,
        // Fill up too short vin's with 'Z' - if no vin is given, use 0xFF, as defined in ISO 13400 for when no vin is set (yet)
        vin = this.vin?.padEnd(17, '0')?.toByteArray() ?: ByteArray(17).let { it.fill(0xFF.toByte()); it },
        maxDataSize = this.maxDataSize,
        nodeType = nodeType,
    )

    // Add the gateway itself as an ecu, so it too can receive requests
    val gatewayEcuConfig = EcuConfig(
        name = this.name,
        logicalAddress = this.logicalAddress,
        functionalAddress = this.functionalAddress,
        pendingNrcSendInterval = this.pendingNrcSendInterval,
    )
    config.ecuConfigList.add(gatewayEcuConfig)

    // Add all the ecus defined for the gateway to the ecuConfigList, so they can later be found and instantiated as SimDslEcu
    config.ecuConfigList.addAll(this.ecus.map { it.toEcuConfig() })
    return config
}

@Suppress("MemberVisibilityCanBePrivate")
public open class SimDoipEntity(private val data: DoipEntityData) : DoipEntity<SimEcu>(data.toDoipEntityConfig()) {
    public val requests: RequestList
        get() = data.requests

    override fun createEcu(config: EcuConfig): SimEcu {
        if (config.name == data.name) {
            // To be able to handle requests for the gateway itself, insert a dummy ecu with the gateways logicalAddress
            val ecu = EcuData(
                name = data.name,
                logicalAddress = data.logicalAddress,
                functionalAddress = data.functionalAddress,
                requests = data.requests,
                nrcOnNoMatch =  data.nrcOnNoMatch,
                resetHandler = data.resetHandler,
                ackBytesLengthMap = data.ackBytesLengthMap,
                pendingNrcSendInterval = data.pendingNrcSendInterval,
            )
            return SimEcu(ecu)
        }

        // Match the other ecus by name, and create an SimDslEcu for them, since StandardEcu can't handle our
        // requirements for handling requests
        val ecuData = data.ecus.first { it.name == config.name }
        return SimEcu(ecuData)
    }

    public override fun reset(recursiveEcus: Boolean) {
        runBlocking {
            MDC.put("ecu", name)

            launch(MDCContext()) {
                logger.infoIf { "Resetting doip entity" }
                requests.forEach { it.reset() }
                if (recursiveEcus) {
                    ecus.forEach { it.reset() }
                }
            }
        }
    }
}

