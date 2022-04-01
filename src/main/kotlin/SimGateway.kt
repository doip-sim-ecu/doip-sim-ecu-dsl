import library.*
import org.slf4j.MDC
import kotlin.properties.Delegates
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

open class GatewayData(name: String) : RequestsData(name) {
    /**
     * Network address this gateway should bind on (default: 0.0.0.0)
     */
    var localAddress: String = "0.0.0.0"

    /**
     * Network port this gateway should bind on (default: 13400)
     */
    var localPort: Int = 13400

    /**
     * Multicast address
     */
    var multicastAddress: String? = null

    /**
     * Whether VAM broadcasts shall be sent on startup (default: true)
     */
    var broadcastEnable: Boolean = true

    /**
     * Default broadcast address for VAM messages (default: 255.255.255.255)
     */
    var broadcastAddress: String = "255.255.255.255"

    /**
     * The logical address under which the gateway shall be reachable
     */
    var logicalAddress by Delegates.notNull<Short>()

    /**
     * The functional address under which the gateway (and other ecus) shall be reachable
     */
    var functionalAddress by Delegates.notNull<Short>()

    /**
     * Vehicle identifier, 17 chars, will be filled with '0`, or if left null, set to 0xFF
     */
    var vin: String? = null // 17 byte VIN

    /**
     * Group ID of the gateway
     */
    var gid: ByteArray = byteArrayOf(0, 0, 0, 0, 0, 0) // 6 byte group identification (used before mac is set)

    /**
     * Entity ID of the gateway
     */
    var eid: ByteArray = byteArrayOf(0, 0, 0, 0, 0, 0) // 6 byte entity identification (usually MAC)

    /**
     * Interval between sending pending NRC messages (0x78)
     */
    var pendingNrcSendInterval: Duration = 2.seconds

    var tlsMode: TlsMode = TlsMode.DISABLED
    var tlsPort: Int = 3496
    var tlsOptions: TlsOptions = TlsOptions()

    private val _ecus: MutableList<EcuData> = mutableListOf()

    val ecus: List<EcuData>
        get() = this._ecus.toList()


    /**
     * Defines an ecu and its properties as behind this gateway
     */
    fun ecu(name: String, receiver: EcuData.() -> Unit) {
        val ecuData = EcuData(name)
        receiver.invoke(ecuData)
        _ecus.add(ecuData)
    }
}

private fun GatewayData.toGatewayConfig(): DoipEntityConfig {
    val config = DoipEntityConfig(
        name = this.name,
        gid = this.gid,
        eid = this.eid,
        localAddress = this.localAddress,
        localPort = this.localPort,
        logicalAddress = this.logicalAddress,
        broadcastEnabled = this.broadcastEnable,
        broadcastAddress = this.broadcastAddress,
        pendingNrcSendInterval = this.pendingNrcSendInterval,
        tlsMode = this.tlsMode,
        tlsPort = this.tlsPort,
        tlsOptions = this.tlsOptions,
        // Fill up too short vin's with 'Z' - if no vin is given, use 0xFF, as defined in ISO 13400 for when no vin is set (yet)
        vin = this.vin?.padEnd(17, 'Z')?.toByteArray() ?: ByteArray(17).let { it.fill(0xFF.toByte()); it },
    )

    // Add the gateway itself as an ecu, so it too can receive requests
    val gatewayEcuConfig = EcuConfig(
        name = this.name,
        physicalAddress = this.logicalAddress,
        functionalAddress = this.functionalAddress,
        pendingNrcSendInterval = this.pendingNrcSendInterval,
    )
    config.ecuConfigList.add(gatewayEcuConfig)

    // Add all the ecus defined for the gateway to the ecuConfigList, so they can later be found and instantiated as SimDslEcu
    config.ecuConfigList.addAll(this.ecus.map { it.toEcuConfig() })
    return config
}

class SimGateway(private val data: GatewayData) : DoipEntity(data.toGatewayConfig()) {
    val requests: List<RequestMatcher>
        get() = data.requests

    override fun createEcu(config: EcuConfig): SimulatedEcu {
        // To be able to handle requests for the gateway itself, insert a dummy ecu with the gateways logicalAddress
        if (config.name == data.name) {
            val ecu = EcuData(
                name = data.name,
                physicalAddress = data.logicalAddress,
                functionalAddress = data.functionalAddress,
                requests = data.requests,
                nrcOnNoMatch =  data.nrcOnNoMatch,
                resetHandler = data.resetHandler,
                requestRegexMatchBytes = data.requestRegexMatchBytes,
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

    override fun findEcuByName(name: String): SimEcu? {
        return super.findEcuByName(name) as SimEcu?
    }

    fun reset(recursiveEcus: Boolean = true) {
        MDC.put("ecu", name)
        logger.infoIf { "Resetting gateway" }
        this.requests.forEach { it.reset() }
        if (recursiveEcus) {
            this.ecus.forEach { (it as SimEcu).reset() }
        }
    }
}

