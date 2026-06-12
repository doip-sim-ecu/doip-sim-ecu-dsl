import library.can.LoopbackCanTransport
import library.can.isotp.IsoTpOptions

public typealias CanEcuDataHandler = CanEcuData.() -> Unit
public typealias CanBusDataHandler = CanBusData.() -> Unit

/**
 * Configuration of the raw CAN frame transport used by a [CanBusData]
 */
public sealed class CanTransportConfig

public class SocketCanConfig(
    public val interfaceName: String,
) : CanTransportConfig()

public class SocketcandConfig(
    public val host: String,
    public val port: Int,
    public val busName: String,
    public val reconnect: Boolean,
) : CanTransportConfig()

public class LoopbackConfig(
    public val transport: LoopbackCanTransport,
) : CanTransportConfig()

/**
 * Connect to a SocketCAN network device (e.g. can0, vcan0, vxcan0) by interface name.
 *
 * Linux only; requires the optional `tel.schich:javacan-core` dependency (plus its
 * architecture classifier jar) on the classpath.
 */
public fun socketCan(interfaceName: String): CanTransportConfig {
    try {
        Class.forName("tel.schich.javacan.CanChannels")
    } catch (e: ClassNotFoundException) {
        throw IllegalStateException(
            "SocketCAN support requires the optional dependency 'tel.schich:javacan-core' " +
                "(and its architecture classifier jar, e.g. classifier 'x86_64') on the classpath",
            e
        )
    }
    return SocketCanConfig(interfaceName)
}

/**
 * Connect to a CAN bus through a socketcand daemon (rawmode) over TCP
 */
public fun socketcand(host: String, port: Int = 29536, bus: String, reconnect: Boolean = true): CanTransportConfig =
    SocketcandConfig(host = host, port = port, busName = bus, reconnect = reconnect)

/**
 * In-memory CAN bus, mainly useful for tests - a tester can be attached to the
 * same [LoopbackCanTransport] instance
 */
public fun loopback(transport: LoopbackCanTransport = LoopbackCanTransport()): CanTransportConfig =
    LoopbackConfig(transport)

/**
 * Defines a CAN bus with ECUs reachable via UDS over ISO-TP (ISO 15765-2)
 */
public class CanBusData(public val name: String) : MultiTransportTarget {
    /**
     * The CAN frame transport for this bus (see [socketCan], [socketcand], [loopback])
     */
    public var transport: CanTransportConfig? = null

    /**
     * Use CAN FD framing (payloads up to 64 bytes per frame); requires a transport
     * that supports FD frames (SocketCAN, not socketcand)
     */
    public var fd: Boolean = false

    /**
     * Maximum payload length of transmitted frames: 8 for classic CAN, up to 64 with [fd]
     */
    public var txDlc: Int = 8

    /**
     * Byte used to pad transmitted frames to their full length (null = no padding)
     */
    public var padding: Byte? = 0xCC.toByte()

    /**
     * CAN id for functional (broadcast) requests, e.g. 0x7DF (null = no functional addressing)
     */
    public var functionalRequestId: Int? = null

    internal var isoTpHandler: (IsoTpOptions.() -> Unit)? = null

    internal val _ecus: MutableList<CanEcuData> = mutableListOf()

    public val ecus: List<CanEcuData>
        get() = _ecus

    /**
     * Bus-wide defaults for ISO-TP parameters (overridable per ECU)
     */
    public fun isoTp(receiver: IsoTpOptions.() -> Unit) {
        isoTpHandler = receiver
    }

    /**
     * Defines an ECU on this CAN bus and its request/response behaviour
     */
    public fun ecu(name: String, receiver: CanEcuDataHandler) {
        val ecuData = CanEcuData(name)
        receiver.invoke(ecuData)
        _ecus.add(ecuData)
    }

    internal fun addEcu(ecuData: CanEcuData) {
        _ecus.add(ecuData)
    }
}

/**
 * The data associated with an ECU on a CAN bus; reuses the request-matcher DSL
 * of [EcuData] but is addressed by CAN ids instead of DoIP logical addresses
 */
public class CanEcuData(name: String) : EcuData(name) {
    /**
     * CAN id the ECU receives physical requests on (e.g. 0x712)
     */
    public var requestId: Int = -1

    /**
     * CAN id the ECU sends its responses on (e.g. 0x7A2)
     */
    public var responseId: Int = -1

    /**
     * Whether this ECU also listens to the bus' functional request id
     */
    public var useFunctionalAddressing: Boolean = true

    internal var isoTpHandler: (IsoTpOptions.() -> Unit)? = null

    /**
     * Per-ECU overrides for ISO-TP parameters (e.g. blockSize, stMin)
     */
    public fun isoTp(receiver: IsoTpOptions.() -> Unit) {
        isoTpHandler = receiver
    }
}
