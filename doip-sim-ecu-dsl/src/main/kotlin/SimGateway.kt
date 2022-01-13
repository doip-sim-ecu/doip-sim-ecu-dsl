import doip.library.comm.DoipTcpConnection
import doip.library.comm.DoipTcpStreamBuffer
import doip.simulation.nodes.Ecu
import doip.simulation.nodes.EcuConfig
import doip.simulation.nodes.GatewayConfig
import doip.simulation.standard.StandardGateway
import doip.simulation.standard.StandardTcpConnectionGateway
import java.net.InetAddress
import kotlin.properties.Delegates

open class GatewayData(val name: String) : RequestsData() {
    /**
     * Network address this gateway should bind on (default: 0.0.0.0)
     */
    var localAddress: InetAddress = InetAddress.getByName("0.0.0.0")

    /**
     * Network port this gateway should bind on (default: 13400)
     */
    var localPort: Int = 13400

    /**
     * Default broadcast address for VAM messages (default: 255.255.255.255)
     */
    var broadcastAddress: InetAddress = InetAddress.getByName("255.255.255.255")

    /**
     * Whether VAM broadcasts shall be sent on startup (default: true)
     */
    var broadcastEnable: Boolean = true

    /**
     * The logical address under which the gateway shall be reachable
     */
    var logicalAddress by Delegates.notNull<Int>()

    /**
     * The functional address under which the gateway (and other ecus) shall be reachable
     */
    var functionalAddress by Delegates.notNull<Int>()


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

private fun GatewayData.toGatewayConfig(): GatewayConfig {
    val config = GatewayConfig()
    config.name = this.name
    config.gid = this.gid
    config.eid = this.eid
    config.localAddress = this.localAddress
    config.localPort = this.localPort
    config.logicalAddress = this.logicalAddress
    config.broadcastAddress = this.broadcastAddress
    config.broadcastEnable = this.broadcastEnable
    // Fill up too short vin's with 'Z' - if no vin is given, use 0xFF, as defined in ISO 13400 for when no vin is set (yet)
    config.vin = this.vin?.padEnd(17, 'Z')?.toByteArray() ?: ByteArray(17).let { it.fill(0xFF.toByte()); it }

    // Add the gateway itself as an ecu, so it too can receive requests
    val gateway = EcuConfig()
    gateway.name = this.name
    gateway.physicalAddress = this.logicalAddress
    gateway.functionalAddress = this.functionalAddress
    config.ecuConfigList.add(gateway)

    // Add all the ecus defined for the gateway to the ecuConfigList, so they can later be found and instantiated as SimDslEcu
    config.ecuConfigList.addAll(this.ecus.map { it.toEcuConfig() })
    return config
}

class SimGateway(private val data: GatewayData) : StandardGateway(data.toGatewayConfig()) {
    private val logger = doip.logging.LogManager.getLogger(SimGateway::class.java)

    val name: String
        get() = data.name

    val requests: List<RequestMatcher>
        get() = data.requests

    override fun createConnection(): StandardTcpConnectionGateway {
        // Hacky way to increase stream buffer size -- there should be a setter in the connection
        val con = super.createConnection()
        val streamBufferField = DoipTcpConnection::class.java.getDeclaredField("streamBuffer")
        streamBufferField.isAccessible = true
        val streamBuffer = streamBufferField.get(con) as DoipTcpStreamBuffer
        streamBuffer.maxPayloadLength = 70000
        return con
    }

    override fun createEcu(config: EcuConfig): Ecu {
        // To be able to handle requests for the gateway itself, insert a dummy ecu with the gateways logicalAddress
        if (config.name == data.name) {
            val ecu = EcuData(
                name = data.name,
                physicalAddress = data.logicalAddress,
                functionalAddress = data.functionalAddress,
                requests = data.requests,
            )
            return SimEcu(ecu)
        }

        // Match the other ecus by name, and create an SimDslEcu for them, since StandardEcu can't handle our
        // requirements for handling requests
        val ecuData = data.ecus.first { it.name == config.name }
        return SimEcu(ecuData)
    }

    fun reset(recursiveEcus: Boolean = true) {
        logger.info("Resetting Gateway $name")
        this.requests.forEach { it.reset() }
        if (recursiveEcus) {
            this.ecuList.forEach { (it as SimEcu).reset() }
        }
    }
}

