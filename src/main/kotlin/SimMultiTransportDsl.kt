public typealias MultiTransportDataHandler = MultiTransportData.() -> Unit

/**
 * A transport container an ECU can be attached to via [multiTransport]:
 * [DoipEntityData] (gateway/doip entity) or [CanBusData]
 */
public sealed interface MultiTransportTarget

/**
 * DSL scope for defining ECUs that are attached to multiple transports at once
 */
public class MultiTransportData internal constructor(
    private val targets: List<MultiTransportTarget>,
) {
    /**
     * Defines an ECU that is reachable on all targets of this block. The ECU is a
     * single instance: stored properties, timers, interceptors and the busy-state
     * are shared across the transports.
     *
     * DoIP addressing ([CanEcuData.logicalAddress]/[CanEcuData.functionalAddress])
     * and CAN addressing ([CanEcuData.requestId]/[CanEcuData.responseId]) must
     * both be set when the respective transport is among the targets.
     */
    public fun ecu(name: String, receiver: CanEcuDataHandler) {
        val ecuData = CanEcuData(name)
        receiver.invoke(ecuData)
        targets.forEach {
            when (it) {
                is DoipEntityData -> it.addEcu(ecuData)
                is CanBusData -> it.addEcu(ecuData)
            }
        }
    }
}

/**
 * Attaches the ECUs defined in [receiver] to all of the given [targets]
 * (DoIP entities and/or CAN buses), e.g.:
 *
 * ```
 * network {
 *     multiTransport(
 *         gateway("GW") { logicalAddress = 0x1010 },
 *         canBus("BUS1") { transport = socketCan("vcan0") },
 *     ) {
 *         ecu("ECU1") {
 *             logicalAddress = 0x1011
 *             requestId = 0x712
 *             responseId = 0x7A2
 *             request("22 F1 86") { respond("62 F1 86 01") }
 *         }
 *     }
 * }
 * ```
 */
@Suppress("unused")
public fun NetworkingData.multiTransport(vararg targets: MultiTransportTarget, receiver: MultiTransportDataHandler) {
    require(targets.isNotEmpty()) {
        "multiTransport requires at least one target"
    }
    require(targets.count { it is DoipEntityData } <= 1) {
        "multiTransport supports at most one DoIP entity per block"
    }
    require(targets.distinct().size == targets.size) {
        "multiTransport targets must be distinct"
    }
    receiver.invoke(MultiTransportData(targets.toList()))
}
