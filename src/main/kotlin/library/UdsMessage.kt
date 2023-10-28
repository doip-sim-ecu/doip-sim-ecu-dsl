package library

import io.ktor.utils.io.*
import kotlinx.coroutines.*

public open class UdsMessage(
    public val sourceAddress: Short,
    public val targetAddress: Short,
    public val targetAddressType: Int,
    public val message: ByteArray,
    public val output: ByteWriteChannel
) {
    public companion object {
        public const val PHYSICAL: Int = 0
        public const val FUNCTIONAL: Int = 1
    }

    public fun respond(data: ByteArray) {
        val response = DoipTcpDiagMessage(targetAddress, sourceAddress, data)

        runBlocking {
            output.writeFully(response.asByteArray)
        }
    }
}

public fun DoipTcpDiagMessage.toUdsMessage(addressType: Int, output: ByteWriteChannel): UdsMessage =
    UdsMessage(
        sourceAddress = this.sourceAddress,
        targetAddress = this.targetAddress,
        targetAddressType = addressType,
        message = this.payload,
        output = output
    )
