package library

import io.ktor.network.sockets.*
import io.ktor.util.network.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.channels.SendChannel
import org.slf4j.Logger
import org.slf4j.LoggerFactory

interface DoipUdpMessageHandler {
    companion object {
        val logger: Logger = LoggerFactory.getLogger(DoipUdpMessageHandler::class.java)
    }

    suspend fun handleUdpMessage(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: NetworkAddress,
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

    suspend fun handleUdpHeaderNegAck(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: NetworkAddress,
        message: DoipUdpHeaderNegAck
    ) {
        logger.traceIf { "> handleUdpHeaderNegAck $message" }
    }

    suspend fun handleUdpVehicleInformationRequest(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: NetworkAddress,
        message: DoipUdpVehicleInformationRequest
    ) {
        logger.traceIf { "> handleUdpVehicleInformationRequest $message" }
    }

    suspend fun handleUdpVehicleInformationRequestWithEid(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: NetworkAddress,
        message: DoipUdpVehicleInformationRequestWithEid
    ) {
        logger.traceIf { "> handleUdpVehicleInformationRequestWithEid $message" }
    }

    suspend fun handleUdpVehicleInformationRequestWithVIN(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: NetworkAddress,
        message: DoipUdpVehicleInformationRequestWithVIN
    ) {
        logger.traceIf { "> handleUdpVehicleInformationRequestWithVIN $message" }
    }

    suspend fun handleUdpVehicleAnnouncementMessage(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: NetworkAddress,
        message: DoipUdpVehicleAnnouncementMessage
    ) {
        logger.traceIf { "> handleUdpVehicleAnnouncementMessage $message" }
    }

    suspend fun handleUdpEntityStatusRequest(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: NetworkAddress,
        message: DoipUdpEntityStatusRequest
    ) {
        logger.traceIf { "> handleUdpEntityStatusRequest $message" }
    }

    suspend fun handleUdpEntityStatusResponse(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: NetworkAddress,
        message: DoipUdpEntityStatusResponse
    ) {
        logger.traceIf { "> handleUdpEntityStatusResponse $message" }
    }

    suspend fun handleUdpDiagnosticPowerModeRequest(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: NetworkAddress,
        message: DoipUdpDiagnosticPowerModeRequest
    ) {
        logger.traceIf { "> handleUdpDiagnosticPowerModeRequest $message" }
    }

    suspend fun handleUdpDiagnosticPowerModeResponse(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: NetworkAddress,
        message: DoipUdpDiagnosticPowerModeResponse
    ) {
        logger.traceIf { "> handleUdpDiagnosticPowerModeResponse $message" }
    }

    suspend fun handleUnknownDoipUdpMessage(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: NetworkAddress,
        message: DoipUdpMessage
    ) {
        logger.traceIf { "> handleUnknownDoipUdpMessage $message" }
    }

    suspend fun respondHeaderNegAck(
        sendChannel: SendChannel<Datagram>,
        address: NetworkAddress,
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

    suspend fun parseMessage(datagram: Datagram): DoipUdpMessage =
        DoipUdpMessageParser.parseUDP(datagram.packet)
}
