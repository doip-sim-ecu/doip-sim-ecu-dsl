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
        logicalAddress = logicalAddress,
        functionalAddress = functionalAddress,
        pendingNrcSendInterval = pendingNrcSendInterval,
        additionalVam = additionalVam,
    )


@Open
public class SimEcu(private val data: EcuData) : SimulatedEcu(data.toEcuConfig()) {
    private val internalDataStorage: MutableMap<String, Any?> = ConcurrentHashMap()

    public val logger: Logger = LoggerFactory.getLogger(SimEcu::class.java)

    public val requests: RequestList
        get() = data.requests

    public val ackBytesLengthMap: Map<Byte, Int>
        get() = data.ackBytesLengthMap

    private val inboundInterceptors = Collections.synchronizedMap(LinkedHashMap<String, RequestInterceptorData>())
    private val outboundInterceptors = Collections.synchronizedMap(LinkedHashMap<String, ResponseInterceptorData>())

    private val mainTimer: Timer by lazy { Timer("$name-Timer", true) }
    private val timers = ConcurrentHashMap<String, EcuTimerTask>()

    override fun simStarted() {
        requests.simStarted()
    }

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
                        logger.error("Request for $name: '${request.message.toHexString(limit = 10, limitExceededByteCount = true)}' -> Error while processing outbound interceptors for response '${response.toHexString(limit = 10, limitExceededByteCount = true)}'")
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

        // this code will send pending nrcs every `config.pendingNrcSendInterval`, until
        // the pendingFor duration is reached. Afterward the `pendingForCallback` is invoked,
        // which may again change the final response in ResponseData
        val pending = byteArrayOf(0x7f, request.message[0], NrcError.RequestCorrectlyReceivedButResponseIsPending)
        val end = System.currentTimeMillis() + pendingFor.inWholeMilliseconds
        while (System.currentTimeMillis() < end) {
            sendResponse(request, pending)
            logger.logForRequest(responseData.caller) { "Request for $name: '${request.message.toHexString(limit = 10, limitExceededByteCount = true)}' matched '${responseData.caller}' -> Pending '${pending.toHexString(limit = 10, limitExceededByteCount = true)}'" }
            if (end - System.currentTimeMillis() <= config.pendingNrcSendInterval.inWholeMilliseconds) {
                Thread.sleep(end - System.currentTimeMillis())
            } else {
                Thread.sleep(config.pendingNrcSendInterval.inWholeMilliseconds)
            }
        }
        try {
            responseData.pendingForCallback.invoke()
        } catch (e: Exception) {
            logger.error("Request for $name: '${request.message.toHexString(limit = 10, limitExceededByteCount = true)}' matched '${responseData.caller}' -> Error while invoking pending-callback-handler", e)
        }
    }

    private fun handleInboundInterceptors(request: UdsMessage, busy: Boolean): Boolean {
        if (this.inboundInterceptors.isEmpty()) {
            return false
        }

        var hasExpiredEntries = false

        synchronized (this.inboundInterceptors) {
            this.inboundInterceptors.forEach {
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
                                    logger.traceIf { "Request for $name: '${request.message.toHexString(limit = 10, limitExceededByteCount = true)}' handled by interceptor -> Continue matching" }
                                    return@forEach
                                } else if (responseData.response.isNotEmpty()) {
                                    logger.debugIf { "Request for $name: '${request.message.toHexString(limit = 10, limitExceededByteCount = true)}' handled by interceptor -> ${responseData.response.toHexString(limit = 10)}" }
                                    runBlocking {
                                        sendResponse(request, responseData.response)
                                    }
                                }
                                return true
                            }
                        } catch (e: NrcException) {
                            logger.debugIf { "Request for $name: '${request.message.toHexString(limit = 10, limitExceededByteCount = true)}' handled by interceptor -> NRC ${e.code.toString(16)}" }
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
                this.inboundInterceptors
                    .filterValues { it.isExpired() }
                    .forEach {
                        this.inboundInterceptors.remove(it.key)
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

        val handled = data.requests.findMessageAndHandle(request.message) { matcher ->
            val responseData = ResponseData(caller = matcher, request = request, ecu = this)
            try {
                matcher.responseHandler.invoke(responseData)
                handlePending(request, responseData)

                if (responseData.continueMatching) {
                    logger.logForRequest(matcher) { "Request for $name: '${request.message.toHexString(limit = 10, limitExceededByteCount = true)}' matched '$matcher' -> Continue matching" }
                    return@findMessageAndHandle false
                } else if (responseData.response.isNotEmpty()) {
                    logger.logForRequest(matcher) { "Request for $name: '${request.message.toHexString(limit = 10, limitExceededByteCount = true)}' matched '$matcher' -> Send response '${responseData.response.toHexString(limit = 10, limitExceededByteCount = true)}'" }
                    sendResponse(request, responseData.response)
                } else {
                    logger.logForRequest(matcher) { "Request for $name: '${request.message.toHexString(limit = 10, limitExceededByteCount = true)}' matched '$matcher' -> No response" }
                }
            } catch (e: NrcException) {
                handlePending(request, responseData)
                val response = byteArrayOf(0x7F, request.message[0], e.code)
                logger.logForRequest(matcher) { "Request for $name: '${request.message.toHexString(limit = 10, limitExceededByteCount = true)}' matched '$matcher' -> Send NRC response '${response.toHexString(limit = 10)}'" }
                sendResponse(request, response)
            } catch (e: Exception) {
                logger.errorIf(e) { "An error occurred while processing a request for $name: '${request.message.toHexString(limit = 10, limitExceededByteCount = true)}'  -> Sending NRC" }
                sendResponse(request, byteArrayOf(0x7F, request.message[0], NrcError.GeneralProgrammingFailure))
            }
            return@findMessageAndHandle true
        }

        if (!handled) {
            if (this.data.nrcOnNoMatch) {
                logger.debugIf { "Request for $name: '${request.message.toHexString(limit = 10, limitExceededByteCount = true)}' no matching request found -> Sending NRC" }
                sendResponse(request, byteArrayOf(0x7F, request.message[0], NrcError.RequestOutOfRange))
            } else {
                logger.debugIf { "Request for $name: '${request.message.toHexString(limit = 10, limitExceededByteCount = true)}' no matching request found -> Ignore (nrcOnNoMatch = false)" }
            }
        }
    }

    /**
     * Adds an interceptor to the ecu.
     *
     * Interceptors are executed before request matching
     *
     * When alsoCallWhenEcuIsBusy is set, the interceptor is also called, when the ECU is busy. This can be used to
     * implement an S3 Timeout for example.
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

        synchronized (inboundInterceptors) {
            inboundInterceptors[name] = RequestInterceptorData(
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
        inboundInterceptors.remove(name)

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

                inboundInterceptors.clear()

                synchronized(mainTimer) {
                    timers.forEach { it.value.cancel() }
                    timers.clear()
                }

                clearStoredProperties()
                data.requests.forEach { it.reset() }
                data.requests.reset()
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

private inline fun Logger.logForRequest(request: RequestMatcher, t: Throwable? = null, supplier: () -> String) =
    when (request.loglevel) {
        LogLevel.ERROR -> this.errorIf(t, supplier)
        LogLevel.INFO -> this.infoIf(t, supplier)
        LogLevel.DEBUG -> this.debugIf(t, supplier)
        LogLevel.TRACE -> this.traceIf(t, supplier)
    }
