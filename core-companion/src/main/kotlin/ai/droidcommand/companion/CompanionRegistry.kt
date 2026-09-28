package ai.droidcommand.companion

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder

/**
 * Manages Android ServiceConnection bindings to companion APKs.
 *
 * Pattern mirrors [ai.droidcommand.security.InMemoryCapabilityRegistry]:
 * @Synchronized on all state mutations; fail-closed (null return when not bound).
 */
interface CompanionRegistry {
    fun bind(descriptor: CompanionDescriptor)
    fun unbind(packageName: String)
    fun isConnected(packageName: String): Boolean
    fun getHackerAiService(): IHackerAIService?
    fun getPentestSwarmService(): IPentestSwarmService?
    fun allConnectedPackages(): Set<String>
}

/**
 * Production implementation that uses Android's PackageManager + bindService.
 *
 * Binding is asynchronous: [bind] enqueues the OS bind request; the AIDL stub
 * becomes non-null in [onServiceConnected]. Callers should treat null returns
 * from [getHackerAiService]/[getPentestSwarmService] as transient — the registry
 * will become populated once the OS delivers the connection.
 *
 * NOT RUNTIME VERIFIED in this environment (no Android SDK). Excluded from
 * settings.gradle.kts for the same reason as :app.
 */
class AndroidCompanionRegistry(private val context: Context) : CompanionRegistry {

    private val connections = mutableMapOf<String, BoundCompanion>()

    private data class BoundCompanion(
        val packageName: String,
        val connection: ServiceConnection,
        var binder: IBinder? = null,
    )

    @Synchronized
    override fun bind(descriptor: CompanionDescriptor) {
        if (connections.containsKey(descriptor.packageName)) return

        val intent = Intent().apply {
            setPackage(descriptor.packageName)
            action = when (descriptor.packageName) {
                KnownCompanions.HACKERAI_PACKAGE ->
                    "ai.hackerai.companion.IHackerAIService"
                KnownCompanions.PENTESTSWARM_PACKAGE ->
                    "ai.pentestswarm.companion.IPentestSwarmService"
                else -> "ai.droidcommand.companion.ICompanionService"
            }
        }

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                synchronized(this@AndroidCompanionRegistry) {
                    connections[descriptor.packageName]?.binder = service
                }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                synchronized(this@AndroidCompanionRegistry) {
                    connections[descriptor.packageName]?.binder = null
                }
            }
        }

        connections[descriptor.packageName] = BoundCompanion(descriptor.packageName, conn)
        context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
    }

    @Synchronized
    override fun unbind(packageName: String) {
        connections.remove(packageName)?.let {
            runCatching { context.unbindService(it.connection) }
        }
    }

    @Synchronized
    override fun isConnected(packageName: String): Boolean =
        connections[packageName]?.binder != null

    override fun getHackerAiService(): IHackerAIService? =
        connections[KnownCompanions.HACKERAI_PACKAGE]?.binder?.let {
            IHackerAIService.Stub.asInterface(it)
        }

    override fun getPentestSwarmService(): IPentestSwarmService? =
        connections[KnownCompanions.PENTESTSWARM_PACKAGE]?.binder?.let {
            IPentestSwarmService.Stub.asInterface(it)
        }

    @Synchronized
    override fun allConnectedPackages(): Set<String> =
        connections.filterValues { it.binder != null }.keys.toSet()

    fun isCompanionInstalled(packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)
}
