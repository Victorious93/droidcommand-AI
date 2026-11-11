package ai.droidcommand.llm.pchost

/** Where a model a PC server offers actually runs, as far as the server says. */
enum class PcModelLocation(val id: String) {
    /** Installed on the PC and run there. Needs no internet, only a path from the phone to the PC. */
    SERVER_LOCAL("server-local"),

    /** The PC forwards the request to another service on the internet. Needs internet on the PC. */
    CLOUD_BACKED("cloud-backed"),

    /** The server's model list does not say (the OpenAI model list has no such field). */
    UNKNOWN("unknown"),
    ;

    companion object {
        fun byId(id: String?): PcModelLocation = entries.firstOrNull { it.id == id } ?: UNKNOWN
    }
}

/**
 * One model a PC server listed. [upstream] is the host a [PcModelLocation.CLOUD_BACKED] model is forwarded to, when
 * the server names it; [detail] is a short description for the list (size, quantisation, owner).
 */
data class PcHostModel(val id: String, val location: PcModelLocation, val upstream: String? = null, val detail: String? = null) {
    /** One line for the model list and the chat header. */
    fun locationLabel(): String = PcHostModels.locationLabel(location, upstream)
}

object PcHostModels {
    fun locationLabel(location: PcModelLocation, upstream: String?): String = when (location) {
        PcModelLocation.SERVER_LOCAL -> "On your PC"
        PcModelLocation.CLOUD_BACKED -> "Cloud-backed: your PC forwards the chat to ${upstream ?: "an internet service"}"
        PcModelLocation.UNKNOWN -> "Location not reported by this server"
    }
}

/**
 * What Chat needs to send one turn to a PC server: which kind, where, which model, and what the server said about
 * where that model runs when it was picked.
 */
data class PcHostTarget(
    val kind: PcHostKind,
    val address: PcHostAddress,
    val model: String,
    val location: PcModelLocation = PcModelLocation.UNKNOWN,
    val upstream: String? = null,
)

/** The wording the settings screen shows, kept here so it is tested on the JVM. */
object PcHostCopy {
    /** Shown on the settings screen and in Help: the two ways to use a model without a cloud provider, and how they differ. */
    const val TWO_WORKFLOWS =
        "Two different things. PC-hosted: the model stays on your PC and every message is sent to the PC, which writes the reply; " +
            "nothing is copied to this phone, and it works whenever the phone can reach the PC (same Wi-Fi or VPN), with or without internet. " +
            "On-device: you import a .gguf model file onto this phone (Models tab) and the phone itself writes the reply; no PC and no network are involved."

    const val CHAT_ONLY =
        "Used by AI Chat only. Plans, one-step commands, the assist pop-ups and memory extraction still use the cloud provider or the on-device model."

    /** One line under "Test connection" after the server answered. [savedModel] is the model picked earlier, if any. */
    fun reachable(kind: PcHostKind, version: String?, models: List<PcHostModel>, savedModel: String?): String {
        val server = kind.label + version?.let { " $it" }.orEmpty()
        if (models.isEmpty()) {
            val hint = when (kind) {
                PcHostKind.OLLAMA -> "It has no models yet. On the PC run: ollama pull <model name>, then test again."
                PcHostKind.OPENAI_COMPATIBLE -> "It lists no models. Load one on the server, or type the model name below."
            }
            return "Connected to $server. $hint"
        }
        val cloud = models.count { it.location == PcModelLocation.CLOUD_BACKED }
        val local = models.count { it.location == PcModelLocation.SERVER_LOCAL }
        val split = when {
            local + cloud == 0 -> "This kind of server does not say where each model runs."
            cloud == 0 -> "All of them run on your PC."
            else -> "$local on your PC, $cloud cloud-backed (the PC forwards those to the internet)."
        }
        val gone = savedModel?.takeIf { it.isNotBlank() && models.none { m -> m.id == it } }
            ?.let { " The model saved earlier, \"$it\", is not on the server any more: pick another." }.orEmpty()
        return "Connected to $server. ${models.size} model${if (models.size == 1) "" else "s"}. $split$gone"
    }
}
