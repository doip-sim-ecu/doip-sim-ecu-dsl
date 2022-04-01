package library

import io.ktor.network.sockets.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.channels.SendChannel
import kotlin.math.max

open class DefaultDoipEntityUdpMessageHandler(
    val doipEntity: DoipEntity,
    val config: DoipEntityConfig
) : DoipUdpMessageHandler {

    companion object {
        fun generateVamByEntityConfig(config: DoipEntityConfig): DoipUdpVehicleAnnouncementMessage =
            DoipUdpVehicleAnnouncementMessage(config.vin, config.logicalAddress, config.gid, config.eid, 0, 0)
    }

    suspend fun sendVamResponse(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: SocketAddress,
    ) {
        sendChannel.send(
            Datagram(
                packet = ByteReadPacket(generateVamByEntityConfig(config).asByteArray),
                address = sourceAddress
            )
        )
    }

    override suspend fun handleUdpVehicleInformationRequest(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: SocketAddress,
        message: DoipUdpVehicleInformationRequest
    ) {
        sendVamResponse(sendChannel, sourceAddress)
    }

    override suspend fun handleUdpVehicleInformationRequestWithEid(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: SocketAddress,
        message: DoipUdpVehicleInformationRequestWithEid
    ) {
        if (config.eid.contentEquals(message.eid)) {
            sendVamResponse(sendChannel, sourceAddress)
        }
    }

    override suspend fun handleUdpVehicleInformationRequestWithVIN(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: SocketAddress,
        message: DoipUdpVehicleInformationRequestWithVIN
    ) {
        if (config.vin.contentEquals(message.vin)) {
            sendVamResponse(sendChannel, sourceAddress)
        }
    }

    override suspend fun handleUdpEntityStatusRequest(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: SocketAddress,
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
                        .asByteArray
                ),
                address = sourceAddress
            )
        )
    }

    override suspend fun handleUdpDiagnosticPowerModeRequest(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: SocketAddress,
        message: DoipUdpDiagnosticPowerModeRequest
    ) {
        sendChannel.send(
            Datagram(
                packet = ByteReadPacket(
                    DoipUdpDiagnosticPowerModeResponse(0)
                        .asByteArray
                ),
                address = sourceAddress
            )
        )
    }
}
