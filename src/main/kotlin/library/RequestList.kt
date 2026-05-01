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

    private val writeLock = Any()
    private val _requests: MutableList<RequestMatcher> = mutableListOf(*requests.toTypedArray())
    private val _runtimeAddedRequests: MutableList<RequestMatcher> = mutableListOf()

    // Volatile snapshot, lazily built on first read. Mutations cheaply invalidate by setting null
    // (so a startup batch of N adds costs O(N), not O(N²)). The next read rebuilds once.
    // The rebuild itself happens under writeLock so it can't race a concurrent mutator.
    @Volatile
    private var index: Map<Int, List<RequestMatcher>>? = null

    @Volatile
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

    override fun add(element: RequestMatcher): Boolean = synchronized(writeLock) {
        val result = _requests.add(element)
        addToRuntime(element)
        invalidateIndex()
        result
    }

    override fun add(index: Int, element: RequestMatcher): Unit = synchronized(writeLock) {
        _requests.add(index, element)
        addToRuntime(element)
        invalidateIndex()
    }

    override fun addAll(index: Int, elements: Collection<RequestMatcher>): Boolean = synchronized(writeLock) {
        val result = _requests.addAll(index, elements)
        addToRuntime(elements)
        invalidateIndex()
        result
    }

    override fun addAll(elements: Collection<RequestMatcher>): Boolean = synchronized(writeLock) {
        val result = _requests.addAll(elements)
        addToRuntime(elements)
        invalidateIndex()
        result
    }

    override fun clear(): Unit = synchronized(writeLock) {
        _requests.clear()
        _runtimeAddedRequests.clear()
        invalidateIndex()
    }

    override fun listIterator(): MutableListIterator<RequestMatcher> =
        _requests.listIterator()

    override fun listIterator(index: Int): MutableListIterator<RequestMatcher> =
        _requests.listIterator(index)

    override fun remove(element: RequestMatcher): Boolean = synchronized(writeLock) {
        val result = _requests.remove(element)
        _runtimeAddedRequests.remove(element)
        invalidateIndex()
        result
    }

    override fun removeAll(elements: Collection<RequestMatcher>): Boolean = synchronized(writeLock) {
        val result = _requests.removeAll(elements)
        _runtimeAddedRequests.removeAll(elements)
        invalidateIndex()
        result
    }

    override fun removeAt(index: Int): RequestMatcher = synchronized(writeLock) {
        val result = _requests.removeAt(index)
        _runtimeAddedRequests.remove(result)
        invalidateIndex()
        result
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
         *
         *  Hot path: a single volatile read of [index] gives an immutable snapshot. The index is built lazily on the
         *  first read after construction or invalidation (so a startup batch of N adds costs O(N), with one rebuild
         *  on the first match). The rebuild itself runs under [writeLock] to exclude concurrent mutators.
         *  Mutations only invalidate the snapshot; in-flight reads continue against the prior reference.
         */
        val idx = index ?: synchronized(writeLock) {
            index ?: _requests.groupBy { it.requestBytes.toIntWithZeroForEmpty() }.also { index = it }
        }
        val searchPattern = message.toIntWithZeroForEmpty()
        for (maskedBytes in 0..4) {
            idx[searchPattern and mask[maskedBytes]]
                ?.forEach {
                    if (it.onlyStartsWith && message.startsWith(it.requestBytes) || message.contentEquals(it.requestBytes)) {
                        if (handlerOnMatch.invoke(it)) {
                            return true
                        }
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
    public fun reset(): Unit = synchronized(writeLock) {
        _requests.removeAll(_runtimeAddedRequests)
        _runtimeAddedRequests.clear()
        invalidateIndex()
    }

    private fun invalidateIndex() {
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
