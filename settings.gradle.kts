rootProject.name = "DroidForge-AI"

include(":core-agent")
include(":core-llm")
include(":core-security")
include(":core-config")
include(":core-remote")
include(":core-build")
include(":core-tools-android")
include(":core-shell")

// The following modules are part of the target architecture (see
// docs/ARCHITECTURE.md) but do not yet contain implemented code and are
// intentionally not included in the build to avoid an unbuildable root:
//   :app, :core-root, :core-apk-lifecycle
