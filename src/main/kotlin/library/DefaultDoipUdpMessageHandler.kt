package library

import io.ktor.network.sockets.*
import io.ktor.util.network.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.channels.SendChannel
import kotlin.math.max

open class DefaultDoipUdpMessageHandler(
    val doipEntity: DoipEntity,
    val config: DoipEntityConfig
) : DoipUdpMessageHandler {

    companion object {
        fun generateVamByEntityConfig(config: DoipEntityConfig): DoipUdpVehicleAnnouncementMessage =
            DoipUdpVehicleAnnouncementMessage(config.vin, config.logicalAddress, config.gid, config.eid, 0, 0)
    }

    suspend fun sendVamResponse(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: NetworkAddress,
    ) {
        sendChannel.send(
            Datagram(
                packet = ByteReadPacket(generateVamByEntityConfig(config).message),
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
        if (config.eid.contentEquals(message.eid)) {
            sendVamResponse(sendChannel, sourceAddress)
        }
    }

    override suspend fun handleUdpVehicleInformationRequestWithVIN(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: NetworkAddress,
        message: DoipUdpVehicleInformationRequestWithVIN
    ) {
        if (config.vin.contentEquals(message.vin)) {
            sendVamResponse(sendChannel, sourceAddress)
        }
    }

    override suspend fun handleUdpEntityStatusRequest(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: NetworkAddress,
        message: DoipUdpEntityStatusRequest
    ) {
        sendChannel.send(
            Datagram(
                packet = ByteReadPacket(
                    DoipUdpEntityStatusResponse(
                        config.nodeType.value,
                        255.toByte(),
                        max(doipEntity.connectionHandlers.size, 255).toByte(),
                        config.maxDataSize
                    )
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
