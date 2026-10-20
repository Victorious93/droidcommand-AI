package ai.droidcommand.app.fleet

import ai.droidcommand.companion.AndroidCompanionRegistry
import ai.droidcommand.companion.CompanionCapabilityHealthChecker
import ai.droidcommand.companion.CompanionDescriptor
import ai.droidcommand.companion.ICompanionService
import ai.droidcommand.companion.KnownCompanions
import ai.droidcommand.security.CapabilityId
import ai.droidcommand.security.CapabilityState
import android.content.Context

/**
 * [CompanionLink] over `core-companion`'s [AndroidCompanionRegistry]. Binding is the registry's own bind (a
 * `BIND_AUTO_CREATE` service connection guarded by the companion's signature permission). Nothing here has run against
 * a real companion APK: none is available in CI, and the APK sources are not build-verified either.
 */
class AndroidCompanionLink(
    context: Context,
    private val connectTimeoutMs: Long = 2_000,
    private val pollMs: Long = 50,
) : CompanionLink {
    private val registry = AndroidCompanionRegistry(context.applicationContext)
    private val descriptors: Map<String, CompanionDescriptor> = listOf(KnownCompanions.HACKERAI, KnownCompanions.PENTESTSWARM)
        .associateBy { it.packageName }

    override fun isInstalled(packageName: String): Boolean = registry.isCompanionInstalled(packageName)

    override fun connect(packageName: String): Boolean {
        val descriptor = descriptors[packageName] ?: return false
        if (registry.isConnected(packageName)) return true
        registry.bind(descriptor)
        val deadline = System.currentTimeMillis() + connectTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (registry.isConnected(packageName)) return true
            Thread.sleep(pollMs)
        }
        // Drop the pending bind so a later attempt (after install or a service start) can bind again.
        registry.unbind(packageName)
        return false
    }

    override fun domainHealthy(packageName: String): Boolean {
        val descriptor = descriptors[packageName] ?: return false
        val checker = CompanionCapabilityHealthChecker(registry, descriptor)
        return checker.verify(CapabilityId("fleet.${descriptor.id}.companion"), descriptor.id).state == CapabilityState.AVAILABLE
    }

    override fun exposesCompanionService(packageName: String): Boolean =
        runCatching { registry.getBinder(packageName)?.interfaceDescriptor == ICompanionService::class.java.name }.getOrDefault(false)

    override fun ping(packageName: String): Boolean = service(packageName).ping()

    override fun executeCapability(packageName: String, capabilityId: String, inputJson: String): String =
        service(packageName).executeCapability(capabilityId, inputJson)

    private fun service(packageName: String): ICompanionService {
        val binder = registry.getBinder(packageName) ?: error("not bound")
        return ICompanionService.Stub.asInterface(binder)
    }
}
