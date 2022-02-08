enum class SequenceMode {
    STOP_AT_END,
    WRAP_AROUND
}

@Suppress("UNUSED_VALUE")
fun RequestResponseData.sequence(vararg responses: String,
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

fun RequestResponseData.sequenceWrapAround(vararg responses: String) =
    sequence(*responses, mode = SequenceMode.WRAP_AROUND)

fun RequestResponseData.sequenceStopAtEnd(vararg responses: String) =
    sequence(*responses, mode = SequenceMode.STOP_AT_END)
