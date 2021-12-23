import doip.library.message.UdsMessage
import doip.simulation.nodes.EcuConfig
import doip.simulation.standard.StandardEcu
import helper.EcuTimerTask
import helper.Open
import helper.scheduleEcuTimerTask
import helper.toHexString
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * Extension function to provide the address by the targetAddressType provided in the UdsMessage
 */
fun EcuConfig.addressByType(message: UdsMessage): Int =
    when (message.targetAddressType) {
        UdsMessage.PHYSICAL -> this.physicalAddress
        UdsMessage.FUNCTIONAL -> this.functionalAddress
        else -> throw IllegalStateException("Unknown targetAddressType ${message.targetAddressType}")
    }

class InterceptorData(
    val name: String,
    val interceptor: InterceptorResponseHandler,
    val isExpired: () -> Boolean
) : DataStorage()

fun EcuData.toEcuConfig(): EcuConfig {
    val config = EcuConfig()
    config.name = this.name
    config.physicalAddress = this.physicalAddress
    config.functionalAddress = this.functionalAddress
    return config
}

@Open
class SimEcu(private val data: EcuData) : StandardEcu(data.toEcuConfig()) {
    private val logger = doip.logging.LogManager.getLogger(SimEcu::class.java)
    private val internalDataStorage: MutableMap<String, Any?> = ConcurrentHashMap()

    val name
        get() = data.name

    private val interceptors = Collections.synchronizedMap(LinkedHashMap<String, InterceptorData>())

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
                val responseData = ResponseData<InterceptorData>(
                    caller = it.value,
                    request = request,
                    ecu = this
                )
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

            val responseData = ResponseData(caller = requestIter, request = request, ecu = this)
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

    /**
     * Adds an interceptor to the ecu.
     *
     * Interceptors are executed before request matching
     */
    fun addInterceptor(
        name: String = UUID.randomUUID().toString(),
        duration: Duration = Duration.INFINITE,
        interceptor: ResponseData<InterceptorData>.(request: UdsMessage) -> Boolean
    ): String {
        // expires at expirationTime
        val expirationTime = System.nanoTime() + duration.inWholeNanoseconds

        interceptors[name] = InterceptorData(
            name = name,
            interceptor = interceptor,
            isExpired = { System.nanoTime() >= expirationTime })

        return name
    }

    /**
     * Remove an interceptor by name
     */
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

    fun <T> storedProperty(initialValue: () -> T): StoragePropertyDelegate<T> =
        StoragePropertyDelegate(this.internalDataStorage, initialValue)

    fun clearStoredProperties() =
        internalDataStorage.clear()

    fun reset() {
        this.interceptors.clear()

        synchronized(mainTimer) {
            this.timers.forEach { it.value.cancel() }
            this.timers.clear()
        }

        clearStoredProperties()
        this.data.requests.forEach { it.reset() }
    }
}
