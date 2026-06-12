import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.slf4j.MDCContext
import library.DoipEntityHardResetException
import library.UdsMessage
import library.can.CanFrame
import library.can.CanTransport
import library.can.CanUdsMessage
import library.can.isotp.IsoTpEndpoint
import library.can.isotp.IsoTpOptions
import library.toHexString
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/**
 * Runtime instance for a [CanBusData]: owns the [CanTransport], one [SimEcu] and
 * [IsoTpEndpoint] per defined ECU, and dispatches reassembled UDS requests
 */
public open class CanBusBinding(
    public val data: CanBusData,
    /**
     * ECU instances shared with other transports (multiTransport), keyed by the
     * identity of their [library.EcuConfig]-source [EcuData]; an ECU whose data is
     * already in this map is adopted instead of instantiated a second time
     */
    private val sharedEcuInstances: MutableMap<EcuData, SimEcu> = mutableMapOf(),
) {
    private val logger = LoggerFactory.getLogger(CanBusBinding::class.java)

    public val name: String
        get() = data.name

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var transport: CanTransport? = null
    private val endpoints: MutableList<IsoTpEndpoint> = mutableListOf()

    private val _ecus: MutableList<SimEcu> = mutableListOf()
    private val ownedEcus: MutableList<SimEcu> = mutableListOf()

    public val ecus: List<SimEcu>
        get() = _ecus

    /**
     * Creates the simulated ECUs (doesn't open the transport yet, see [startNetworking])
     */
    public fun start() {
        _ecus.clear()
        ownedEcus.clear()
        data.ecus.forEach { ecuData ->
            val existing = sharedEcuInstances[ecuData]
            if (existing != null) {
                // dual-homed ECU - the instance (and its state) is shared with another transport
                _ecus.add(existing)
            } else {
                val simEcu = SimEcu(ecuData)
                sharedEcuInstances[ecuData] = simEcu
                ownedEcus.add(simEcu)
                _ecus.add(simEcu)
                simEcu.simStarted()
            }
        }
    }

    /**
     * Validates the configuration, opens the transport and attaches the ISO-TP endpoints
     */
    public fun startNetworking() {
        val transportConfig = data.transport
            ?: throw IllegalArgumentException("No transport configured for CAN bus '$name'")
        validate()

        val transport = createTransport(transportConfig)
        if (data.fd && !transport.supportsFd) {
            transport.close()
            throw IllegalArgumentException("CAN bus '$name' is configured with fd=true, but transport '${transport.name}' doesn't support CAN FD")
        }
        this.transport = transport

        // attach the endpoints before connecting, so no frame received right after
        // connecting can be lost
        data.ecus.zip(_ecus).forEach { (ecuData, simEcu) ->
            val options = effectiveIsoTpOptions(ecuData)
            val functionalRxId = if (ecuData.useFunctionalAddressing) data.functionalRequestId else null
            lateinit var endpoint: IsoTpEndpoint
            endpoint = IsoTpEndpoint(
                transport = transport,
                physicalRxId = ecuData.requestId,
                functionalRxId = functionalRxId,
                txId = ecuData.responseId,
                options = options,
            ) { payload, functional ->
                dispatchRequest(simEcu, ecuData, endpoint, payload, functional)
            }
            endpoint.start(scope)
            endpoints.add(endpoint)
            logger.info("ECU '${simEcu.name}' listening on 0x${ecuData.requestId.toString(16)} (responding on 0x${ecuData.responseId.toString(16)})")
        }

        runBlocking {
            transport.start(scope)
        }
        logger.info("CAN bus '$name' connected via ${transport.name}")
    }

    public fun stop() {
        endpoints.forEach { it.stop() }
        endpoints.clear()
        transport?.close()
        transport = null
        scope.cancel()
    }

    public open fun reset() {
        // adopted (shared) ECUs are reset by their owning transport
        ownedEcus.forEach { it.reset() }
    }

    public fun findEcuByName(ecuName: String, ignoreCase: Boolean = true): SimEcu? =
        _ecus.firstOrNull { ecuName.equals(it.name, ignoreCase) }

    private fun dispatchRequest(
        simEcu: SimEcu,
        ecuData: CanEcuData,
        endpoint: IsoTpEndpoint,
        payload: ByteArray,
        functional: Boolean,
    ) {
        val request = CanUdsMessage(
            targetAddressType = if (functional) UdsMessage.FUNCTIONAL else UdsMessage.PHYSICAL,
            message = payload,
            output = endpoint.outputChannel,
            requestCanId = if (functional) data.functionalRequestId!! else ecuData.requestId,
            responseCanId = ecuData.responseId,
        )
        MDC.put("ecu", simEcu.name)
        scope.launch(MDCContext()) {
            try {
                simEcu.onIncomingUdsMessage(request)
            } catch (e: DoipEntityHardResetException) {
                logger.warn("hardResetEntityFor isn't supported on CAN (ECU '${simEcu.name}')")
            } catch (e: Exception) {
                logger.error(
                    "Error while handling request '${payload.toHexString(limit = 10, limitExceededByteCount = true)}' for ECU '${simEcu.name}'",
                    e
                )
            }
        }
    }

    private fun effectiveIsoTpOptions(ecuData: CanEcuData): IsoTpOptions {
        val options = IsoTpOptions(
            fd = data.fd,
            txDlc = data.txDlc,
            paddingByte = data.padding,
        )
        data.isoTpHandler?.invoke(options)
        ecuData.isoTpHandler?.invoke(options)
        return options
    }

    private fun validate() {
        data.ecus.forEach {
            require(it.requestId in 0..CanFrame.MAX_STANDARD_ID) {
                "ECU '${it.name}' on CAN bus '$name' has no valid requestId (11-bit CAN id required)"
            }
            require(it.responseId in 0..CanFrame.MAX_STANDARD_ID) {
                "ECU '${it.name}' on CAN bus '$name' has no valid responseId (11-bit CAN id required)"
            }
        }
        val duplicateRxIds = data.ecus.groupBy { it.requestId }.filterValues { it.size > 1 }
        require(duplicateRxIds.isEmpty()) {
            "CAN bus '$name' has multiple ECUs with the same requestId: ${duplicateRxIds.keys.map { "0x${it.toString(16)}" }}"
        }
        data.functionalRequestId?.let { functionalId ->
            require(functionalId in 0..CanFrame.MAX_STANDARD_ID) {
                "CAN bus '$name' has an invalid functionalRequestId (11-bit CAN id required)"
            }
            require(data.ecus.none { it.requestId == functionalId }) {
                "CAN bus '$name': functionalRequestId 0x${functionalId.toString(16)} collides with an ECU requestId"
            }
        }
        require(data.txDlc == 8 || (data.fd && library.can.CanDlc.isValidLength(data.txDlc) && data.txDlc > 8)) {
            "CAN bus '$name' has an invalid txDlc ${data.txDlc} (8 for classic CAN; 12, 16, 20, 24, 32, 48 or 64 with fd=true)"
        }
    }

    protected open fun createTransport(config: CanTransportConfig): CanTransport =
        when (config) {
            is LoopbackConfig -> config.transport
            is SocketcandConfig -> library.can.socketcand.SocketcandTransport(
                host = config.host,
                port = config.port,
                busName = config.busName,
                reconnect = config.reconnect,
            )
            is SocketCanConfig -> library.can.socketcan.SocketCanTransport(config.interfaceName)
        }
}
