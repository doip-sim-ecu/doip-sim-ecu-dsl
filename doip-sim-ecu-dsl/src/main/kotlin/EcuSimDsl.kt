import doip.library.message.UdsMessage
import helper.decodeHex
import java.util.*
import kotlin.IllegalArgumentException
import kotlin.time.Duration

typealias RequestResponseData = ResponseData<RequestMatcher>
typealias RequestResponseHandler = RequestResponseData.() -> Unit
typealias InterceptorResponseData = ResponseData<InterceptorWrapper>
typealias InterceptorResponseHandler = InterceptorResponseData.(request: UdsMessage) -> Boolean
typealias EcuDataHandler = EcuData.() -> Unit
typealias GatewayDataHandler = GatewayData.() -> Unit
typealias CreateEcuFunc = (name: String, receiver: EcuDataHandler) -> Unit
typealias CreateGatewayFunc = (name: String, receiver: GatewayDataHandler) -> Unit

object NrcError {
    // Common Response Codes
    const val GeneralReject: Byte = 0x10
    const val ServiceNotSupported: Byte = 0x11
    const val SubFunctionNotSupported: Byte = 0x12
    const val IncorrectMessageLengthOrInvalidFormat: Byte = 0x13
    const val ResponseTooLong: Byte = 0x04

    const val BusyRepeatRequest: Byte = 0x21
    const val ConditionsNotCorrect: Byte = 0x22

    const val RequestSequenceError: Byte = 0x24
    const val NoResponseFromSubNetComponent: Byte = 0x25
    const val FailurePreventsExecutionOfRequestedAction: Byte = 0x26

    const val RequestOutOfRange: Byte = 0x31

    const val SecurityAccessDenied: Byte = 0x33

    const val InvalidKey: Byte = 0x35
    const val ExceededNumberOfAttempts: Byte = 0x36
    const val RequiredTimeDelayNotExpired: Byte = 0x37

    const val UploadDownloadNotAccepted: Byte = 0x70
    const val TransferDataSuspended: Byte = 0x71
    const val GeneralProgrammingFailure: Byte = 0x72
    const val WrongBlockSequenceCounter: Byte = 0x73

    const val RequestCorrectlyReceivedButResponseIsPending: Byte = 0x78

    const val SubFunctionNotSupportedInActiveSession: Byte = 0x7E
    const val ServiceNotSupportedInActiveSession: Byte = 0x7F

    // Condition driven
    const val VoltageTooHigh: Byte = 0x92.toByte()
    const val VoltageTooLow: Byte = 0x93.toByte()
}

/**
 * Define the response to be sent after the function returns
 */
open class ResponseData<out T : DataStorage>(
    /**
     * The object that called this response handler (e.g. [RequestMatcher] or [InterceptorWrapper])
     */
    val caller: T,
    /**
     * The request as received for the ecu
     */
    val request: UdsMessage,
    /**
     * Represents the simulated ecu, allows you to modify data on it
     */
    val ecu: SimEcu) {
    /**
     * The request as a byte-array for easier access
     */
    val message: ByteArray
        get() = request.message

    /**
     * The response that's scheduled to be sent after the response handler
     * finishes
     */
    val response
        get() = _response

    /**
     * Continue matching with other request handlers
     */
    val continueMatching
        get() = _continueMatching

    private var _response: ByteArray = ByteArray(0)
    private var _continueMatching: Boolean = false

    fun addOrReplaceEcuTimer(name: String, delay: Duration, handler: TimerTask.() -> Unit) {
        ecu.addOrReplaceTimer(name, delay, handler)
    }

    fun addEcuInterceptor(name: String = UUID.randomUUID().toString(),
                          duration: Duration = Duration.INFINITE,
                          interceptor: ResponseData<InterceptorWrapper>.(request: UdsMessage) -> Boolean) =
        ecu.addInterceptor(name, duration, interceptor)

    fun removeEcuInterceptor(name: String) =
        ecu.removeInterceptor(name)

    fun respond(responseHex: ByteArray) {
        _response = responseHex
    }

    fun respond(responseHex: String) =
        respond(responseHex.decodeHex())

    /**
     * Acknowledge a request with the given payload. The first two
     * bytes (SID + 0x40, subfunction) are automatically preprended
     */
    fun ack(payload: ByteArray = ByteArray(0)) =
        respond(byteArrayOf((message[0] + 0x40.toByte()).toByte(), message[1]) + payload)

    /**
     * Acknowledge a request with the given payload. The first two
     * bytes (SID + 0x40, subfunction) are automatically preprended
     *
     * payload must be a hex-string.
     */
    fun ack(payload: String) =
        ack(payload.decodeHex())

    /**
     * Send a negative response code (NRC) in response to the request
     */
    fun nrc(code: Byte = NrcError.GeneralReject) =
        respond(byteArrayOf(0x7F, message[0], code))

    /**
     * Don't send any responses/acknowledgement, and continue matching
     */
    fun continueMatching(continueMatching: Boolean = true) {
        _continueMatching = continueMatching
    }
}

/**
 * Defines a matcher for an incoming request and the lambdas to be executed when it matches
 */
class RequestMatcher(
    val name: String?,
    val requestBytes: ByteArray?,
    val requestRegex: Regex?,
    val responseHandler: RequestResponseHandler
): DataStorage() {
    init {
        if (requestBytes == null && requestRegex == null) {
            throw IllegalArgumentException("requestBytes or requestRegex must be not null")
        } else if (requestBytes != null && requestRegex != null) {
            throw IllegalArgumentException("Only requestBytes or requestRegex must be set")
        }
    }

    fun reset() {
        clearStoredProperties()
    }
}

open class RequestsData {
    /**
     * List of all defined requests in the order they were defined
     */
    var requests: MutableList<RequestMatcher> = mutableListOf()

    /**
     * Maximum length of data converted into a hex-string for incoming requests
     */
    var requestRegexMatchBytes: Int = 10

    private fun regexifyRequestHex(requestHex: String) =
        Regex(
            pattern = requestHex
                .replace(" ", "")
                .uppercase()
                .replace("[]", ".*"),
//            option = RegexOption.IGNORE_CASE
        )

    /**
     * Define request-matcher & response-handler for a gateway or ecu by using an
     * exact matching byte-array
     */
    fun request(request: ByteArray, name: String? = null, response: RequestResponseHandler = {}): RequestMatcher {
        val req = RequestMatcher(name = name, requestBytes = request, requestRegex = null, responseHandler = response)
        requests.add(req)
        return req
    }

    /**
     * Define request-matcher & response-handler for a gateway or ecu by using a
     * regular expression. Incoming request are normalized into a string
     * by converting the incoming data into uppercase hexadecimal
     * without any spaces. The regular expression defined here is then
     * used to match against this string
     *
     * Note: Take the maximal string length [requestRegexMatchBytes] into
     * account
     */
    fun request(reqRegex: Regex, name: String? = null, response: RequestResponseHandler = {}): RequestMatcher {
        val req = RequestMatcher(name = name, requestBytes = null, requestRegex = reqRegex, responseHandler = response)
        requests.add(req)
        return req
    }

    /**
     * Define request/response pair for a gateway or ecu by using a best guess
     * for the type of string that's used.
     *
     * A static request is only hexadecimal digits and whitespaces, and will be converted
     * into a call to the [request]-Method that takes an ByteArray
     *
     * A dynamic match is detected when the string includes "[", "." or "|". The string
     * is automatically converted into a regular expression by replacing all "[]" with ".*",
     * turning it into uppercase, and removing all spaces.
     */
    fun request(reqHex: String, name: String = "", response: RequestResponseHandler = {}) {
        if (isRegex(reqHex)) {
            request(regexifyRequestHex(reqHex), name, response)
        } else {
            request(reqHex.decodeHex(), name, response)
        }
    }

    private fun isRegex(value: String) =
        value.contains("[") || value.contains(".") || value.contains("|")
}

/**
 * Define the data associated with the ecu
 */
open class EcuData(val name: String) : RequestsData() {
    var physicalAddress: Int = 0
    var functionalAddress: Int = 0
    var nrcOnNoMatch = true
}

val gateways: MutableList<GatewayData> = mutableListOf()
val gatewayInstances: MutableList<SimGateway> = mutableListOf()

/**
 * Defines a DoIP-Gateway and the ECUs behind it
 */
fun gateway(name: String, receiver: GatewayDataHandler) {
    val gatewayData = GatewayData(name)
    receiver.invoke(gatewayData)
    gateways.add(gatewayData)
}


fun reset() {
    gatewayInstances.forEach { it.reset() }
}

fun stop() {
    gatewayInstances.forEach { it.stop() }
    gatewayInstances.clear()
}

fun start() {
    gatewayInstances.addAll(gateways.map { SimGateway(it) })

    gatewayInstances.forEach { it.start() }
}
