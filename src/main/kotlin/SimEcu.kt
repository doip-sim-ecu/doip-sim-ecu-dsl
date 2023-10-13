import helper.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.slf4j.MDCContext
import library.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

public open class InterceptorData<T>(
    public val name: String,
    public val interceptor: T,
    public val isExpired: () -> Boolean
) : DataStorage()

public class RequestInterceptorData(
    name: String,
    interceptor: InterceptorRequestHandler,
    public val alsoCallWhenEcuIsBusy: Boolean,
    isExpired: () -> Boolean
) : InterceptorData<InterceptorRequestHandler>(name, interceptor, isExpired)

public class ResponseInterceptorData(name: String, interceptor: InterceptorResponseHandler, isExpired: () -> Boolean)
    : InterceptorData<InterceptorResponseHandler>(name, interceptor, isExpired)

internal fun EcuData.toEcuConfig(): EcuConfig =
    EcuConfig(
        name = name,
        physicalAddress = physicalAddress,
        functionalAddress = functionalAddress,
        pendingNrcSendInterval = pendingNrcSendInterval,
        additionalVam = additionalVam,
    )


@Open
public class SimEcu(private val data: EcuData) : SimulatedEcu(data.toEcuConfig()) {
    private val internalDataStorage: MutableMap<String, Any?> = ConcurrentHashMap()

    public val logger: Logger = LoggerFactory.getLogger(SimEcu::class.java)

    public val requests: MutableList<RequestMatcher>
        get() = data.requests

    public val ackBytesMap: Map<Byte, Int>
        get() = data.ackBytesLengthMap

    private val interceptors = Collections.synchronizedMap(LinkedHashMap<String, RequestInterceptorData>())
    private val outboundInterceptors = Collections.synchronizedMap(LinkedHashMap<String, ResponseInterceptorData>())

    private val mainTimer: Timer by lazy { Timer("$name-Timer", true) }
    private val timers = ConcurrentHashMap<String, EcuTimerTask>()

    public fun sendResponse(request: UdsMessage, response: ByteArray) {
        if (handleOutboundInterceptors(request, response)) {
            return
        }
        request.respond(response)
    }

    private fun handleOutboundInterceptors(request: UdsMessage, response: ByteArray): Boolean {
        if (this.outboundInterceptors.isEmpty()) {
            return false
        }

        var hasExpiredEntries = false

        synchronized (this.outboundInterceptors) {
            this.outboundInterceptors.forEach {
                if (!it.value.isExpired()) {
                    try {
                        val responseData = InterceptorResponseData(
                            caller = it.value,
                            request = request,
                            responseMessage = response,
                            ecu = this
                        )
                        if (it.value.interceptor.invoke(responseData, response)) {
                            if (responseData.pendingFor != null) {
                                throw UnsupportedOperationException("Outbound interceptors don't support pendingFor (yet)")
                            }
                            if (responseData.continueMatching) {
                                return@forEach
                            } else if (responseData.response.isNotEmpty()) {
                                runBlocking {
                                    // Must directly send the response, otherwise we'd have an infinite loop
                                    request.respond(responseData.response)
                                }
                            }
                            return true
                        }
                    } catch (e: Exception) {
                        logger.error("Request for $name: '${request.message.toHexString(limit = 10)}' -> Error while processing outbound interceptors for response '${response.toHexString(limit = 10)}'")
                    }
                } else {
                    hasExpiredEntries = true
                }
            }

            if (hasExpiredEntries) {
                this.outboundInterceptors
                    .filterValues { it.isExpired() }
                    .forEach {
                        this.outboundInterceptors.remove(it.key)
                    }
            }
        }

        return false
    }

    private fun handlePending(request: UdsMessage, responseData: ResponseData<RequestMatcher>) {
        val pendingFor = responseData.pendingFor ?: return

        val pending = byteArrayOf(0x7f, request.message[0], NrcError.RequestCorrectlyReceivedButResponseIsPending)
        val end = System.currentTimeMillis() + pendingFor.inWholeMilliseconds
        while (System.currentTimeMillis() < end) {
            sendResponse(request, pending)
            logger.logForRequest(responseData.caller) { "Request for $name: '${request.message.toHexString(limit = 10)}' matched '${responseData.caller}' -> Pending '${pending.toHexString(limit = 10)}'" }
            if (end - System.currentTimeMillis() <= config.pendingNrcSendInterval.inWholeMilliseconds) {
                Thread.sleep(end - System.currentTimeMillis())
            } else {
                Thread.sleep(config.pendingNrcSendInterval.inWholeMilliseconds)
            }
        }
        try {
            responseData.pendingForCallback.invoke()
        } catch (e: Exception) {
            logger.error("Request for $name: '${request.message.toHexString(limit = 10)}' matched '${responseData.caller}' -> Error while invoking pending-callback-handler", e)
        }
    }

    private fun handleInboundInterceptors(request: UdsMessage, busy: Boolean): Boolean {
        if (this.interceptors.isEmpty()) {
            return false
        }

        var hasExpiredEntries = false

        synchronized (this.interceptors) {
            this.interceptors.forEach {
                if (!it.value.isExpired()) {
                    if (!busy || it.value.alsoCallWhenEcuIsBusy) {
                        val responseData = InterceptorRequestData(
                            caller = it.value,
                            request = request,
                            ecu = this
                        )
                        try {
                            if (it.value.interceptor.invoke(responseData, RequestMessage(request, busy))) {
                                if (responseData.continueMatching) {
                                    logger.traceIf { "Request for $name: '${request.message.toHexString(limit = 10)}' handled by interceptor -> Continue matching" }
                                    return@forEach
                                } else if (responseData.response.isNotEmpty()) {
                                    logger.debugIf { "Request for $name: '${request.message.toHexString(limit = 10)}' handled by interceptor -> ${responseData.response.toHexString(limit = 10)}" }
                                    runBlocking {
                                        sendResponse(request, responseData.response)
                                    }
                                }
                                return true
                            }
                        } catch (e: NrcException) {
                            logger.debugIf { "Request for $name: '${request.message.toHexString(limit = 10)}' handled by interceptor -> NRC ${e.code.toString(16)}" }
                            sendResponse(request, byteArrayOf(0x7F, request.message[0], e.code))
                            return true
                        } catch (e: Exception) {
                            logger.error("Error while processing interceptor ${it.value.name}", e)
                            sendResponse(request, byteArrayOf(0x7F, request.message[0], NrcError.GeneralReject))
                            return true
                        }
                    }
                } else {
                    hasExpiredEntries = true
                }
            }

            if (hasExpiredEntries) {
                this.interceptors
                    .filterValues { it.isExpired() }
                    .forEach {
                        this.interceptors.remove(it.key)
                    }
            }
        }

        return false
    }

    /**
     * Handler that is called when the ecu is currently busy with another request
     */
    override fun handleRequestIfBusy(request: UdsMessage) {
        if (handleInboundInterceptors(request, true)) {
            logger.debugIf { "Incoming busy request for $name - ${request.message.toHexString(limit = 10)} was handled by interceptors" }
        }
        super.handleRequestIfBusy(request)
    }

    /**
     * Handler for an incoming diagnostic message when the ECU isn't busy
     */
    override fun handleRequest(request: UdsMessage) {
        if (handleInboundInterceptors(request, false)) {
            logger.debugIf { "Incoming request for $name - ${request.message.toHexString(limit = 10)} was handled by interceptors" }
            return
        }

        logger.traceIf { "Incoming request for $name (${request.targetAddress}) - ${request.message.toHexString()}" }

        // Note: We could build a lookup map to directly find the correct RequestMatcher for a binary input

        for (requestIter in data.requests) {
            val matches = try {
                if (requestIter.onlyStartsWith) {
                    request.message.startsWith(requestIter.requestBytes)
                } else {
                    request.message.contentEquals(requestIter.requestBytes)
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
            try {
                requestIter.responseHandler.invoke(responseData)
                handlePending(request, responseData)
            } catch (e: NrcException) {
                handlePending(request, responseData)
                val response = byteArrayOf(0x7F, request.message[0], e.code)
                logger.logForRequest(requestIter) { "Request for $name: '${request.message.toHexString(limit = 10)}' matched '$requestIter' -> Send response '${response.toHexString(limit = 10)}'" }
                sendResponse(request, response)
                return
            } catch (e: Exception) {
                logger.errorIf(e) { "An error occurred while processing a request for $name: '${request.message.toHexString(limit = 10)}'  -> Sending NRC" }
                sendResponse(request, byteArrayOf(0x7F, request.message[0], NrcError.GeneralProgrammingFailure))
                return
            }

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
    public fun addOrReplaceEcuInterceptor(
        name: String = UUID.randomUUID().toString(),
        duration: Duration = Duration.INFINITE,
        alsoCallWhenEcuIsBusy: Boolean = false,
        interceptor: InterceptorRequestHandler
    ): String {
        logger.traceIf { "Adding interceptor '$name' for $duration (busy: $alsoCallWhenEcuIsBusy) in ecu ${this.name}"}

        // expires at expirationTime
        val expirationTime = if (duration == Duration.INFINITE) Long.MAX_VALUE else System.nanoTime() + duration.inWholeNanoseconds

        synchronized (interceptors) {
            interceptors[name] = RequestInterceptorData(
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
    public fun removeInterceptor(name: String): RequestInterceptorData? =
        interceptors.remove(name)

    /**
     * Adds an outbound interceptor to the ecu.
     *
     * Outbound interceptors are executed before a response is sent
     */
    public fun addOrReplaceEcuOutboundInterceptor(
        name: String = UUID.randomUUID().toString(),
        duration: Duration = Duration.INFINITE,
        interceptor: InterceptorResponseHandler
    ): String {
        logger.traceIf { "Adding outbound interceptor '$name' for $duration in ecu ${this.name}"}

        // expires at expirationTime
        val expirationTime = if (duration == Duration.INFINITE) Long.MAX_VALUE else System.nanoTime() + duration.inWholeNanoseconds

        synchronized (outboundInterceptors) {
            outboundInterceptors[name] = ResponseInterceptorData(
                name = name,
                interceptor = interceptor,
                isExpired = { System.nanoTime() >= expirationTime })
        }

        return name
    }

    /**
     * Remove an outbound interceptor by name
     */
    public fun removeOutboundInterceptor(name: String): ResponseInterceptorData? =
        outboundInterceptors.remove(name)

    /**
     * Adds a new, or replaces an existing timer with a new routine.
     * Please note that the internal resolution for delay is milliseconds
     */
    public fun addOrReplaceTimer(name: String, delay: Duration, handler: TimerTask.() -> Unit) {
        logger.traceIf { "Adding or replacing timer '$name' for ${this.name} to be executed after $delay"}

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
    public fun cancelTimer(name: String) {
        logger.traceIf { "Cancelling timer '$name' for ${this.name}" }
        synchronized(mainTimer) {
            timers[name]?.cancel()
            timers.remove(name)
        }
    }

    /**
     * Retrieves a property that is persisted until [reset] or [clearStoredProperties] are called
     */
    public fun <T> storedProperty(initialValue: () -> T): StoragePropertyDelegate<T> =
        StoragePropertyDelegate(this.internalDataStorage, initialValue)

    /**
     * Clears the stored properties
     */
    public fun clearStoredProperties(): Unit =
        internalDataStorage.clear()

    /**
     * Resets all the ECUs stored properties, timers, interceptors and requests
     */
    public fun reset() {
        runBlocking(Dispatchers.Default) {
            MDC.put("ecu", name)

            launch(MDCContext()) {
                logger.debug("Resetting interceptors, timers and stored data")

                interceptors.clear()

                synchronized(mainTimer) {
                    timers.forEach { it.value.cancel() }
                    timers.clear()
                }

                clearStoredProperties()
                data.requests.forEach { it.reset() }
                data.resetHandler.forEach {
                    if (it.name != null) {
                        logger.traceIf { "Calling onReset-Handler" }
                    }
                    it.handler(this@SimEcu)
                }
            }
        }
    }
}

private fun Logger.logForRequest(request: RequestMatcher, t: Throwable? = null, supplier: () -> String) =
    when (request.loglevel) {
        LogLevel.ERROR -> this.errorIf(t, supplier)
        LogLevel.INFO -> this.infoIf(t, supplier)
        LogLevel.DEBUG -> this.debugIf(t, supplier)
        LogLevel.TRACE -> this.traceIf(t, supplier)
    }
