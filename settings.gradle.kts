rootProject.name = "DroidCommand-AI"

include(":core-agent")
include(":core-llm")
include(":core-security")
include(":core-config")
include(":core-remote")
include(":core-build")
include(":core-tools-android")
include(":core-shell")
include(":core-apk-lifecycle")
include(":core-root")
include(":core-llm-anthropic")
include(":core-llm-openai")
include(":core-build-local")
include(":core-build-remote")

// The following modules are part of the target architecture (see
// docs/ARCHITECTURE.md) but do not yet contain implemented code and are
// intentionally not included in the build to avoid an unbuildable root:
//   :app
