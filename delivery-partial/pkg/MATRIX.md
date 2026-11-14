# Build/validation matrix (2026-11-14T21:40:15Z)

**Overall: NOT COMPLETE — 7 required job(s) not PASSED**

| Job | Repo | Required | Status | Detail |
|---|---|---|---|---|
| vl-fleet-tests | VictorLinux | yes | **PASSED** | 40.1s |
| vl-debian-logic-tests | VictorLinux | yes | **PASSED** | 0.1s |
| vl-arch-render-and-ui | VictorLinux | yes | **PASSED** | 6.4s |
| vl-native-cache-off | VictorLinux | no | **BLOCKED** | missing: env:LLAMA_SRC |
| vl-native-cache-on | VictorLinux | no | **NOT RUN** | dependency not PASSED: vl-native-cache-off |
| vl-image-debian | VictorLinux | no | **BLOCKED** | missing: lb, debootstrap, xorriso, mksquashfs |
| vl-image-arch | VictorLinux | no | **BLOCKED** | missing: container-daemon |
| flash-rust-tests | victorious-flash | yes | **PASSED** | 1.2s |
| flash-helper-build | victorious-flash | yes | **PASSED** | 8.6s |
| flash-tauri-bundle | victorious-flash | yes | **BLOCKED** | missing: pkg:webkit2gtk-4.1, pkg:gtk+-3.0 |
| dc-jvm-tests | droidcommand-AI | yes | **PASSED** | 10.0s |
| dc-apk | droidcommand-AI | yes | **BLOCKED** | missing: android-sdk |
| rustdesk-android-apk | rustdeskV | yes | **BLOCKED** | missing: flutter, android-sdk |
| rustdesk-linux-client | rustdeskV | yes | **BLOCKED** | missing: flutter |
| victorsuite-apk | VictorSuite | yes | **BLOCKED** | missing: android-sdk |
| filemanager-apk | File-Manager | yes | **BLOCKED** | missing: android-sdk |
| mesh-apk | victorious-mesh | yes | **BLOCKED** | missing: android-sdk |
| manifest-validate | fleet-manifest | yes | **PASSED** | 0.1s |
