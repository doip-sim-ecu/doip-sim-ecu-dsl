package library

import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.runBlocking

public interface OutputChannel {
    public suspend fun writeFully(data: ByteArray)
    public suspend fun flush()
}

public class OutputChannelImpl(private val writeChannel: ByteWriteChannel) : OutputChannel {
    override suspend fun writeFully(data: ByteArray) {
        synchronized<Unit>(writeChannel) {
            runBlocking {
                writeChannel.writeFully(data, 0, data.size)
            }
        }
    }

    override suspend fun flush() {
        synchronized(writeChannel) {
            writeChannel.flush()
        }
    }
}