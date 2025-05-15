package library

import io.ktor.utils.io.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

public interface OutputChannel {
    public suspend fun writeFully(data: ByteArray)
    public suspend fun flush()
}

public class OutputChannelImpl(private val writeChannel: ByteWriteChannel) : OutputChannel {
    private val mutex = Mutex()

    override suspend fun writeFully(data: ByteArray) {
        mutex.withLock {
            writeChannel.writeFully(data, 0, data.size)
        }
    }

    override suspend fun flush() {
        mutex.withLock {
            writeChannel.flush()
        }
    }
}