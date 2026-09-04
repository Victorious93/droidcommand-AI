rootProject.name = "DroidForge-AI"

include(":core-agent")

// The following modules are part of the target architecture (see
// docs/ARCHITECTURE.md) but do not yet contain implemented code and are
// intentionally not included in the build to avoid an unbuildable root:
//   :app, :core-llm, :core-tools-android, :core-shell, :core-root,
//   :core-build, :core-apk-lifecycle, :core-remote, :core-security,
//   :core-config
