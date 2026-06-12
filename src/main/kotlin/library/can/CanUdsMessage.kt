package library.can

import kotlinx.coroutines.runBlocking
import library.OutputChannel
import library.UdsMessage

/**
 * A UDS message received via ISO-TP on a CAN bus.
 *
 * The inherited [sourceAddress]/[targetAddress] fields are truncated to [Short]
 * for compatibility with the DoIP-based API; [requestCanId] and [responseCanId]
 * carry the unmodified CAN identifiers.
 */
public class CanUdsMessage(
    targetAddressType: Int,
    message: ByteArray,
    output: OutputChannel,
    /**
     * CAN id the request was received on (physical or functional)
     */
    public val requestCanId: Int,
    /**
     * CAN id the response is sent on
     */
    public val responseCanId: Int,
) : UdsMessage(
    sourceAddress = requestCanId.toShort(),
    targetAddress = requestCanId.toShort(),
    targetAddressType = targetAddressType,
    targetAddressPhysical = responseCanId.toShort(),
    message = message,
    output = output,
) {
    override fun respond(data: ByteArray) {
        // no transport header - the output channel performs a complete ISO-TP transmission
        runBlocking {
            output.writeFully(data)
        }
    }
}
