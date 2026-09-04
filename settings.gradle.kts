rootProject.name = "DroidForge-AI"

include(":core-agent")
include(":core-llm")
include(":core-security")

// The following modules are part of the target architecture (see
// docs/ARCHITECTURE.md) but do not yet contain implemented code and are
// intentionally not included in the build to avoid an unbuildable root:
//   :app, :core-tools-android, :core-shell, :core-root, :core-build,
//   :core-apk-lifecycle, :core-remote, :core-config
