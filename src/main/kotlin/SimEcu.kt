import helper.*
import kotlinx.coroutines.runBlocking
import library.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

class InterceptorData(
    val name: String,
    val interceptor: InterceptorResponseHandler,
    val alsoCallWhenEcuIsBusy: Boolean,
    val isExpired: () -> Boolean
) : DataStorage()

fun EcuData.toEcuConfig(): EcuConfig =
    EcuConfig(
        name = name,
        physicalAddress = physicalAddress,
        functionalAddress = functionalAddress
    )


@Open
class SimEcu(private val data: EcuData) : SimulatedEcu(data.toEcuConfig()) {
    private val internalDataStorage: MutableMap<String, Any?> = ConcurrentHashMap()

    val logger: Logger = LoggerFactory.getLogger(SimEcu::class.java)

    val requests
        get() = data.requests

    val ackBytesMap
        get() = data.ackBytesLengthMap

    private val interceptors = Collections.synchronizedMap(LinkedHashMap<String, InterceptorData>())

    private val mainTimer: Timer by lazy { Timer("$name-Timer", true) }
    private val timers = ConcurrentHashMap<String, EcuTimerTask>()

    fun sendResponse(request: UdsMessage, response: ByteArray) {
        request.respond(response)
    }

    private fun handleInterceptors(request: UdsMessage, busy: Boolean): Boolean {
        if (this.interceptors.isEmpty()) {
            return false
        }

        synchronized (this.interceptors) {
            this.interceptors
                .filterValues { it.isExpired() }
                .forEach {
                    this.interceptors.remove(it.key)
                }

            this.interceptors.forEach {
                if (!it.value.isExpired() && (!busy || it.value.alsoCallWhenEcuIsBusy)) {
                    val responseData = ResponseData<InterceptorData>(
                        caller = it.value,
                        request = request,
                        ecu = this
                    )
                    if (it.value.interceptor.invoke(responseData, RequestMessage(request, busy))) {
                        if (responseData.continueMatching) {
                            return false
                        } else if (responseData.response.isNotEmpty()) {
                            runBlocking {
                                sendResponse(request, responseData.response)
                            }
                        }
                        return true
                    }
                }
            }
        }

        return false
    }

    override fun handleRequestIfBusy(request: UdsMessage) {
        if (handleInterceptors(request, true)) {
            logger.debugIf { "Incoming busy request for $name - ${request.message.toHexString(limit = 10)} was handled by interceptors" }
        }
        super.handleRequestIfBusy(request)
    }

    /**
     * Normalizes an incoming request into hexadecimal
     */
    override fun handleRequest(request: UdsMessage) {
        if (handleInterceptors(request, false)) {
            logger.debugIf { "Incoming request for $name - ${request.message.toHexString(limit = 10)} was handled by interceptors" }
            return
        }

        val normalizedRequest by lazy { request.message.toHexString("", limit = data.requestRegexMatchBytes, limitExceededSuffix = "") }

        logger.traceIf { "Incoming request for $name (${request.targetAddress}) - ${request.message.toHexString()}" }

        // Note: We could build a lookup map to directly find the correct RequestMatcher for a binary input

        for (requestIter in data.requests) {
            val matches = try {
                if (requestIter.requestBytes != null) {
                    request.message.contentEquals(requestIter.requestBytes)
                } else {
                    requestIter.requestRegex!!.matches(normalizedRequest)
                }
            } catch (e: Exception) {
                logger.error("Error while matching requests for $name: ${e.message}", e)
                throw e
            }

            logger.traceIf { "Request for $name: '${request.message.toHexString(limit = 10)}' try match '$requestIter' -> $matches" }

            if (!matches) {
                continue
            }

            val responseData = ResponseData(caller = requestIter, request = request, ecu = this)
            requestIter.responseHandler.invoke(responseData)
            if (responseData.continueMatching) {
                logger.logForRequest(requestIter) { "Request for $name: '${request.message.toHexString(limit = 10)}' matched '$requestIter' -> Continue matching" }
                continue
            } else if (responseData.response.isNotEmpty()) {
                logger.logForRequest(requestIter) { "Request for $name: '${request.message.toHexString(limit = 10)}' matched '$requestIter' -> Send response '${responseData.response.toHexString(limit = 10)}'" }
                sendResponse(request, responseData.response)
            } else {
                logger.logForRequest(requestIter) { "Request for $name: '${request.message.toHexString(limit = 10)}' matched '$requestIter' -> No response" }
            }
            return
        }

        if (this.data.nrcOnNoMatch) {
            logger.debugIf { "Request for $name: '${request.message.toHexString(limit = 10)}' no matching request found -> Sending NRC" }
            sendResponse(request, byteArrayOf(0x7F, request.message[0], NrcError.RequestOutOfRange))
        } else {
            logger.debugIf { "Request for $name: '${request.message.toHexString(limit = 10)}' no matching request found -> Ignore (nrcOnNoMatch = false)" }
        }
    }

    /**
     * Adds an interceptor to the ecu.
     *
     * Interceptors are executed before request matching
     */
    fun addOrReplaceEcuInterceptor(
        name: String = UUID.randomUUID().toString(),
        duration: Duration = Duration.INFINITE,
        alsoCallWhenEcuIsBusy: Boolean = false,
        interceptor: InterceptorResponseHandler
    ): String {
        logger.traceIf { "Adding interceptor '$name' for $duration (busy: $alsoCallWhenEcuIsBusy) in ecu $name"}

        // expires at expirationTime
        val expirationTime = if (duration == Duration.INFINITE) Long.MAX_VALUE else System.nanoTime() + duration.inWholeNanoseconds

        synchronized (interceptors) {
            interceptors[name] = InterceptorData(
                name = name,
                interceptor = interceptor,
                alsoCallWhenEcuIsBusy = alsoCallWhenEcuIsBusy,
                isExpired = { System.nanoTime() >= expirationTime })
        }

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
        logger.traceIf { "Adding or replacing timer '$name' for $name to be executed after $delay"}

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
     * Explicitly cancel a running timer
     */
    fun cancelTimer(name: String) {
        logger.traceIf { "Cancelling timer '$name' for $name" }
        synchronized(mainTimer) {
            timers[name]?.cancel()
            timers.remove(name)
        }
    }

    /**
     * StoredProperties can be retrieved by using delegates. They are only stored until [reset] is called
     */
    fun <T> storedProperty(initialValue: () -> T): StoragePropertyDelegate<T> =
        StoragePropertyDelegate(this.internalDataStorage, initialValue)

    fun clearStoredProperties() =
        internalDataStorage.clear()

    /**
     * Resets all the ECUs stored properties, timers, interceptors and requests
     */
    fun reset() {
        MDC.put("ecu", name)
        logger.debug("Resetting interceptors, timers and stored data")

        this.interceptors.clear()

        synchronized(mainTimer) {
            this.timers.forEach { it.value.cancel() }
            this.timers.clear()
        }

        clearStoredProperties()
        this.data.requests.forEach { it.reset() }
        this.data.resetHandler.forEach {
            if (it.name != null) {
                logger.traceIf { "Calling onReset-Handler" }
            }
            it.handler(this)
        }
    }
}

fun Logger.logForRequest(request: RequestMatcher, t: Throwable? = null, supplier: () -> String) =
    when (request.loglevel) {
        LogLevel.ERROR -> this.errorIf(t, supplier)
        LogLevel.INFO -> this.infoIf(t, supplier)
        LogLevel.DEBUG -> this.debugIf(t, supplier)
        LogLevel.TRACE -> this.traceIf(t, supplier)
    }
