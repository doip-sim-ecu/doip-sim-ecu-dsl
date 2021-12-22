import doip.library.message.UdsMessage
import helper.decodeHex
import java.net.InetAddress
import java.util.*
import kotlin.IllegalArgumentException
import kotlin.properties.Delegates
import kotlin.time.Duration

typealias ResponseHandler = ResponseData.() -> Unit
typealias EcuDataHandler = EcuData.() -> Unit
typealias GatewayDataHandler = GatewayData.() -> Unit
typealias CreateEcuFunc = (name: String, receiver: EcuDataHandler) -> Unit
typealias CreateGatewayFunc = (name: String, receiver: GatewayDataHandler) -> Unit
typealias Interceptor = ResponseData.(request: UdsMessage) -> Boolean

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
class ResponseData(val request: UdsMessage, val simEcu: SimDslEcu) {
    val message: ByteArray
        get() = request.message

    val response
        get() = _response

    val continueMatching
        get() = _continueMatching

    private var _response: ByteArray = ByteArray(0)
    private var _continueMatching: Boolean = false

    fun addOrReplaceEcuTimer(name: String, delay: Duration, handler: TimerTask.() -> Unit) {
        simEcu.addOrReplaceTimer(name, delay, handler)
    }

    fun addEcuInterceptor(name: String = UUID.randomUUID().toString(),
                          duration: Duration = Duration.INFINITE,
                          interceptor: ResponseData.(request: UdsMessage) -> Boolean) =
        simEcu.addInterceptor(name, duration, interceptor)

    fun removeEcuInterceptor(name: String) =
        simEcu.removeInterceptor(name)

    fun respond(responseHex: ByteArray) {
        _response = responseHex
    }

    fun respond(responseHex: String) =
        respond(responseHex.decodeHex())

    fun ack(payload: ByteArray = ByteArray(0)) =
        respond(byteArrayOf((message[0] + 0x40.toByte()).toByte(), message[1]) + payload)

    fun ack(payload: String) =
        ack(payload.decodeHex())

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
class Request(
    val name: String?,
    val requestBytes: ByteArray?,
    val requestRegex: Regex?,
    val responseHandler: ResponseHandler
) {
    init {
        if (requestBytes == null && requestRegex == null) {
            throw IllegalArgumentException("requestBytes or requestRegex must be not null")
        } else if (requestBytes != null && requestRegex != null) {
            throw IllegalArgumentException("Only requestBytes or requestRegex must be set")
        }
    }
}

open class RequestsData {
    var requests: MutableList<Request> = mutableListOf()
    var requestRegexMatchBytes: Int = 10

    private fun regexifyRequestHex(requestHex: String) =
        Regex(
            pattern = requestHex
                .replace(" ", "")
                .uppercase()
                .replace("[]", ".*"),
//            option = RegexOption.IGNORE_CASE
        )

    fun request(request: ByteArray, name: String? = null, response: ResponseHandler = {}): Request {
        val req = Request(name = name, requestBytes = request, requestRegex = null, responseHandler = response)
        requests.add(req)
        return req
    }

    fun request(reqRegex: Regex, name: String? = null, response: ResponseHandler = {}): Request {
        val req = Request(name = name, requestBytes = null, requestRegex = reqRegex, responseHandler = response)
        requests.add(req)
        return req
    }

    /**
     * reqHex is a hex string that
     */
    fun request(reqHex: String, name: String = "", response: ResponseHandler = {}) {
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

private val gateways: MutableList<GatewayData> = mutableListOf()

/**
 * Defines a DoIP-Gateway and the ECUs behind it
 */
fun gateway(name: String, receiver: GatewayDataHandler) {
    val gatewayData = GatewayData(name)
    receiver.invoke(gatewayData)
    gateways.add(gatewayData)
}


open class GatewayData(val name: String) : RequestsData() {
    /**
     * Network address this gateway should bind on (default: 0.0.0.0)
     */
    var localAddress: InetAddress = InetAddress.getByName("0.0.0.0")

    /**
     * Network port this gateway should bind on (default: 13400)
     */
    var localPort: Int = 13400

    /**
     * Default broadcast address for VAM messages (default: 255.255.255.255)
     */
    var broadcastAddress: InetAddress = InetAddress.getByName("255.255.255.255")

    /**
     * Whether VAM broadcasts shall be sent on startup (default: true)
     */
    var broadcastEnable: Boolean = true

    /**
     * The logical address under which the gateway shall be reachable
     */
    var logicalAddress by Delegates.notNull<Int>()

    /**
     * The functional address under which the gateway (and other ecus) shall be reachable
     */
    var functionalAddress by Delegates.notNull<Int>()


    /**
     * Vehicle identifier, 17 chars, will be filled with '0`, or if left null, set to 0xFF
     */
    var vin: String? = null // 17 byte VIN

    /**
     * Group ID of the gateway
     */
    var gid: ByteArray = byteArrayOf(0, 0, 0, 0, 0, 0) // 6 byte group identification (used before mac is set)
    /**
     * Entity ID of the gateway
     */
    var eid: ByteArray = byteArrayOf(0, 0, 0, 0, 0, 0) // 6 byte entity identification (usually MAC)

    private val _ecus: MutableList<EcuData> = mutableListOf()

    val ecus: List<EcuData>
        get() = this._ecus.toList()


    /**
     * Defines an ecu and its properties as behind this gateway
     */
    fun ecu(name: String, receiver: EcuData.() -> Unit) {
        val ecuData = EcuData(name)
        receiver.invoke(ecuData)
        _ecus.add(ecuData)
    }
}

fun start() {
    gateways
        .map { SimDslGateway(it) }
        .forEach { it.start() }
}
