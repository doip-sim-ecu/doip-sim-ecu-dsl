import io.ktor.network.sockets.*
import io.ktor.util.network.*
import kotlinx.coroutines.channels.SendChannel

interface DoipUdpMessageHandler {
    suspend fun handleUdpMessage(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: NetworkAddress,
        message: DoipUdpMessage
    ) {
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
    }

    suspend fun handleUdpVehicleInformationRequest(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: NetworkAddress,
        message: DoipUdpVehicleInformationRequest
    ) {
    }

    suspend fun handleUdpVehicleInformationRequestWithEid(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: NetworkAddress,
        message: DoipUdpVehicleInformationRequestWithEid
    ) {
    }

    suspend fun handleUdpVehicleInformationRequestWithVIN(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: NetworkAddress,
        message: DoipUdpVehicleInformationRequestWithVIN
    ) {
    }

    suspend fun handleUdpVehicleAnnouncementMessage(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: NetworkAddress,
        message: DoipUdpVehicleAnnouncementMessage
    ) {
    }

    suspend fun handleUdpEntityStatusRequest(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: NetworkAddress,
        message: DoipUdpEntityStatusRequest
    ) {

    }

    suspend fun handleUdpEntityStatusResponse(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: NetworkAddress,
        message: DoipUdpEntityStatusResponse
    ) {

    }

    suspend fun handleUdpDiagnosticPowerModeRequest(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: NetworkAddress,
        message: DoipUdpDiagnosticPowerModeRequest
    ) {

    }

    suspend fun handleUdpDiagnosticPowerModeResponse(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: NetworkAddress,
        message: DoipUdpDiagnosticPowerModeResponse
    ) {

    }

    suspend fun handleUnknownDoipUdpMessage(
        sendChannel: SendChannel<Datagram>,
        sourceAddress: NetworkAddress,
        message: DoipUdpMessage
    ) {

    }

}
