package library

import io.ktor.network.sockets.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.channels.SendChannel
import org.slf4j.Logger
import org.slf4j.LoggerFactory

public interface DoipUdpMessageHandler {
    private companion object {
        val logger: Logger = LoggerFactory.getLogger(DoipUdpMessageHandler::class.java)
    }

    public suspend fun handleUdpMessage(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: SocketAddress,
        message: DoipUdpMessage
    ) {
        logger.traceIf { "> handleUdpMessage $message" }
        when (message) {
            is DoipUdpHeaderNegAck -> {
                handleUdpHeaderNegAck(sendChannel, sourceAddress, message)
            }
            is DoipUdpVehicleInformationRequest -> {
                handleUdpVehicleInformationRequest(sendChannel, sourceAddress, message)
            }
            is DoipUdpVehicleInformationRequestWithEid -> {
                handleUdpVehicleInformationRequestWithEid(sendChannel, sourceAddress, message)
            }
            is DoipUdpVehicleInformationRequestWithVIN -> {
                handleUdpVehicleInformationRequestWithVIN(sendChannel, sourceAddress, message)
            }
            is DoipUdpVehicleAnnouncementMessage -> {
                handleUdpVehicleAnnouncementMessage(sendChannel, sourceAddress, message)
            }
            is DoipUdpEntityStatusRequest -> {
                handleUdpEntityStatusRequest(sendChannel, sourceAddress, message)
            }
            is DoipUdpEntityStatusResponse -> {
                handleUdpEntityStatusResponse(sendChannel, sourceAddress, message)
            }
            is DoipUdpDiagnosticPowerModeRequest -> {
                handleUdpDiagnosticPowerModeRequest(sendChannel, sourceAddress, message)
            }
            is DoipUdpDiagnosticPowerModeResponse -> {
                handleUdpDiagnosticPowerModeResponse(sendChannel, sourceAddress, message)
            }
            else -> {
                handleUnknownDoipUdpMessage(sendChannel, sourceAddress, message)
            }
        }
    }

    public suspend fun handleUdpHeaderNegAck(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: SocketAddress,
        message: DoipUdpHeaderNegAck
    ) {
        logger.traceIf { "> handleUdpHeaderNegAck $message" }
    }

    public suspend fun handleUdpVehicleInformationRequest(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: SocketAddress,
        message: DoipUdpVehicleInformationRequest
    ) {
        logger.traceIf { "> handleUdpVehicleInformationRequest $message" }
    }

    public suspend fun handleUdpVehicleInformationRequestWithEid(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: SocketAddress,
        message: DoipUdpVehicleInformationRequestWithEid
    ) {
        logger.traceIf { "> handleUdpVehicleInformationRequestWithEid $message" }
    }

    public suspend fun handleUdpVehicleInformationRequestWithVIN(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: SocketAddress,
        message: DoipUdpVehicleInformationRequestWithVIN
    ) {
        logger.traceIf { "> handleUdpVehicleInformationRequestWithVIN $message" }
    }

    public suspend fun handleUdpVehicleAnnouncementMessage(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: SocketAddress,
        message: DoipUdpVehicleAnnouncementMessage
    ) {
        logger.traceIf { "> handleUdpVehicleAnnouncementMessage $message" }
    }

    public suspend fun handleUdpEntityStatusRequest(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: SocketAddress,
        message: DoipUdpEntityStatusRequest
    ) {
        logger.traceIf { "> handleUdpEntityStatusRequest $message" }
    }

    public suspend fun handleUdpEntityStatusResponse(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: SocketAddress,
        message: DoipUdpEntityStatusResponse
    ) {
        logger.traceIf { "> handleUdpEntityStatusResponse $message" }
    }

    public suspend fun handleUdpDiagnosticPowerModeRequest(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: SocketAddress,
        message: DoipUdpDiagnosticPowerModeRequest
    ) {
        logger.traceIf { "> handleUdpDiagnosticPowerModeRequest $message" }
    }

    public suspend fun handleUdpDiagnosticPowerModeResponse(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: SocketAddress,
        message: DoipUdpDiagnosticPowerModeResponse
    ) {
        logger.traceIf { "> handleUdpDiagnosticPowerModeResponse $message" }
    }

    public suspend fun handleUnknownDoipUdpMessage(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: SocketAddress,
        message: DoipUdpMessage
    ) {
        logger.traceIf { "> handleUnknownDoipUdpMessage $message" }
    }

    public suspend fun respondHeaderNegAck(
        sendChannel: SendChannel<Datagram>,
        address: SocketAddress,
        code: Byte
    ) {
        logger.traceIf { "> respondHeaderNegAck $code" }
        sendChannel.send(
            Datagram(
                packet = ByteReadPacket(DoipUdpHeaderNegAck(code).asByteArray),
                address = address
            )
        )
    }
}
