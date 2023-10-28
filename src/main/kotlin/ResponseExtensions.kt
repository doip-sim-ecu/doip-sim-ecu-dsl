public enum class SequenceMode {
    STOP_AT_END,
    WRAP_AROUND
}

/**
 * Returns the given responses, advancing by one after each request, either wrapping around to the beginning,
 * or repeating the last one
 */
public fun RequestResponseData.sequence(vararg responses: String,
    mode: SequenceMode = SequenceMode.STOP_AT_END,
    startIndex: Int = 0
) {
    var sequenceIndex by caller.storedProperty { startIndex }
    respond(responses[sequenceIndex])
    sequenceIndex += 1
    when (mode) {
        SequenceMode.STOP_AT_END -> if (sequenceIndex >= responses.size) sequenceIndex = responses.size - 1
        SequenceMode.WRAP_AROUND -> sequenceIndex %= responses.size
    }
}

/**
 * Returns the given responses, advancing by one after each request, starting at the beginning when the end is reached
 */
public fun RequestResponseData.sequenceWrapAround(vararg responses: String): Unit =
    sequence(*responses, mode = SequenceMode.WRAP_AROUND)

/**
 * Returns the given responses, advancing by one after each request, repeating the last one when the end is reached
 */
public fun RequestResponseData.sequenceStopAtEnd(vararg responses: String): Unit =
    sequence(*responses, mode = SequenceMode.STOP_AT_END)
