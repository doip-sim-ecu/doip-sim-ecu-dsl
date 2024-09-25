@file:Suppress("MemberVisibilityCanBePrivate")

import library.*
import java.util.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

public typealias RequestResponseData = ResponseData<RequestMatcher>
public typealias RequestResponseHandler = RequestResponseData.() -> Unit
public typealias InterceptorRequestData = ResponseData<RequestInterceptorData>
public typealias InterceptorRequestHandler = InterceptorRequestData.(request: RequestMessage) -> Boolean
public typealias InterceptorResponseHandler = InterceptorResponseData.(response: ByteArray) -> Boolean
public typealias EcuDataHandler = EcuData.() -> Unit
public typealias DoipEntityDataHandler = DoipEntityData.() -> Unit
public typealias NetworkingDataHandler = NetworkingData.() -> Unit
public typealias CreateEcuFunc = (name: String, receiver: EcuDataHandler) -> Unit
public typealias CreateDoipEntityFunc = (name: String, receiver: DoipEntityDataHandler) -> Unit
public typealias CreateNetworkFunc = (receiver: NetworkingDataHandler) -> Unit

@Suppress("unused")
public class InterceptorResponseData(
    caller: ResponseInterceptorData,
    request: UdsMessage,
    public val responseMessage: ByteArray,
    ecu: SimEcu
) : ResponseData<ResponseInterceptorData>(caller, request, ecu)

public open class NrcException(public val code: Byte) : Exception()

@Suppress("unused", "ConstPropertyName")
public object NrcError {
    // Common Response Codes
    public const val GeneralReject: Byte = 0x10
    public const val ServiceNotSupported: Byte = 0x11
    public const val SubFunctionNotSupported: Byte = 0x12
    public const val IncorrectMessageLengthOrInvalidFormat: Byte = 0x13
    public const val ResponseTooLong: Byte = 0x14

    public const val BusyRepeatRequest: Byte = 0x21
    public const val ConditionsNotCorrect: Byte = 0x22

    public const val RequestSequenceError: Byte = 0x24
    public const val NoResponseFromSubNetComponent: Byte = 0x25
    public const val FailurePreventsExecutionOfRequestedAction: Byte = 0x26

    public const val RequestOutOfRange: Byte = 0x31

    public const val SecurityAccessDenied: Byte = 0x33
    public const val AuthenticationRequired: Byte = 0x34

    public const val InvalidKey: Byte = 0x35
    public const val ExceededNumberOfAttempts: Byte = 0x36
    public const val RequiredTimeDelayNotExpired: Byte = 0x37
    public const val SecureDataTransmissionRequired: Byte = 0x38
    public const val SecureDataTransmissionNotAllowed: Byte = 0x39
    public const val SecureDataVerificationFailed: Byte = 0x3a

    public const val CertificateVerificationFailedInvalidTimePeriod: Byte = 0x50
    public const val CertificateVerificationFailedInvalidSignature: Byte = 0x51
    public const val CertificateVerificationFailedInvalidChainOfTrust: Byte = 0x52
    public const val CertificateVerificationFailedInvalidType: Byte = 0x53
    public const val CertificateVerificationFailedInvalidFormat: Byte = 0x54
    public const val CertificateVerificationFailedInvalidContent: Byte = 0x55
    public const val CertificateVerificationFailedInvalidScope: Byte = 0x56
    public const val CertificateVerificationFailedInvalidCertRevoked: Byte = 0x57
    public const val OwnershipVerificationFailed: Byte = 0x58
    public const val ChallengeCalculationFailed: Byte = 0x59
    public const val SettingAccessRightsFailed: Byte = 0x5a
    public const val SessionKeyCreationDerivationFailed: Byte = 0x5b
    public const val ConfigurationDataUsageFailed: Byte = 0x5c
    public const val DeAuthenticationFailed: Byte = 0x5d

    public const val UploadDownloadNotAccepted: Byte = 0x70
    public const val TransferDataSuspended: Byte = 0x71
    public const val GeneralProgrammingFailure: Byte = 0x72
    public const val WrongBlockSequenceCounter: Byte = 0x73

    public const val RequestCorrectlyReceivedButResponseIsPending: Byte = 0x78

    public const val SubFunctionNotSupportedInActiveSession: Byte = 0x7E
    public const val ServiceNotSupportedInActiveSession: Byte = 0x7F

    public const val RpmTooHigh: Byte = 0x81.toByte()
    public const val RpmTooLow: Byte = 0x82.toByte()
    public const val EngineIsRunning: Byte = 0x84.toByte()
    public const val EngineRunTimeTooLow: Byte = 0x85.toByte()
    public const val TemperatureTooHigh: Byte = 0x86.toByte()
    public const val TemperatureTooLow: Byte = 0x87.toByte()
    public const val VehicleSpeedToHigh: Byte = 0x88.toByte()
    public const val VehicleSpeedTooLow: Byte = 0x89.toByte()
    public const val ThrottleOrPedalTooHigh: Byte = 0x8a.toByte()
    public const val ThrottleOrPedalTooLow: Byte = 0x8b.toByte()
    public const val TransmissionRangeNotInNeutral: Byte = 0x8c.toByte()
    public const val TransmissionRangeNotInGear: Byte = 0x8d.toByte()

    public const val BrakeSwitchesNotClosed: Byte = 0x8f.toByte()

    public const val TorqueConvertedClutchLocked: Byte = 0x91.toByte()
    public const val VoltageTooHigh: Byte = 0x92.toByte()
    public const val VoltageTooLow: Byte = 0x93.toByte()
    public const val ResourceTemporarilyNotAvailable: Byte = 0x94.toByte()
}

public class RequestMessage(udsMessage: UdsMessage, public val isBusy: Boolean) :
    UdsMessage(
        udsMessage.sourceAddress,
        udsMessage.targetAddress,
        udsMessage.targetAddressType,
        udsMessage.targetAddressPhysical,
        udsMessage.message,
        udsMessage.output
    )

/**
 * Define the response to be sent after the function returns
 */
public open class ResponseData<T>(
    /**
     * The object that called this response handler (e.g. [RequestMatcher] or [InterceptorData])
     */
    public val caller: T,
    /**
     * The request as received for the ecu
     */
    public val request: UdsMessage,
    /**
     * Represents the simulated ecu, allows you to modify data on it
     */
    public val ecu: SimEcu
) {
    /**
     * The request as a byte-array for easier access
     */
    public val message: ByteArray
        get() = request.message

    /**
     * The response that's scheduled to be sent after the response handler
     * finishes
     */
    public val response: ByteArray
        get() = _response

    /**
     * Continue matching with other request handlers
     */
    public val continueMatching: Boolean
        get() = _continueMatching

    public val pendingFor: Duration?
        get() = _pendingFor

    public val pendingForInterval: Duration?
        get() = _pendingForInterval

    public val pendingForNrc: Byte?
        get() = _pendingForNrc

    public val pendingForCallback: () -> Unit
        get() = _pendingForCallback

    public val hardResetEntityFor: Duration?
        get() = _hardResetEntityFor

    private var _response: ByteArray = ByteArray(0)
    private var _continueMatching: Boolean = false
    private var _pendingFor: Duration? = null
    private var _pendingForInterval: Duration? = null
    private var _pendingForNrc: Byte? = null
    private var _pendingForCallback: () -> Unit = {}
    private var _hardResetEntityFor: Duration? = null

    /**
     * See [SimEcu.addOrReplaceTimer]
     */
    public fun addOrReplaceEcuTimer(name: String, delay: Duration, handler: TimerTask.() -> Unit) {
        ecu.addOrReplaceTimer(name, delay, handler)
    }

    /**
     * See [SimEcu.cancelTimer]
     */
    public fun cancelEcuTimer(name: String) {
        ecu.cancelTimer(name)
    }

    /**
     * See [SimEcu.addOrReplaceEcuInterceptor]
     */
    public fun addOrReplaceEcuInterceptor(
        name: String = UUID.randomUUID().toString(),
        duration: Duration = Duration.INFINITE,
        alsoCallWhenEcuIsBusy: Boolean = false,
        interceptor: InterceptorRequestHandler
    ): String =
        ecu.addOrReplaceEcuInterceptor(name, duration, alsoCallWhenEcuIsBusy, interceptor)

    /**
     * See [SimEcu.removeInterceptor]
     */
    public fun removeEcuInterceptor(name: String): RequestInterceptorData? =
        ecu.removeInterceptor(name)

    public fun respond(responseHex: ByteArray) {
        _response = responseHex
    }

    public fun respond(responseHex: String): Unit =
        respond(responseHex.decodeHex())

    /**
     * Acknowledge a request with the given payload. The first nrOfRequestBytes
     * bytes (SID + 0x40, ...) are automatically prefixed.
     *
     * nrOfRequestBytes is the total number of bytes (including SID + 0x40)
     */
    public fun ack(payload: ByteArray = ByteArray(0), nrOfRequestBytes: Int): Unit =
        respond(
            byteArrayOf(
                (message[0] + 0x40.toByte()).toByte(),
                *message.copyOfRange(1, nrOfRequestBytes),
                *payload
            )
        )

    /**
     * Acknowledge a request with the given payload. The first nrOfRequestBytes
     * bytes (SID + 0x40, ...) are automatically prefixed
     *
     * payload must be a hex-string.
     * nrOfRequestBytes is the total number of bytes (including SID + 0x40)
     */
    public fun ack(payload: String, nrOfRequestBytes: Int): Unit =
        ack(payload.decodeHex(), nrOfRequestBytes)

    /**
     * Acknowledge a request with the given payload.
     *
     * The first n bytes are automatically prefixed, depending on which service
     * is responded to (see [RequestsData.ackBytesLengthMap])
     */
    public fun ack(payload: String): Unit =
        ack(payload, ecu.ackBytesLengthMap[message[0]] ?: 2)

    /**
     * Acknowledge a request with the given payload.
     *
     * The first n bytes are automatically prefixed, depending on which service
     * is responded to (see [RequestsData.ackBytesLengthMap])
     */
    public fun ack(payload: ByteArray = ByteArray(0)): Unit =
        ack(payload, ecu.ackBytesLengthMap[message[0]] ?: 2)

    /**
     * Send a negative response code (NRC) in response to the request
     */
    public fun nrc(code: Byte = NrcError.GeneralReject): Unit =
        respond(byteArrayOf(0x7F, message[0], code))

    /**
     * Don't send any responses/acknowledgement, and continue matching
     */
    public fun continueMatching(continueMatching: Boolean = true) {
        _continueMatching = continueMatching
    }

    /**
     * Sends a busy wait (NRC 0x78, received but pending) for duration, before calling
     * callback, and sending the reply
     */
    public fun pendingFor(duration: Duration, callback: () -> Unit = {}) {
        _pendingFor = duration
        _pendingForCallback = callback
    }

    /**
     * Overrides the default interval for sending pending NRC
     */
    public fun pendingForInterval(duration: Duration) {
        _pendingForInterval = duration
    }

    /**
     * Changes the default busy wait NRC (0x78) to something different
     */
    public fun pendingForNrc(nrc: Byte) {
        _pendingForNrc = nrc
    }

    /**
     * Pretend to hard-reset entity by disconnecting the current, nd  not being reachable for duration,
     * and sending a vam after being reachable again
     */
    @ExperimentalDoipDslApi
    public fun hardResetEntityFor(duration: Duration) {
        _hardResetEntityFor = duration
    }
}

/**
 * Defines a matcher for an incoming request and the lambdas to be executed when it matches
 */
public class RequestMatcher(
    public val name: String?,
    public val requestBytes: ByteArray,
    public val onlyStartsWith: Boolean = false,
    public val loglevel: LogLevel = LogLevel.DEBUG,
    public val responseHandler: RequestResponseHandler
) : DataStorage() {
    /**
     * Reset persistent storage for this request
     */
    public fun reset() {
        clearStoredProperties()
    }

    public fun matches(request: ByteArray): Boolean {
        return if (onlyStartsWith) {
            request.startsWith(requestBytes)
        } else {
            request.contentEquals(requestBytes)
        }
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("{ ")
        if (!name.isNullOrEmpty()) {
            sb.append("$name; ")
        }
        if (onlyStartsWith) {
            sb.append("Bytes: ${requestBytes.toHexString(limit = 10)} []")
        } else {
            sb.append("Bytes: ${requestBytes.toHexString(limit = 10)}")
        }
        sb.append(" }")
        return sb.toString()
    }
}

internal fun List<RequestMatcher>.findByName(name: String) =
    this.firstOrNull { it.name == name }

internal fun MutableList<RequestMatcher>.removeByName(name: String): RequestMatcher? {
    val index = this.indexOfFirst { it.name == name }
    if (index >= 0) {
        return this.removeAt(index)
    }
    return null
}

public data class ResetHandler(val name: String?, val handler: (SimEcu) -> Unit)

public enum class LogLevel {
    ERROR,
    INFO,
    DEBUG,
    TRACE,
}

public enum class DuplicateStrategy {
    ERROR,
    REPLACE,
    APPEND,
}

public val DefaultAckBytesLengthMap: Map<Byte, Int> = mapOf(
    // default is 2
    0x22.toByte() to 3,
    0x2e.toByte() to 3,

    0x31.toByte() to 4, // routine control

    0x34.toByte() to 1,  // request download
    0x35.toByte() to 1,  // request upload
//    0x36.toByte() to 2, // transfer data
    0x37.toByte() to 1, // transfer exit

    0x14.toByte() to 1, // clear dtc
)

public open class RequestsData(
    public val name: String,
    /**
     * Return a nrc when no request could be matched
     */
    public var nrcOnNoMatch: Boolean = true,
    requests: List<RequestMatcher> = emptyList(),
    resetHandler: List<ResetHandler> = emptyList(),

    /**
     * Map of Request SID to number of ack response byte count
     */
    public var ackBytesLengthMap: Map<Byte, Int> = DefaultAckBytesLengthMap,
) {
    /**
     * List of all defined requests in the order they were defined
     */
    public val requests: RequestList = RequestList(requests)

    /**
     * List of defined reset handlers
     */
    public val resetHandler: MutableList<ResetHandler> = mutableListOf(*resetHandler.toTypedArray())


    /**
     * Define request-matcher & response-handler for a gateway or ecu by using an
     * exact matching byte-array
     */
    public fun request(
        /**
         * Byte-Array to match the request
         */
        request: ByteArray,
        /**
         * Name of the expression to be shown in logs
         */
        name: String? = null,
        /**
         * Defines if request is only for the start or for an exact match
         */
        onlyStartsWith: Boolean = false,
        /**
         * Specifies how a duplicated request matcher should be handled
         */
        duplicateStrategy: DuplicateStrategy = DuplicateStrategy.ERROR,
        /**
         * The loglevel used to log when the request matches and its responses
         */
        loglevel: LogLevel = LogLevel.DEBUG,
        /**
         * Handler that is called when the request is matched
         */
        response: RequestResponseHandler = {}
    ): RequestMatcher {
        val req = RequestMatcher(
            name = name,
            requestBytes = request,
            onlyStartsWith = onlyStartsWith,
            loglevel = loglevel,
            responseHandler = response
        )
        when (duplicateStrategy) {
            DuplicateStrategy.APPEND -> requests.add(req)
            DuplicateStrategy.ERROR -> {
                val duplicate = requests.firstOrNull {
                    it.onlyStartsWith == req.onlyStartsWith && it.requestBytes.contentEquals(req.requestBytes)
                }
                if (duplicate != null) {
                    throw IllegalArgumentException("The request is duplicated, existing request: $duplicate - use different duplicateStrategy, or change requestBytes")
                }
                requests.add(req)
            }

            DuplicateStrategy.REPLACE -> {
                val duplicate = requests.filter {
                    it.onlyStartsWith == req.onlyStartsWith && it.requestBytes.contentEquals(req.requestBytes)
                }
                if (duplicate.isNotEmpty()) {
                    requests.removeAll(duplicate)
                }
                requests.add(req)
            }
        }

        return req
    }

    /**
     * Define request/response pair for a gateway or ecu by using a best guess
     * for the type of string that's used.
     *
     * A static request is only hexadecimal digits and whitespaces, and will be converted
     * into a call to the [request]-Method that takes an ByteArray
     *
     * A dynamic match is detected when the string ends with "[]" or ".*". The hex data only
     * has matched to the start of the incoming request then.
     */
    public fun request(
        /**
         * Hex-String to match the request
         */
        reqHex: String,
        /**
         * Name of the expression to be shown in logs
         */
        name: String? = null,
        /**
         * Specifies how a duplicated request matcher should be handled
         */
        duplicateStrategy: DuplicateStrategy = DuplicateStrategy.ERROR,
        /**
         * The loglevel used to log when the request matches and its responses
         */
        loglevel: LogLevel = LogLevel.DEBUG,
        /**
         * Handler that is called when the request is matched
         */
        response: RequestResponseHandler = {}
    ) {
        val trimmed = reqHex.trimEnd()
        val isOpenEnded = trimmed.endsWith("[]") || trimmed.endsWith(".*") || trimmed.endsWith("..")

        request(
            request = trimmed.decodeHex(),
            name = name,
            onlyStartsWith = isOpenEnded,
            duplicateStrategy = duplicateStrategy,
            loglevel = loglevel,
            response = response
        )
    }

    public fun onReset(name: String? = null, handler: (SimEcu) -> Unit) {
        resetHandler.add(ResetHandler(name, handler))
    }
}

/**
 * Define the data associated with the ecu
 */
public open class EcuData(
    name: String,
    /**
     * The logical address under which the gateway shall be reachable
     */
    public var logicalAddress: Short = 0,
    /**
     * The functional address under which the gateway (and other ecus) shall be reachable
     */
    public var functionalAddress: Short = 0,
    /**
     * Interval between sending pending NRC messages (0x78)
     */
    public var pendingNrcSendInterval: Duration = 2.seconds,
    nrcOnNoMatch: Boolean = true,
    requests: List<RequestMatcher> = emptyList(),
    resetHandler: List<ResetHandler> = emptyList(),
    ackBytesLengthMap: Map<Byte, Int> = DefaultAckBytesLengthMap,
) : RequestsData(
    name = name,
    nrcOnNoMatch = nrcOnNoMatch,
    requests = requests,
    resetHandler = resetHandler,
    ackBytesLengthMap = ackBytesLengthMap,
)

internal val networks: MutableList<NetworkingData> = mutableListOf()
internal val networkInstances: MutableList<SimDoipNetworking> = mutableListOf()

public fun networks(): List<NetworkingData> =
    networks.toList()

public fun networkInstances(): List<SimDoipNetworking> =
    networkInstances.toList()

public fun network(receiver: NetworkingDataHandler) {
    val networkingData = NetworkingData()
    receiver.invoke(networkingData)
    networks.add(networkingData)
}

public fun reset() {
    networkInstances.forEach { it.reset() }
}

@Suppress("unused")
public fun start() {
    networkInstances.addAll(networks.map { SimDoipNetworking(it) })

    val networkManager = networkInstances.map { NetworkManager(it.data, it.doipEntities) }

    networkManager.forEach {
        it.start()
    }
}
