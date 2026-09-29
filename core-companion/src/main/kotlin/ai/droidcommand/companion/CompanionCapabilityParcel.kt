package ai.droidcommand.companion

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * A single capability advertised by a companion APK, passed over AIDL.
 *
 * @param id            Stable, dot-namespaced identifier (e.g. "ai.companion.hackerai.agent_task").
 * @param name          Human-readable display name.
 * @param description   One-sentence description suitable for DCA's tool picker UI.
 * @param inputSchema   JSON Schema string describing the capability's inputJson parameter.
 *                      DCA uses this to validate inputs before invoking executeCapability().
 */
@Parcelize
data class CompanionCapabilityParcel(
    val id: String,
    val name: String,
    val description: String,
    val inputSchema: String,
) : Parcelable
