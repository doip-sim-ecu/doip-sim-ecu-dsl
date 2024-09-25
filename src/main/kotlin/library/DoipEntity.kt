package library

import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.slf4j.MDCContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.io.File
import kotlin.time.Duration.Companion.seconds

public typealias GID = ByteArray
public typealias EID = ByteArray
public typealias VIN = ByteArray


public enum class DoipNodeType(public val value: Byte) {
    GATEWAY(0),
    NODE(1)
}

@Suppress("unused")
public enum class TlsMode {
    DISABLED,
    OPTIONAL,
    MANDATORY,
}

public data class TlsOptions(
    public val tlsMode: TlsMode = TlsMode.DISABLED,
    public val tlsPort: Int = 3496,
    public val tlsCert: File? = null,
    public val tlsKey: File? = null,
    public val tlsKeyPassword: String? = null,
    public val tlsCiphers: List<String>? = DefaultTlsCiphers,
    public val tlsProtocols: List<String>? = DefaultTlsProtocols,
)

@Suppress("unused")
public open class DoipEntityConfig(
    public val name: String,
    public val logicalAddress: Short,
    public val gid: GID,
    public val eid: EID,
    public val vin: VIN,
    public val maxDataSize: Int = Int.MAX_VALUE,
    public val pendingNrcSendInterval: kotlin.time.Duration = 2.seconds,
    public val ecuConfigList: MutableList<EcuConfig> = mutableListOf(),
    public val nodeType: DoipNodeType = DoipNodeType.GATEWAY,
) {
    init {
        if (name.isEmpty()) {
            throw IllegalArgumentException("name must be not empty")
        }
        if (gid.size != 6) {
            throw IllegalArgumentException("gid must be 6 bytes")
        }
        if (eid.size != 6) {
            throw IllegalArgumentException("eid must be 6 bytes")
        }
        if (vin.size != 17) {
            throw IllegalArgumentException("vin must be 17 bytes")
        }
    }
}

/**
 * DoIP-Entity
 */
@Suppress("MemberVisibilityCanBePrivate")
public abstract class DoipEntity<out T : SimulatedEcu>(
    public val config: DoipEntityConfig,
) : DiagnosticMessageHandler {
    public val name: String =
        config.name

    protected val logger: Logger = LoggerFactory.getLogger(DoipEntity::class.java)

    protected var targetEcusByLogical: Map<Short, @UnsafeVariance T> = emptyMap()
    protected var targetEcusByFunctional: MutableMap<Short, MutableList<@UnsafeVariance T>> = mutableMapOf()

    public val connectionHandlers: MutableList<DoipTcpConnectionMessageHandler> = mutableListOf()

    private val _ecus: MutableList<T> = mutableListOf()

    public val ecus: List<T>
        get() = _ecus

    protected abstract fun createEcu(config: EcuConfig): T
    public abstract fun reset(recursiveEcus: Boolean = true)

    override fun existsTargetAddress(targetAddress: Short): Boolean =
        targetEcusByLogical.containsKey(targetAddress) || targetEcusByFunctional.containsKey(targetAddress)

    public fun generateVehicleAnnouncementMessages(): List<DoipUdpVehicleAnnouncementMessage> =
        config.let {
            listOf(DoipUdpVehicleAnnouncementMessage(it.vin, it.logicalAddress, it.gid, it.eid))
        }

    public open fun createDoipUdpMessageHandler(): DoipUdpMessageHandler =
        DefaultDoipEntityUdpMessageHandler(
            doipEntity = this,
            config = config
        )

    public open fun createDoipTcpMessageHandler(socket: DoipTcpSocket, tlsOptions: TlsOptions?): DoipTcpConnectionMessageHandler =
        DefaultDoipEntityTcpConnectionMessageHandler(
            doipEntity = this,
            socket = socket,
            logicalAddress = config.logicalAddress,
            diagMessageHandler = this,
            tlsOptions = tlsOptions,
        )

    protected open suspend fun sendResponse(request: DoipTcpDiagMessage, output: ByteWriteChannel, data: ByteArray) {
        if (data.isEmpty()) {
            return
        }
        val response = DoipTcpDiagMessage(
            sourceAddress = request.targetAddress,
            targetAddress = request.sourceAddress,
            payload = data
        )
        output.writeFully(response.asByteArray)
    }

    override suspend fun onIncomingDiagMessage(diagMessage: DoipTcpDiagMessage, output: ByteWriteChannel) {
        val ecu = targetEcusByLogical[diagMessage.targetAddress]
        ecu?.run {
            runBlocking {
                MDC.put("ecu", ecu.name)
                launch(MDCContext()) {
                    onIncomingUdsMessage(diagMessage.toUdsMessage(UdsMessage.PHYSICAL, output, ecu.config.logicalAddress))
                }
            }
            // Exit if the target ecu was found by physical
            return
        }

        val ecus = targetEcusByFunctional[diagMessage.targetAddress]
        ecus?.forEach {
            runBlocking {
                MDC.put("ecu", it.name)
                launch(MDCContext()) {
                    it.onIncomingUdsMessage(diagMessage.toUdsMessage(UdsMessage.FUNCTIONAL, output, it.config.logicalAddress))
                }
            }
        }
    }

    public open fun findEcuByName(name: String, ignoreCase: Boolean = true): T? =
        this.ecus.firstOrNull { name.equals(it.name, ignoreCase = ignoreCase) }

    public fun start() {
        this._ecus.addAll(this.config.ecuConfigList.map { createEcu(it) })

        targetEcusByLogical = this.ecus.associateBy { it.config.logicalAddress }
        targetEcusByFunctional = _ecus.groupByTo(mutableMapOf()) { it.config.functionalAddress }

        _ecus.forEach {
            it.simStarted()
        }
    }
}
