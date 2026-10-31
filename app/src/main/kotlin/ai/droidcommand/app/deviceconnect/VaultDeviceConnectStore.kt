package ai.droidcommand.app.deviceconnect

import ai.droidcommand.config.SecretsVault
import ai.droidcommand.deviceconnect.DeviceConnect
import ai.droidcommand.deviceconnect.DeviceConnectCodec
import ai.droidcommand.deviceconnect.DeviceConnectSnapshot
import ai.droidcommand.deviceconnect.DeviceConnectStore

/** Pairing snapshot in the existing encrypted vault. Never plaintext. */
class VaultDeviceConnectStore(private val vault: SecretsVault) : DeviceConnectStore {
    @Synchronized
    override fun load(): DeviceConnectSnapshot {
        val raw = vault.getSecret(DeviceConnect.STORE_ID) ?: return DeviceConnectSnapshot()
        return DeviceConnectCodec.decode(raw) ?: DeviceConnectSnapshot()
    }

    @Synchronized
    override fun save(snapshot: DeviceConnectSnapshot) {
        vault.putSecret(DeviceConnect.STORE_ID, DeviceConnectCodec.encode(snapshot))
    }
}
