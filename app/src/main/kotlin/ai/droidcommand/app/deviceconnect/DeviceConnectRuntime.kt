package ai.droidcommand.app.deviceconnect

import ai.droidcommand.deviceconnect.DeviceConnect
import ai.droidcommand.deviceconnect.DeviceConnectDecision
import ai.droidcommand.deviceconnect.DeviceConnectService
import ai.droidcommand.deviceconnect.DeviceConnectTransport
import ai.droidcommand.deviceconnect.DeviceKind
import ai.droidcommand.deviceconnect.DeviceStatus
import ai.droidcommand.remotecontrol.RemoteControlStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceConnectRuntime @Inject constructor(
    val service: DeviceConnectService,
    remoteControlStore: RemoteControlStore,
) {
    private val verifyLock = Object()
    private var verifyLatch: CountDownLatch? = null
    private val accepted = AtomicBoolean(false)
    private val mutableStatus = MutableStateFlow(service.status(false, null, emptyList(), null))
    val liveStatus: StateFlow<DeviceStatus> = mutableStatus.asStateFlow()

    val transport = DeviceConnectTransport(
        service = service,
        localKind = DeviceKind.PHONE,
        onVerify = { code, name ->
            service.rememberVerification(code, name)
            publishStatus()
            val latch = CountDownLatch(1)
            synchronized(verifyLock) {
                verifyLatch = latch
                accepted.set(false)
            }
            val answered = latch.await(DeviceConnect.CHALLENGE_TTL_MS, TimeUnit.MILLISECONDS)
            accepted.get() && answered
        },
        allowControl = { remoteControlStore.load().externalControlEnabled },
    )

    fun status(): DeviceStatus = publishStatus()

    private fun publishStatus(): DeviceStatus {
        val next = service.status(
            listening = transport.isListening,
            port = transport.port,
            addresses = transport.localAddresses(),
            invite = transport.invite(),
        )
        mutableStatus.value = next
        return next
    }

    fun waitHere(password: String): DeviceConnectDecision {
        val prepared = transport.preparePassword(password)
        if (prepared is DeviceConnectDecision.Refused) return prepared
        return try {
            if (!transport.isListening) {
                transport.startListening(DeviceConnect.DEFAULT_PORT)
            }
            publishStatus()
            DeviceConnectDecision.Ok("Waiting on this Wi-Fi.")
        } catch (_: Exception) {
            DeviceConnectDecision.Refused("Could not wait on this Wi-Fi. Try again, or pick another network.")
        }
    }

    fun stopWaiting() {
        transport.stopListening()
        publishStatus()
    }

    fun join(address: String, password: String): DeviceConnectDecision {
        val parsed = DeviceConnect.parseInvite(address)
        val host: String
        val port: Int
        if (parsed != null) {
            host = parsed.first
            port = parsed.second
        } else {
            val parts = address.trim().split(':')
            if (parts.size != 2) return DeviceConnectDecision.Refused("Type an address like 192.168.1.8:17890, or paste the invite.")
            host = parts[0].trim()
            port = parts[1].toIntOrNull() ?: return DeviceConnectDecision.Refused("The port must be a number.")
        }
        return transport.join(host, port, password)
    }

    fun confirmCode(match: Boolean) {
        synchronized(verifyLock) {
            accepted.set(match)
            verifyLatch?.countDown()
        }
    }

    /**
     * Resumes the listener when the owner left "keep waiting" on. Returns null when nothing needed doing.
     *
     * The pairing password is deliberately never written to disk (it is the SPAKE2 secret), so after the process
     * dies [DeviceConnectService.keepListening] reloads `true` but the password is gone and no handshake can be
     * answered. Instead of silently doing nothing, say so, so the screen can ask for the password again.
     * Never throws: this runs from `Application.onCreate` and on the main thread when the Connect screen opens,
     * and a port already in use must not crash the app.
     */
    fun startIfKeptOn(): DeviceConnectDecision? {
        if (!service.keepListening || transport.isListening) return null
        if (transport.pendingPassword == null) return DeviceConnectDecision.Refused(NEEDS_PASSWORD_AGAIN)
        return try {
            transport.startListening(DeviceConnect.DEFAULT_PORT)
            DeviceConnectDecision.Ok("Waiting on this Wi-Fi.")
        } catch (_: Exception) {
            DeviceConnectDecision.Refused("Could not wait on this Wi-Fi. Try again, or pick another network.")
        } finally {
            publishStatus()
        }
    }

    companion object {
        const val NEEDS_PASSWORD_AGAIN =
            "\"Keep waiting\" is on, but the password is not kept after the app restarts. " +
                "Type it again and tap Wait here."
    }
}
