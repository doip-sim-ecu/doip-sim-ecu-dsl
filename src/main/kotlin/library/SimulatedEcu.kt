package library

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

open class SimulatedEcu(val config: EcuConfig) {
    val name: String =
        config.name

    private val logger: Logger = LoggerFactory.getLogger(SimulatedEcu::class.java)

    private val isBusy: AtomicBoolean = AtomicBoolean(false)

    /**
     * Handler for an incoming diagnostic message when the ECU isn't busy
     */
    open fun handleRequest(request: UdsMessage) {
        logger.debugIf { "Handle Request message: ${request.message.toHexString(limit = 20)} for $name" }
    }

    /**
     * Handler that is called when the ecu is currently busy with another request
     */
    open fun handleRequestIfBusy(request: UdsMessage) {
        // Busy NRC
        logger.debugIf { "ECU $name is busy, sending busy-NRC" }
        request.respond(byteArrayOf(0x7f, request.message[0], 0x21))
    }

    /**
     * Called on incoming diagnostic messages for this ECU
     */
    open fun onIncomingUdsMessage(request: UdsMessage) {
        return if (isBusy.compareAndSet(false, true)) {
            try {
                handleRequest(request)
            } finally {
                isBusy.set(false)
            }
        } else {
            handleRequestIfBusy(request)
        }
    }
}
