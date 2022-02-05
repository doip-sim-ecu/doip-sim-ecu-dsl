import io.ktor.network.sockets.*
import io.ktor.util.network.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.channels.SendChannel

open class DefaultDoipUdpMessageHandler(
    protected val config: DoipGatewayConfig
) : DoipUdpMessageHandler {

    companion object {
        fun generateVamByGatewayConfig(config: DoipGatewayConfig): DoipUdpVehicleAnnouncementMessage =
            DoipUdpVehicleAnnouncementMessage(config.vin, config.logicalAddress, config.gid, config.eid, 0, 0)
    }

    suspend fun sendVamResponse(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: NetworkAddress,
    ) {
        sendChannel.send(
            Datagram(
                packet = ByteReadPacket(generateVamByGatewayConfig(config).message),
                address = sourceAddress
            )
        )
    }

    override suspend fun handleUdpVehicleInformationRequest(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: NetworkAddress,
        message: DoipUdpVehicleInformationRequest
    ) {
        sendVamResponse(sendChannel, sourceAddress)
    }

    override suspend fun handleUdpVehicleInformationRequestWithEid(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: NetworkAddress,
        message: DoipUdpVehicleInformationRequestWithEid
    ) {
        sendVamResponse(sendChannel, sourceAddress)
    }

    override suspend fun handleUdpVehicleInformationRequestWithVIN(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: NetworkAddress,
        message: DoipUdpVehicleInformationRequestWithVIN
    ) {
        sendVamResponse(sendChannel, sourceAddress)
    }

    override suspend fun handleUdpEntityStatusRequest(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: NetworkAddress,
        message: DoipUdpEntityStatusRequest
    ) {
        sendChannel.send(
            Datagram(
                packet = ByteReadPacket(
                    DoipUdpEntityStatusResponse(0, 255.toByte(), 0, 0xFFFF)
                        .message
                ),
                address = sourceAddress
            )
        )
    }

    override suspend fun handleUdpDiagnosticPowerModeRequest(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: NetworkAddress,
        message: DoipUdpDiagnosticPowerModeRequest
    ) {
        sendChannel.send(
            Datagram(
                packet = ByteReadPacket(
                    DoipUdpDiagnosticPowerModeResponse(0)
                        .message
                ),
                address = sourceAddress
            )
        )
    }
}
