package library

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
            output: OutputChannel
        ) {
            val handler = list.filter { it.existsTargetAddress(diagMessage.targetAddress) }
            handler.forEach { it.onIncomingDiagMessage(diagMessage, output) }
        }
    }
}