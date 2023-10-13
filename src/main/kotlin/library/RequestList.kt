package library

import RequestMatcher
import org.slf4j.Logger
import org.slf4j.LoggerFactory

public class RequestList(
    requests: List<RequestMatcher>
) : MutableList<RequestMatcher> {
    public companion object {
        public var indexActive: Boolean = true
        private val mask: Array<Int> = arrayOf(
            0xFFFFFFFF.toInt(),
            0xFFFFFF00.toInt(),
            0xFFFF0000.toInt(),
            0xFF000000.toInt(),
            0x00000000,
        )
    }

    private val logger: Logger = LoggerFactory.getLogger(RequestList::class.java)

    private val requests: MutableList<RequestMatcher> = mutableListOf(*requests.toTypedArray())

    private var index: Map<Int, List<RequestMatcher>>? = null

    override val size: Int
        get() = requests.size

    override fun contains(element: RequestMatcher): Boolean =
        requests.contains(element)

    override fun containsAll(elements: Collection<RequestMatcher>): Boolean =
        requests.containsAll(elements)

    override fun get(index: Int): RequestMatcher =
        requests[index]

    override fun indexOf(element: RequestMatcher): Int =
        requests.indexOf(element)

    override fun isEmpty(): Boolean =
        requests.isEmpty()

    override fun iterator(): MutableIterator<RequestMatcher> =
        requests.iterator()

    override fun lastIndexOf(element: RequestMatcher): Int =
        requests.lastIndexOf(element)

    override fun add(element: RequestMatcher): Boolean =
        requests.add(element).also { updateIndex() }

    override fun add(index: Int, element: RequestMatcher): Unit =
        requests.add(index, element).also { updateIndex() }

    override fun addAll(index: Int, elements: Collection<RequestMatcher>): Boolean =
        requests.addAll(index, elements).also { updateIndex() }

    override fun addAll(elements: Collection<RequestMatcher>): Boolean =
        requests.addAll(elements).also { updateIndex() }

    override fun clear(): Unit =
        requests.clear().also { updateIndex() }

    override fun listIterator(): MutableListIterator<RequestMatcher> =
        requests.listIterator()

    override fun listIterator(index: Int): MutableListIterator<RequestMatcher> =
        requests.listIterator(index)

    override fun remove(element: RequestMatcher): Boolean =
        requests.remove(element).also { updateIndex() }

    override fun removeAll(elements: Collection<RequestMatcher>): Boolean =
        requests.removeAll(elements).also { updateIndex() }

    override fun removeAt(index: Int): RequestMatcher =
        requests.removeAt(index).also { updateIndex() }

    override fun retainAll(elements: Collection<RequestMatcher>): Boolean =
        requests.retainAll(elements).also { updateIndex() }

    override fun set(index: Int, element: RequestMatcher): RequestMatcher =
        requests.set(index, element).also { updateIndex() }

    override fun subList(fromIndex: Int, toIndex: Int): MutableList<RequestMatcher> =
        requests.subList(fromIndex, toIndex)

    public fun findMessageAndHandle(
        ecuName: String,
        message: ByteArray,
        handlerOnMatch: (RequestMatcher) -> Boolean
    ): Boolean {
        if (!indexActive) {
            for (requestIter in requests) {
                val matches = requestIter.matches(message)

                logger.traceIf { "Request for ${ecuName}: '${message.toHexString(limit = 10)}' try match '$requestIter' -> $matches" }

                if (matches) {
                    val wasHandled = handlerOnMatch.invoke(requestIter)
                    if (!wasHandled) {
                        continue
                    }
                    return true
                }
            }
            return false
        } else {
            var index = this.index
            if (index == null) {
                index = requests.groupBy { it.requestBytes.toIntWithZeroForEmpty() }
                this.index = index
            }

            val searchPattern = message.toIntWithZeroForEmpty()
            for (maskedBytes in 0..4) {
                index[searchPattern and mask[maskedBytes]]?.forEach {
                    val wasHandled = handlerOnMatch.invoke(it)
                    if (wasHandled) {
                        return true
                    }
                }
            }

            return false
        }
    }

    private fun updateIndex() {
        index = null
    }

    private fun ByteArray.toIntWithZeroForEmpty(): Int =
        when (this.size) {
            0 -> 0
            1 -> this[0].toInt() and 0xFF shl 24
            2 -> (this[0].toInt() and 0xFF shl 24) or (this[1].toInt() and 0xFF shl 16)
            3 -> (this[0].toInt() and 0xFF shl 24) or (this[1].toInt() and 0xFF shl 16) or (this[2].toInt() and 0xFF shl 8)
            else -> (this[0].toInt() and 0xFF shl 24) or (this[1].toInt() and 0xFF shl 16) or (this[2].toInt() and 0xFF shl 8) or (this[3].toInt() and 0xFF)
        }
}
