import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.slf4j.MDCContext
import library.*
import org.slf4j.MDC
import kotlin.properties.Delegates
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Suppress("unused")
public open class GatewayData(name: String) : RequestsData(name) {
    /**
     * Network address this gateway should bind on (default: 0.0.0.0)
     */
    public var localAddress: String = "0.0.0.0"

    /**
     * Should udp be bound additionally on any?
     * There's an issue when binding it to an network interface of not receiving 255.255.255.255 broadcasts
     */
    public var bindOnAnyForUdpAdditional: Boolean = true

    /**
     * Network port this gateway should bind on (default: 13400)
     */
    public var localPort: Int = 13400

    /**
     * Multicast address
     */
    public var multicastAddress: String? = null

    /**
     * Whether VAM broadcasts shall be sent on startup (default: true)
     */
    public var broadcastEnable: Boolean = true

    /**
     * Default broadcast address for VAM messages (default: 255.255.255.255)
     */
    public var broadcastAddress: String = "255.255.255.255"

    /**
     * The logical address under which the gateway shall be reachable
     */
    public var logicalAddress: Short by Delegates.notNull()

    /**
     * The functional address under which the gateway (and other ecus) shall be reachable
     */
    public var functionalAddress: Short by Delegates.notNull()

    /**
     * Vehicle identifier, 17 chars, will be filled with '0`, or if left null, set to 0xFF
     */
    public  var vin: String? = null // 17 byte VIN

    /**
     * Group ID of the gateway
     */
    public var gid: ByteArray = byteArrayOf(0, 0, 0, 0, 0, 0) // 6 byte group identification (used before mac is set)

    /**
     * Entity ID of the gateway
     */
    public var eid: ByteArray = byteArrayOf(0, 0, 0, 0, 0, 0) // 6 byte entity identification (usually MAC)

    /**
     * Interval between sending pending NRC messages (0x78)
     */
    public var pendingNrcSendInterval: Duration = 2.seconds

    /**
     * Maximum payload data size allowed for a DoIP message
     */
    public var maxDataSize: Int = Int.MAX_VALUE

    public var tlsMode: TlsMode = TlsMode.DISABLED
    public var tlsPort: Int = 3496
    public var tlsOptions: TlsOptions = TlsOptions()

    private val _ecus: MutableList<EcuData> = mutableListOf()
    private val _additionalVams: MutableList<DoipUdpVehicleAnnouncementMessage> = mutableListOf()

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

    public fun doipEntity(name: String, vam: DoipUdpVehicleAnnouncementMessage, receiver: EcuData.() -> Unit) {
        val ecuData = EcuData(name)
        receiver.invoke(ecuData)
        _ecus.add(ecuData)
        _additionalVams.add(vam)
    }
}

private fun GatewayData.toGatewayConfig(): DoipEntityConfig {
    val config = DoipEntityConfig(
        name = this.name,
        gid = this.gid,
        eid = this.eid,
        localAddress = this.localAddress,
        bindOnAnyForUdpAdditional = this.bindOnAnyForUdpAdditional,
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
        maxDataSize = this.maxDataSize,
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

public class SimGateway(private val data: GatewayData) : DoipEntity(data.toGatewayConfig()) {
    public val requests: List<RequestMatcher>
        get() = data.requests

    override fun createEcu(config: EcuConfig): SimulatedEcu {
        // To be able to handle requests for the gateway itself, insert a dummy ecu with the gateways logicalAddress
        if (config.name == data.name) {
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

    override fun findEcuByName(name: String): SimEcu? {
        return super.findEcuByName(name) as SimEcu?
    }

    public fun reset(recursiveEcus: Boolean = true) {
        runBlocking {
            MDC.put("ecu", name)

            launch(MDCContext()) {
                logger.infoIf { "Resetting gateway" }
                requests.forEach { it.reset() }
                if (recursiveEcus) {
                    ecus.forEach { (it as SimEcu).reset() }
                }
            }
        }
    }
}

