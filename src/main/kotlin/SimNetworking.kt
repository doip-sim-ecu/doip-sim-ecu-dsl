import library.*
import org.slf4j.LoggerFactory
import java.util.IdentityHashMap

public enum class NetworkMode {
    AUTO,
    SINGLE_IP,
}

@Suppress("unused")
public open class NetworkingData {
    /**
     * The network interface that should be used to bind on, can be an IP, or name
     */
    public var networkInterface: String? = "0.0.0.0"

    /**
     * Mode for assigning ip addresses to doip entities
     */
    public var networkMode: NetworkMode = NetworkMode.AUTO

    /**
     * Should udp be bound additionally on any?
     * There's an issue when binding it to a network interface with not receiving 255.255.255.255 broadcasts
     */
    public var bindOnAnyForUdpAdditional: Boolean = true

    /**
     * Network port this gateway should bind on (default: 13400)
     */
    public var localPort: Int = 13400

    /**
     * Whether VAM broadcasts shall be sent on startup (default: true)
     */
    public var broadcastEnable: Boolean = true

    /**
     * Default broadcast address for VAM messages (default: 255.255.255.255)
     */
    public var broadcastAddress: String = "255.255.255.255"

    public val tlsOptions: TlsOptions = TlsOptions()

    internal val _doipEntities: MutableList<DoipEntityData> = mutableListOf()

    public val doipEntities: List<DoipEntityData>
        get() = _doipEntities

    internal val _canBuses: MutableList<CanBusData> = mutableListOf()

    public val canBuses: List<CanBusData>
        get() = _canBuses

    /**
     * Defines a DoIP-Gateway and the ECUs behind it
     */
    public fun gateway(name: String, receiver: DoipEntityDataHandler): DoipEntityData {
        val gatewayData = DoipEntityData(name, DoipNodeType.GATEWAY)
        receiver.invoke(gatewayData)
        _doipEntities.add(gatewayData)
        return gatewayData
    }

    /**
     * Defines a DoIP-Gateway and the ECUs behind it
     */
    public fun doipEntity(name: String, receiver: DoipEntityDataHandler): DoipEntityData {
        val gatewayData = DoipEntityData(name, DoipNodeType.NODE)
        receiver.invoke(gatewayData)
        _doipEntities.add(gatewayData)
        return gatewayData
    }

    /**
     * Defines a CAN bus and the ECUs on it, reachable via UDS over ISO-TP
     */
    public fun canBus(name: String, receiver: CanBusDataHandler): CanBusData {
        val canBusData = CanBusData(name)
        receiver.invoke(canBusData)
        _canBuses.add(canBusData)
        return canBusData
    }
}

public open class SimDoipNetworking(data: NetworkingData) : SimNetworking<SimEcu, SimDoipEntity>(data) {
    override fun createDoipEntity(data: DoipEntityData): SimDoipEntity =
        SimDoipEntity(data, sharedEcuInstances)
}

public abstract class SimNetworking<E : SimulatedEcu, out T : DoipEntity<E>>(public val data: NetworkingData) {
    private val log = LoggerFactory.getLogger(SimNetworking::class.java)

    public val doipEntities: List<T>
        get() {
            if (data.doipEntities.isNotEmpty() && _doipEntities.isEmpty()) {
                synchronized(startLock) {
                    if (data.doipEntities.isNotEmpty() && _doipEntities.isEmpty()) {
                        start()
                    }
                }
            }
            return _doipEntities
        }

    private val startLock = Any()
    private val _doipEntities: MutableList<T> = mutableListOf()
    protected val _vams: MutableList<DoipUdpVehicleAnnouncementMessage> = mutableListOf()

    public val canBusBindings: List<CanBusBinding>
        get() {
            if (data.canBuses.isNotEmpty() && _canBusBindings.isEmpty()) {
                start()
            }
            return _canBusBindings
        }

    private val _canBusBindings: MutableList<CanBusBinding> = mutableListOf()

    /**
     * One instance per [EcuData] object across all transports - an EcuData attached
     * to multiple transports (multiTransport) results in a single shared [SimEcu]
     */
    protected val sharedEcuInstances: MutableMap<EcuData, SimEcu> = IdentityHashMap()

    protected fun addEntity(doipEntity: @UnsafeVariance T) {
        if (_doipEntities.any { it.config.logicalAddress == doipEntity.config.logicalAddress }) {
            log.error("Can't add '${doipEntity.name}' - entity with same logical address already exists")
            return
        }
        _doipEntities.add(doipEntity)
    }

    protected abstract fun createDoipEntity(data: DoipEntityData): T

    public open fun start(): Unit = synchronized(startLock) {
        sharedEcuInstances.clear()

        _doipEntities.clear()

        data._doipEntities.map { createDoipEntity(it) }.forEach {
            _doipEntities.add(it)
        }

        // entities first: they create (and own) the SimEcus, which the CAN bus
        // bindings then adopt for ECUs attached to both transports (multiTransport)
        _doipEntities.forEach {
            it.start()
        }

        _canBusBindings.clear()

        data._canBuses.map { CanBusBinding(it, sharedEcuInstances) }.forEach {
            _canBusBindings.add(it)
        }

        _canBusBindings.forEach {
            it.start()
        }
    }

    public open fun reset() {
        _doipEntities.forEach { it.reset() }
        _canBusBindings.forEach { it.reset() }
    }

    public open fun findEcuByName(ecuName: String, ignoreCase: Boolean = true): E? =
        _doipEntities.flatMap { it.ecus }.firstOrNull { ecuName.equals(it.name, ignoreCase) }

    /**
     * Finds an ECU defined on one of the CAN buses by its name
     */
    public fun findCanEcuByName(ecuName: String, ignoreCase: Boolean = true): SimEcu? =
        _canBusBindings.firstNotNullOfOrNull { it.findEcuByName(ecuName, ignoreCase) }
}

