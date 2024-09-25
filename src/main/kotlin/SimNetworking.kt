import library.*

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

    /**
     * Defines a DoIP-Gateway and the ECUs behind it
     */
    public fun gateway(name: String, receiver: DoipEntityDataHandler) {
        val gatewayData = DoipEntityData(name, DoipNodeType.GATEWAY)
        receiver.invoke(gatewayData)
        _doipEntities.add(gatewayData)
    }

    /**
     * Defines a DoIP-Gateway and the ECUs behind it
     */
    public fun doipEntity(name: String, receiver: DoipEntityDataHandler) {
        val gatewayData = DoipEntityData(name, DoipNodeType.NODE)
        receiver.invoke(gatewayData)
        _doipEntities.add(gatewayData)
    }

}

public open class SimDoipNetworking(data: NetworkingData) : SimNetworking<SimEcu, SimDoipEntity>(data) {
    override fun createDoipEntity(data: DoipEntityData): SimDoipEntity =
        SimDoipEntity(data)
}

public abstract class SimNetworking<E : SimulatedEcu, out T : DoipEntity<E>>(public val data: NetworkingData) {
    public val doipEntities: List<T>
        get() = _doipEntities

    private val _doipEntities: MutableList<T> = mutableListOf()
    protected val _vams: MutableList<DoipUdpVehicleAnnouncementMessage> = mutableListOf()

    protected fun addEntity(doipEntity: @UnsafeVariance T) {
        _doipEntities.add(doipEntity)
    }

    protected abstract fun createDoipEntity(data: DoipEntityData): T

    init {
        start()
    }

    private fun start() {
        _doipEntities.clear()

        data._doipEntities.map { createDoipEntity(it) }.forEach {
            it.start()
            _doipEntities.add(it)
        }

        _doipEntities.forEach {
            it.start()
        }
    }

    public open fun reset() {
        _doipEntities.forEach { it.reset() }
    }

    public open fun findEcuByName(ecuName: String, ignoreCase: Boolean = true): E? =
        _doipEntities.flatMap { it.ecus }.firstOrNull { ecuName.equals(it.name, ignoreCase) }
}

