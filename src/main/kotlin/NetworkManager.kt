import library.DoipEntity
import org.slf4j.LoggerFactory
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

public open class NetworkManager(
    public val config: NetworkingData,
    public val doipEntities: List<DoipEntity<*>>,
) {
    private val log = LoggerFactory.getLogger(NetworkManager::class.java)

    protected open fun findInterfaceByName(): NetworkInterface? {
        var foundInterface: NetworkInterface? = null
        NetworkInterface.getNetworkInterfaces()?.let { netIntf ->
            while (netIntf.hasMoreElements()) {
                val entry = netIntf.nextElement()
                if (entry.displayName != null && entry.displayName.equals(config.networkInterface, true)) {
                    foundInterface = entry
                    break
                }
                entry.subInterfaces?.let { subInterfaces ->
                    while (subInterfaces.hasMoreElements()) {
                        val subInterface = subInterfaces.nextElement()
                        if (subInterface.displayName != null && subInterface.displayName.equals(
                                config.networkInterface,
                                true
                            )
                        ) {
                            foundInterface = entry;
                            break
                        }
                    }
                }
                if (foundInterface != null) {
                    break
                }
            }
        }

        return foundInterface
    }

    protected open fun getAvailableIPAddresses(): Set<InetAddress> {
        if (config.networkInterface.isNullOrBlank() || config.networkInterface == "0.0.0.0") {
            return setOf(InetAddress.getByName("0.0.0.0"))
        }
        val ipAddresses = mutableSetOf<InetAddress>()
        findInterfaceByName()?.let { intf ->
            intf.inetAddresses?.let { inetAddresses ->
                while (inetAddresses.hasMoreElements()) {
                    val address = inetAddresses.nextElement()
                    if (address is Inet4Address) {
                        ipAddresses.add(address)
                    }
                    if (config.networkMode == NetworkMode.SINGLE_IP && ipAddresses.isNotEmpty()) {
                        break
                    }
                }
            }
        }
        if (ipAddresses.isEmpty()) {
            InetAddress.getByName(config.networkInterface)?.let { addr ->
                ipAddresses.add(addr)
            }
        }
        return ipAddresses
    }

    protected open fun buildStartupMap(): Map<String, List<DoipEntity<*>>> {
        val ipAddresses = getAvailableIPAddresses().toMutableList()
        if (ipAddresses.isEmpty()) {
            throw IllegalArgumentException("No network interface with the identifier ${config.networkInterface} could be found")
        }
        log.info("There are ${ipAddresses.size} ip address available, and we have ${doipEntities.size} doip entities")
        val entitiesByIP = mutableMapOf<String, MutableList<DoipEntity<*>>>()
        doipEntities.forEach { entity ->
            val ip = if (ipAddresses.size == 1) {
                ipAddresses.first()
            } else {
                ipAddresses.removeFirst()
            }
            log.info("Assigning entity ${entity.name} to $ip")
            var entityList = entitiesByIP[ip.hostAddress]
            if (entityList == null) {
                entityList = mutableListOf()
                entitiesByIP[ip.hostAddress] = entityList
            }
            entityList.add(entity)
        }
        return entitiesByIP
    }

    public fun start() {
        val map = buildStartupMap()

        // UDP
        map.forEach { (address, entities) ->
            val unb = createUdpNetworkBinding(address, entities)
            unb.start()
        }

        if (config.bindOnAnyForUdpAdditional && !map.containsKey("0.0.0.0")) {
            val unb = createUdpNetworkBindingAny()
            unb.start()
        }

        // TCP
        map.forEach { (address, entities) ->
            val tnb = createTcpNetworkBinding(address, entities)
            tnb.start()
        }
    }

    protected open fun createTcpNetworkBinding(
        address: String,
        entities: List<DoipEntity<*>>
    ): TcpNetworkBinding =
        TcpNetworkBinding(this, address, config.localPort, config.tlsOptions, entities)

    protected open fun createUdpNetworkBinding(
        address: String,
        entities: List<DoipEntity<*>>
    ): UdpNetworkBinding =
        UdpNetworkBinding(address, config.localPort, config.broadcastEnable, config.broadcastAddress, entities)

    protected open fun createUdpNetworkBindingAny(): UdpNetworkBinding =
        UdpNetworkBinding("0.0.0.0", config.localPort, config.broadcastEnable, config.broadcastAddress, doipEntities)
}

