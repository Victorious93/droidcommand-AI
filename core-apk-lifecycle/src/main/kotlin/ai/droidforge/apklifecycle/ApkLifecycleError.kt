package ai.droidforge.apklifecycle

import ai.droidforge.build.BuildError

enum class ApkLifecycleStage { BUILD, SELECT_ARTIFACT, INSTALL, LAUNCH, TEST }

/** Machine-readable failure taxonomy, mirroring `core-build.BuildError`'s shape at a scope appropriate to this smaller pipeline. */
sealed class ApkLifecycleError(val code: String, val message: String) {
    class BuildFailed(val buildError: BuildError) : ApkLifecycleError("BUILD_FAILED", buildError.message)
    class NoArtifactFound(detail: String) : ApkLifecycleError("NO_ARTIFACT_FOUND", detail)
    class InstallFailed(detail: String) : ApkLifecycleError("INSTALL_FAILED", detail)
    class LaunchFailed(detail: String) : ApkLifecycleError("LAUNCH_FAILED", detail)
    class TestRunFailed(detail: String) : ApkLifecycleError("TEST_RUN_FAILED", detail)
}
