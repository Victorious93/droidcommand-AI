// Base AIDL interface that every DroidCommand AI companion APK must implement.
// Provides generic capability discovery and execution so DCA can interact with
// any companion in a uniform way, regardless of its specific domain.
//
// JSON string transport is used for executeCapability/cancelExecution so that
// new capabilities only require JSON schema changes, not AIDL file changes.
package ai.droidcommand.companion;

import ai.droidcommand.companion.CompanionCapabilityParcel;

interface ICompanionService {
    // Identity and capability discovery
    String getCompanionId();
    String getCompanionVersion();
    List<CompanionCapabilityParcel> listCapabilities();

    // Liveness check — returns true within a bounded wall-clock time (~500 ms)
    boolean ping();

    // Generic capability invocation.
    // capabilityId — one of the ids returned by listCapabilities()
    // inputJson    — JSON object matching that capability's inputSchema
    // returns      — JSON: {"ok": true, "output": "..."} or {"ok": false, "error": "..."}
    String executeCapability(String capabilityId, String inputJson);

    // Cancels an in-flight execution asynchronously (best-effort, non-blocking).
    oneway void cancelExecution(String executionId);
}
