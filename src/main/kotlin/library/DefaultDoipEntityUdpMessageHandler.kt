package library

import io.ktor.network.sockets.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.channels.SendChannel
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.math.max

public open class DefaultDoipEntityUdpMessageHandler(
    public val doipEntity: DoipEntity<*>,
    public val config: DoipEntityConfig
) : DoipUdpMessageHandler {
    private val logger: Logger = LoggerFactory.getLogger(DefaultDoipEntityUdpMessageHandler::class.java)


    @Suppress("MemberVisibilityCanBePrivate")
    protected suspend fun sendVamResponse(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: SocketAddress,
    ) {
        val vams = doipEntity.generateVehicleAnnouncementMessages()
        vams.forEach { vam ->
            logger.info("Sending VIR-Response (VAM) for ${vam.logicalAddress.toString(16)} to $sourceAddress")
            sendChannel.send(
                Datagram(
                    packet = ByteReadPacket(vam.asByteArray),
                    address = sourceAddress
                )
            )
        }
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
            logger.info("Received VIR for VIN, responding with VAMs")
            sendVamResponse(sendChannel, sourceAddress)
        } else {
            logger.info("Received VIR for different VIN, not responding")
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
