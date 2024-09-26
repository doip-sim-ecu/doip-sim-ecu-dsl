package library

import io.ktor.utils.io.ByteWriteChannel

public open class GroupDoipTcpConnectionMessageHandler(
    entities: List<DoipEntity<*>>,
    socket: DoipTcpSocket,
    tlsOptions: TlsOptions?,
) : DefaultDoipEntityTcpConnectionMessageHandler(entities.first(), socket, entities.first().config.logicalAddress.toShort(), entities.first(), tlsOptions) {

    private val diagnosticMessageHandler: List<DiagnosticMessageHandler> = entities.map { it }

    init {
        super.diagMessageHandler = GroupHandler(diagnosticMessageHandler)
    }

    public class GroupHandler(private val list: List<DiagnosticMessageHandler>) : DiagnosticMessageHandler {
        override fun existsTargetAddress(targetAddress: Short): Boolean =
            list.any { it.existsTargetAddress(targetAddress) }

        override suspend fun onIncomingDiagMessage(
            diagMessage: DoipTcpDiagMessage,
            output: ByteWriteChannel
        ) {
            val handler = list.firstOrNull { it.existsTargetAddress(diagMessage.targetAddress) } ?: list.first()
            handler.onIncomingDiagMessage(diagMessage, output)
        }
    }
}