import io.ktor.utils.io.*
import kotlinx.coroutines.*

open class UdsMessage(
    val sourceAddress: Short,
    val targetAddress: Short,
    val targetAddressType: Int,
    val message: ByteArray,
    val output: ByteWriteChannel
) {
    companion object {
        const val PHYSICAL = 0
        const val FUNCTIONAL = 1
    }

    fun respond(data: ByteArray) {
        val response = DoipTcpDiagMessage(targetAddress, sourceAddress, data)

        runBlocking {
            output.writeFully(response.message)
        }
    }
}

fun DoipTcpDiagMessage.toUdsMessage(addressType: Int, output: ByteWriteChannel): UdsMessage =
    UdsMessage(
        sourceAddress = this.sourceAddress,
        targetAddress = this.targetAddress,
        targetAddressType = addressType,
        message = this.payload,
        output = output,
    )
