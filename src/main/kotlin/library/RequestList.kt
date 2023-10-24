package library

import RequestMatcher

public class RequestList(
    requests: List<RequestMatcher>
) : MutableList<RequestMatcher> {
    public companion object {
        private val mask: Array<Int> = arrayOf(
            0xFFFFFFFF.toInt(),
            0xFFFFFF00.toInt(),
            0xFFFF0000.toInt(),
            0xFF000000.toInt(),
            0x00000000,
        )
    }

    private val _requests: MutableList<RequestMatcher> = mutableListOf(*requests.toTypedArray())
    private val _runtimeAddedRequests: MutableList<RequestMatcher> = mutableListOf()

    private var index: Map<Int, List<RequestMatcher>>? = null
    private var isRuntime: Boolean = false

    override val size: Int
        get() = _requests.size

    override fun contains(element: RequestMatcher): Boolean =
        _requests.contains(element)

    override fun containsAll(elements: Collection<RequestMatcher>): Boolean =
        _requests.containsAll(elements)

    override fun get(index: Int): RequestMatcher =
        _requests[index]

    override fun indexOf(element: RequestMatcher): Int =
        _requests.indexOf(element)

    override fun isEmpty(): Boolean =
        _requests.isEmpty()

    override fun iterator(): MutableIterator<RequestMatcher> =
        _requests.iterator()

    override fun lastIndexOf(element: RequestMatcher): Int =
        _requests.lastIndexOf(element)

    override fun add(element: RequestMatcher): Boolean {
        val result = _requests.add(element)
        addToRuntime(element)
        updateIndex()
        return result
    }

    override fun add(index: Int, element: RequestMatcher) {
        _requests.add(index, element)
        addToRuntime(element)
        updateIndex()
    }

    override fun addAll(index: Int, elements: Collection<RequestMatcher>): Boolean {
        val result = _requests.addAll(index, elements)
        addToRuntime(elements)
        updateIndex()
        return result
    }

    override fun addAll(elements: Collection<RequestMatcher>): Boolean {
        val result = _requests.addAll(elements)
        addToRuntime(elements)
        updateIndex()
        return result
    }

    override fun clear() {
        _requests.clear()
        _runtimeAddedRequests.clear()
        updateIndex()
    }

    override fun listIterator(): MutableListIterator<RequestMatcher> =
        _requests.listIterator()

    override fun listIterator(index: Int): MutableListIterator<RequestMatcher> =
        _requests.listIterator(index)

    override fun remove(element: RequestMatcher): Boolean {
        val result = _requests.remove(element)
        _runtimeAddedRequests.remove(element)
        updateIndex()
        return result
    }

    override fun removeAll(elements: Collection<RequestMatcher>): Boolean {
        val result = _requests.removeAll(elements)
        _runtimeAddedRequests.removeAll(elements)
        updateIndex()
        return result
    }

    override fun removeAt(index: Int): RequestMatcher {
        val result = _requests.removeAt(index)
        _runtimeAddedRequests.remove(result)
        updateIndex()
        return result
    }

    override fun retainAll(elements: Collection<RequestMatcher>): Boolean =
        throw UnsupportedOperationException("not supported")

    override fun set(index: Int, element: RequestMatcher): RequestMatcher =
        throw UnsupportedOperationException("not supported")

    override fun subList(fromIndex: Int, toIndex: Int): MutableList<RequestMatcher> =
        throw UnsupportedOperationException("not supported")

    /**
     * Looks up the message in the requests, and executes handlerOnMatch on the matching entries, until handlerOnMatch
     * returns true
     */
    public fun findMessageAndHandle(
        message: ByteArray,
        handlerOnMatch: (RequestMatcher) -> Boolean
    ): Boolean {
        /** How indexing works:
         *  We create an index based on (up to) the first 4 bytes of the request definition, and put it into a map<int, list<request>>.
         *  Since most request definitions should be only for the uds services with their did/rid without large payloads,
         *  this should scale fairly well for not having to iterate through long lists of requests.
         */
        var index = this.index
        if (index == null) {
            // create the index, if none exists
            index = _requests.groupBy { it.requestBytes.toIntWithZeroForEmpty() }
            this.index = index
        }

        // convert the request message into the search pattern for the index
        val searchPattern = message.toIntWithZeroForEmpty()
        for (maskedBytes in 0..4) {
            // since the request definition may be shorter than the actual request, we start with
            // the highest amount possibly matching bytes, and mask out more and more bytes
            index[searchPattern and mask[maskedBytes]]?.forEach {
                val wasHandled = handlerOnMatch.invoke(it)
                if (wasHandled) {
                    return true
                }
            }
        }

        return false
    }

    internal fun simStarted() {
        isRuntime = true
    }

    /**
     * Removes requests which were added at runtime
     */
    public fun reset() {
        _requests.removeAll(_runtimeAddedRequests)
        _runtimeAddedRequests.clear()
        updateIndex()
    }

    private fun updateIndex() {
        index = null
    }

    /**
     * Converts up to the first 4 bytes of the byte-array into an int
     */
    private fun ByteArray.toIntWithZeroForEmpty(): Int =
        when (this.size) {
            0 -> 0
            1 -> this[0].toInt() and 0xFF shl 24
            2 -> (this[0].toInt() and 0xFF shl 24) or (this[1].toInt() and 0xFF shl 16)
            3 -> (this[0].toInt() and 0xFF shl 24) or (this[1].toInt() and 0xFF shl 16) or (this[2].toInt() and 0xFF shl 8)
            else -> (this[0].toInt() and 0xFF shl 24) or (this[1].toInt() and 0xFF shl 16) or (this[2].toInt() and 0xFF shl 8) or (this[3].toInt() and 0xFF)
        }

    private fun addToRuntime(element: RequestMatcher) {
        if (isRuntime) {
            _runtimeAddedRequests.add(element)
        }
    }

    private fun addToRuntime(elements: Collection<RequestMatcher>) {
        if (isRuntime) {
            _runtimeAddedRequests.addAll(elements)
        }
    }
}