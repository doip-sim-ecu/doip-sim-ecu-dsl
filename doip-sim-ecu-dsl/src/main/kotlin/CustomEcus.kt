import doip.library.message.UdsMessage
import doip.simulation.nodes.Ecu
import doip.simulation.nodes.EcuConfig
import doip.simulation.nodes.GatewayConfig
import doip.simulation.standard.StandardEcu
import doip.simulation.standard.StandardGateway
import helper.EcuTimerTask
import helper.Open
import helper.scheduleEcuTimerTask
import helper.toHexString
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

fun GatewayData.toGatewayConfig(): GatewayConfig {
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

fun EcuData.toEcuConfig(): EcuConfig {
    val config = EcuConfig()
    config.name = this.name
    config.physicalAddress = this.physicalAddress
    config.functionalAddress = this.functionalAddress
    return config
}

class SimDslGateway(private val data: GatewayData) : StandardGateway(data.toGatewayConfig()) {
    override fun createEcu(config: EcuConfig): Ecu {
        // To be able to handle requests for the gateway itself, insert a dummy ecu with the gateways logicalAddress
        if (config.name == data.name) {
            val ecu = EcuData(data.name)
            ecu.physicalAddress = data.logicalAddress
            ecu.functionalAddress = data.functionalAddress
            ecu.requests = data.requests
            return SimDslEcu(ecu)
        }

        // Match the other ecus by name, and create an SimDslEcu for them, since StandardEcu can't handle our
        // requirements for handling requests
        val ecuData = data.ecus.first { it.name == config.name }
        return SimDslEcu(ecuData)
    }
}

/**
 * Extension function to provide the address by the targetAddressType provided in the UdsMessage
 */
fun EcuConfig.addressByType(message: UdsMessage): Int =
    when (message.targetAddressType) {
        UdsMessage.PHYSICAL -> this.physicalAddress
        UdsMessage.FUNCTIONAL -> this.functionalAddress
        else -> throw IllegalStateException("Unknown targetAddressType ${message.targetAddressType}")
    }

class InterceptorWrapper(val name: String, val interceptor: Interceptor, val isExpired: () -> Boolean)

@Open
class SimDslEcu(private val data: EcuData) : StandardEcu(data.toEcuConfig()) {
    private val logger = doip.logging.LogManager.getLogger(SimDslEcu::class.java)

    val name
        get() = data.name

    private val interceptors = Collections.synchronizedMap(LinkedHashMap<String, InterceptorWrapper>())

    private val mainTimer: Timer by lazy { Timer("$name-Timer", true) }
    private val timers = ConcurrentHashMap<String, EcuTimerTask>()


    fun sendResponse(request: UdsMessage, response: ByteArray) {
        val sourceAddress = config.addressByType(request)

        val udsResponse = UdsMessage(
            sourceAddress,
            request.sourceAdrress,
            request.targetAddressType,
            response
        )

        clearCurrentRequest()
        onSendUdsMessage(udsResponse)
    }

    private fun handleInterceptors(request: UdsMessage): Boolean {
        if (this.interceptors.isEmpty()) {
            return false
        }
        this.interceptors
            .filterValues { it.isExpired() }
            .forEach {
                this.interceptors.remove(it.key)
            }

        this.interceptors.forEach {
            if (it.value.isExpired()) {
                this.interceptors.remove(it.key)
            } else {
                val responseData = ResponseData(request = request, simEcu = this)
                if (it.value.interceptor.invoke(responseData, request)) {
                    if (responseData.continueMatching) {
                        return false
                    } else if (responseData.response.isNotEmpty()) {
                        sendResponse(request, responseData.response)
                    }
                    return true
                }
            }

        }

        return false
    }

    /**
     * Normalizes an incoming request into hexadecimal
     */
    override fun handleRequest(request: UdsMessage) {
        if (handleInterceptors(request)) {
            return
        }

        val normalizedRequest by lazy { request.message.toHexString("", limit = data.requestRegexMatchBytes) }

        for (requestIter in data.requests) {
            val matches = try {
                if (requestIter.requestBytes != null)
                    request.message.contentEquals(requestIter.requestBytes)
                else
                    requestIter.requestRegex!!.matches(normalizedRequest)
            } catch (e: Exception) {
                logger.error("Error while matching requests ${e.message}")
                throw e
            }

            if (!matches) {
                continue
            }

            val responseData = ResponseData(request = request, simEcu = this)
            requestIter.responseHandler.invoke(responseData)
            if (responseData.continueMatching) {
                continue
            } else if (responseData.response.isNotEmpty()) {
                sendResponse(request, responseData.response)
            } else {
                clearCurrentRequest()
            }
            return
        }

        if (this.data.nrcOnNoMatch) {
            sendResponse(request, byteArrayOf(0x7F, request.message[0], NrcError.RequestOutOfRange))
        } else {
            clearCurrentRequest()
        }
    }

    fun addInterceptor(
        name: String = UUID.randomUUID().toString(),
        duration: Duration = Duration.INFINITE,
        interceptor: ResponseData.(request: UdsMessage) -> Boolean
    ): String {
        // expires at expirationTime
        val expirationTime = System.nanoTime() + duration.inWholeNanoseconds


        interceptors[name] = InterceptorWrapper(
            name = name,
            interceptor = interceptor,
            isExpired = { System.nanoTime() >= expirationTime })

        return name
    }

    fun removeInterceptor(name: String) =
        interceptors.remove(name)

    /**
     * Adds a new, or replaces an existing timer with a new routine.
     * Please note that the internal resolution for delay is milliseconds
     */
    fun addOrReplaceTimer(name: String, delay: Duration, handler: TimerTask.() -> Unit) {
        synchronized(mainTimer) {
            timers[name]?.cancel()
            timers[name] = mainTimer.scheduleEcuTimerTask(delay.inWholeMilliseconds, handler)

            // Remove outdated timers
            timers
                .filterValues {
                    it.canBeRemoved
                }
                .forEach {
                    timers.remove(it.key)
                }
        }
    }

    /**
     * Explicitly cancel an existing timer
     */
    fun cancelTimer(name: String) {
        synchronized(mainTimer) {
            timers[name]?.cancel()
            timers.remove(name)
        }
    }
}
