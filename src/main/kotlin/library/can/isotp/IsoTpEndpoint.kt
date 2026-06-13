package library.can.isotp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import library.OutputChannel
import library.can.CanFrame
import library.can.CanTransport
import library.can.isotp.IsoTpFlowControlFrame.Companion.CONTINUE_TO_SEND
import library.can.isotp.IsoTpFlowControlFrame.Companion.OVERFLOW
import library.can.isotp.IsoTpFlowControlFrame.Companion.WAIT
import library.toHexString
import org.slf4j.LoggerFactory

public class IsoTpException(message: String) : Exception(message)

/**
 * One ISO-TP endpoint on a CAN bus: receives segmented messages addressed to
 * [physicalRxId] (and single frames to [functionalRxId]), reassembles them and
 * hands them to [handler]; transmits segmented messages on [txId].
 *
 * [handler] is invoked on the endpoint's frame-processing coroutine and must not
 * block - dispatch the actual work to another coroutine (otherwise flow control
 * frames for concurrently transmitted responses can't be processed).
 */
public class IsoTpEndpoint(
    private val transport: CanTransport,
    private val physicalRxId: Int,
    private val functionalRxId: Int?,
    private val txId: Int,
    private val options: IsoTpOptions,
    private val handler: (payload: ByteArray, functional: Boolean) -> Unit,
) {
    private val logger = LoggerFactory.getLogger(IsoTpEndpoint::class.java)

    private val frames = Channel<CanFrame>(Channel.UNLIMITED)
    private val flowControls = Channel<IsoTpFlowControlFrame>(Channel.UNLIMITED)
    private val txMutex = Mutex()
    private val jobs = mutableListOf<Job>()

    /**
     * Response path for [library.UdsMessage] - writing to it performs a full
     * (possibly segmented) ISO-TP transmission
     */
    public val outputChannel: OutputChannel = IsoTpOutputChannel()

    public fun start(scope: CoroutineScope) {
        // UNDISPATCHED so the subscription to the (non-buffering) shared flow is
        // already registered when start() returns - frames sent afterwards can't be lost
        jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.incomingFrames.collect { frame ->
                if (frame.id == physicalRxId || (functionalRxId != null && frame.id == functionalRxId)) {
                    frames.send(frame)
                }
            }
        }
        jobs += scope.launch {
            while (currentCoroutineContext().isActive) {
                processPdu(receivePdu() ?: continue)
            }
        }
    }

    public fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    /**
     * Transmits a complete ISO-TP message on [txId], segmenting it and honoring
     * the receiver's flow control if it doesn't fit a single frame.
     * Concurrent calls are serialized.
     */
    public suspend fun send(payload: ByteArray) {
        txMutex.withLock {
            if (payload.size <= IsoTpFraming.maxSingleFramePayload(options)) {
                sendData(IsoTpFraming.encodeSingleFrame(payload, options))
            } else {
                sendSegmented(payload)
            }
        }
    }

    private suspend fun sendData(data: ByteArray) {
        transport.sendFrame(IsoTpFraming.toCanFrame(txId, data, options))
    }

    private suspend fun sendSegmented(payload: ByteArray) {
        // drop stale flow control frames from a previous (possibly aborted) transmission
        while (flowControls.tryReceive().isSuccess) {
            // drain
        }

        val ffPayloadSize = IsoTpFraming.firstFramePayloadSize(payload.size, options)
        val cfPayloadSize = IsoTpFraming.consecutiveFramePayloadSize(options)
        sendData(IsoTpFraming.encodeFirstFrame(payload.size, payload.copyOfRange(0, ffPayloadSize), options))

        var offset = ffPayloadSize
        var sequenceNumber = 1
        var waitCount = 0
        // STmin separates consecutive frames; it doesn't apply before the very first one
        var consecutiveFramesSent = false
        while (offset < payload.size) {
            val fc = withTimeoutOrNull(options.nBsTimeout) { flowControls.receive() }
                ?: throw IsoTpException("Timeout waiting for flow control while sending on 0x${txId.toString(16)} (N_Bs)")
            when (fc.status) {
                CONTINUE_TO_SEND -> {
                    waitCount = 0
                    val stMinMillis = IsoTpFraming.stMinToMillis(fc.stMin)
                    var framesInBlock = 0
                    while (offset < payload.size && (fc.blockSize == 0 || framesInBlock < fc.blockSize)) {
                        if (stMinMillis > 0 && consecutiveFramesSent) {
                            delay(stMinMillis)
                        }
                        val chunk = payload.copyOfRange(offset, minOf(offset + cfPayloadSize, payload.size))
                        sendData(IsoTpFraming.encodeConsecutiveFrame(sequenceNumber, chunk, options))
                        sequenceNumber = (sequenceNumber + 1) and 0x0F
                        offset += chunk.size
                        framesInBlock++
                        consecutiveFramesSent = true
                    }
                }
                WAIT -> {
                    waitCount++
                    if (waitCount > options.maxWaitFrames) {
                        throw IsoTpException("Receiver sent more than ${options.maxWaitFrames} flow control WAIT frames, aborting transmission on 0x${txId.toString(16)}")
                    }
                }
                OVERFLOW ->
                    throw IsoTpException("Receiver signaled flow control OVERFLOW for transmission on 0x${txId.toString(16)}")
            }
        }
    }

    private class ReceivedPdu(val pdu: IsoTpPdu, val functional: Boolean)

    /**
     * Suspends until a decodable ISO-TP frame arrives, skipping (and logging)
     * undecodable frames so a single corrupt frame doesn't abort a reception
     */
    private suspend fun receiveDecodablePdu(): ReceivedPdu {
        while (true) {
            val frame = frames.receive()
            val pdu = IsoTpFraming.decode(frame.data)
            if (pdu == null) {
                logger.warn("Ignoring invalid ISO-TP frame on 0x${frame.id.toString(16)}: ${frame.data.toHexString(limit = 10)}")
                continue
            }
            return ReceivedPdu(pdu, functional = functionalRxId != null && frame.id == functionalRxId && frame.id != physicalRxId)
        }
    }

    /**
     * Returns the next decodable PDU, or null only when [timeout] elapses first
     * (a null result always means a timeout, never a malformed frame)
     */
    private suspend fun receivePdu(timeout: kotlin.time.Duration? = null): ReceivedPdu? =
        if (timeout == null) {
            receiveDecodablePdu()
        } else {
            withTimeoutOrNull(timeout) { receiveDecodablePdu() }
        }

    private suspend fun processPdu(received: ReceivedPdu) {
        val pdu = received.pdu
        when (pdu) {
            is IsoTpSingleFrame ->
                handler(pdu.payload, received.functional)
            is IsoTpFirstFrame ->
                if (received.functional) {
                    logger.warn("Ignoring first frame on functional id 0x${functionalRxId?.toString(16)} - segmented functional requests aren't allowed")
                } else {
                    receiveSegmented(pdu)
                }
            is IsoTpConsecutiveFrame ->
                logger.warn("Ignoring unexpected consecutive frame on 0x${physicalRxId.toString(16)} - no reception in progress")
            is IsoTpFlowControlFrame ->
                flowControls.send(pdu)
        }
    }

    private suspend fun receiveSegmented(firstFrame: IsoTpFirstFrame) {
        if (firstFrame.totalLength > options.maxMessageSize) {
            logger.warn("Rejecting incoming message of ${firstFrame.totalLength} bytes on 0x${physicalRxId.toString(16)} (maxMessageSize = ${options.maxMessageSize})")
            sendData(IsoTpFraming.encodeFlowControlFrame(OVERFLOW, 0, 0, options))
            return
        }
        val buffer = ByteArray(firstFrame.totalLength)
        var received = minOf(firstFrame.payload.size, buffer.size)
        firstFrame.payload.copyInto(buffer, 0, 0, received)

        sendData(IsoTpFraming.encodeFlowControlFrame(CONTINUE_TO_SEND, options.blockSize, options.stMin, options))
        var expectedSequenceNumber = 1
        var framesUntilFlowControl = options.blockSize

        while (received < buffer.size) {
            val next = receivePdu(options.nCrTimeout)
            if (next == null) {
                logger.warn("Aborting reception on 0x${physicalRxId.toString(16)} ($received/${buffer.size} bytes received)")
                return
            }
            when (val pdu = next.pdu) {
                is IsoTpConsecutiveFrame -> {
                    if (next.functional) {
                        logger.warn("Ignoring consecutive frame on functional id")
                        continue
                    }
                    if (pdu.sequenceNumber != expectedSequenceNumber) {
                        logger.warn("Aborting reception on 0x${physicalRxId.toString(16)}: expected sequence number $expectedSequenceNumber, got ${pdu.sequenceNumber}")
                        return
                    }
                    val chunk = minOf(pdu.payload.size, buffer.size - received)
                    pdu.payload.copyInto(buffer, received, 0, chunk)
                    received += chunk
                    expectedSequenceNumber = (expectedSequenceNumber + 1) and 0x0F
                    if (framesUntilFlowControl > 0 && received < buffer.size) {
                        framesUntilFlowControl--
                        if (framesUntilFlowControl == 0) {
                            sendData(IsoTpFraming.encodeFlowControlFrame(CONTINUE_TO_SEND, options.blockSize, options.stMin, options))
                            framesUntilFlowControl = options.blockSize
                        }
                    }
                }
                is IsoTpFlowControlFrame ->
                    // flow control for a transmission running concurrently to this reception
                    flowControls.send(pdu)
                is IsoTpSingleFrame, is IsoTpFirstFrame -> {
                    if (next.functional && pdu is IsoTpSingleFrame) {
                        handler(pdu.payload, true)
                        continue
                    }
                    logger.warn("New ISO-TP message while reception on 0x${physicalRxId.toString(16)} was in progress - restarting")
                    processPdu(next)
                    return
                }
            }
        }
        handler(buffer, false)
    }

    private inner class IsoTpOutputChannel : OutputChannel {
        override suspend fun writeFully(data: ByteArray) {
            send(data)
        }

        override suspend fun flush() {
            // each write is already a complete ISO-TP transmission
        }
    }
}
