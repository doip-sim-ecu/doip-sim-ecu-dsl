package library

import kotlinx.coroutines.*

public open class UdsMessage(
    public val sourceAddress: Short,
    public val targetAddress: Short,
    public val targetAddressType: Int,
    public val targetAddressPhysical: Short,
    public val message: ByteArray,
    public val output: OutputChannel
) {
    public companion object {
        public const val PHYSICAL: Int = 0
        public const val FUNCTIONAL: Int = 1
    }

    public fun respond(data: ByteArray) {
        val response = DoipTcpDiagMessage(targetAddressPhysical, sourceAddress, data)

        runBlocking {
            output.writeFully(response.asByteArray)
        }
    }
}

public fun DoipTcpDiagMessage.toUdsMessage(addressType: Int, output: OutputChannel, targetAddressPhysical: Short): UdsMessage =
    UdsMessage(
        sourceAddress = this.sourceAddress,
        targetAddress = this.targetAddress,
        targetAddressType = addressType,
        targetAddressPhysical = targetAddressPhysical,
        message = this.payload,
        output = output
    )
