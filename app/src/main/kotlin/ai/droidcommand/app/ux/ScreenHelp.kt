package ai.droidcommand.app.ux

/**
 * Short "What is this?" copy for each screen the owner can open.
 * Internal words (EXTERNAL_NODE, AIDL, envelope, pin) stay out.
 */
data class ScreenHelp(
    val title: String,
    val body: String,
)

object ScreenHelpCatalog {
    val FLEET = ScreenHelp(
        title = "What is Fleet?",
        body = "This list is your other Victorious apps on this phone. " +
            "Each card says whether that app is connected, installed and waiting to be paired, " +
            "not installed yet, or blocked — and what to do next. " +
            "Pairing only stores that you trust that install. It does not turn on remote control. " +
            "Anything that can change the phone still asks you first.",
    )

    val CHAT = ScreenHelp(
        title = "What is AI Chat?",
        body = "Ask a question here. The reply can come from a model on this phone or from a cloud " +
            "service you already have a key for. Nothing is sent to a cloud until you add a key " +
            "and send a message. Pick a model on the Models tab if you want it to work without a key.",
    )

    val MODELS = ScreenHelp(
        title = "What are Models?",
        body = "A model is the on-phone brain Chat uses when you do not want a cloud key. " +
            "Pick a .gguf file you already have, or download a free one from the catalog. " +
            "The file stays on this device. Removing a copy in app storage deletes that copy only.",
    )

    val SETTINGS = ScreenHelp(
        title = "What is Settings?",
        body = "Everyday setup lives here: look (light, dark, or match the phone), cloud keys, voice, " +
            "and a shortcut to Models. Cloud keys are stored encrypted on this phone and are never " +
            "shown again after you save them. Power-user tools are on the Advanced tab.",
    )

    val ADVANCED = ScreenHelp(
        title = "What is Advanced?",
        body = "One-step commands, plans, memory, extra tools, the activity log, your own n8n server, " +
            "remote-control toggles, and later Kai capabilities live here. Those capabilities stay off. " +
            "Remote control stays off until you turn it on and confirm. " +
            "You do not need this tab to chat or to pair an app.",
    )

    val APK_DOWNLOAD = ScreenHelp(
        title = "What is Get latest APK?",
        body = "Opens the GitHub Releases page for this app so you can download a newer debug build. " +
            "Long-press copies the same link. The version next to the icon is this install, not the file on that page.",
    )

    val N8N = ScreenHelp(
        title = "What is n8n?",
        body = "n8n is a workflow tool you run on a computer you own. This screen saves its address " +
            "and an API key (encrypted on this phone), tests the connection, lists workflows, and " +
            "can start a webhook workflow after you confirm. The AI cannot use n8n until you turn that on, " +
            "and this phone still asks before every run.",
    )

    val FIRST_RUN = ScreenHelp(
        title = "What is this setup?",
        body = "Four short steps so the app can work: what it is, pick a model, pair your other apps, " +
            "and optional permissions. You can skip any step and come back from Settings.",
    )

    val PILOT = ScreenHelp(
        title = "What is a one-step command?",
        body = "Ask the app to run one approved action. It still shows you what it will do and waits " +
            "for you to confirm. This is optional — everyday questions belong on AI Chat.",
    )

    val PLAN = ScreenHelp(
        title = "What is a multi-step plan?",
        body = "Give the app a goal and it breaks the work into steps. Each step that can change " +
            "the phone still asks before it runs. This is optional.",
    )

    val MEMORY = ScreenHelp(
        title = "What is Memory?",
        body = "Facts the app can remember across chats, only if you turn that on. " +
            "You can edit or delete anything stored here.",
    )

    val TOOLS = ScreenHelp(
        title = "What are extra tools?",
        body = "Security tools, notes, clipboards, and saved commands. " +
            "Each run that can change something still asks you first. " +
            "You do not need these to chat or to pair your other apps.",
    )

    val LOGS = ScreenHelp(
        title = "What is the activity log?",
        body = "A list of what this app already did on this phone — approvals, denials, and similar events. " +
            "It is a record, not a control panel.",
    )

    val HELP = ScreenHelp(
        title = "What is Help?",
        body = "Longer reference notes. The short 'What is this?' button on each screen is the everyday version.",
    )

    val PAIRING = ScreenHelp(
        title = "What is pairing?",
        body = "Pairing means: this install of that app is the one you trust on this phone. " +
            "If the other app is later replaced by a different install, the connection stops until you pair again. " +
            "Pairing does not turn on remote control and does not run any command.",
    )

    val CONNECT_DEVICE = ScreenHelp(
        title = "What is Connect a device?",
        body = "Link this phone to a computer or another phone on the same Wi-Fi. " +
            "You both type the same one-time password and check that a short code matches. " +
            "After that you can only check that the other device is there, or send a note to Chat. " +
            "Remote control stays off unless you turn it on under Advanced.",
    )

    val ORCHESTRATOR = ScreenHelp(
        title = "What are approved commands?",
        body = "A list of commands you can send to an app that is already connected. " +
            "Read-only checks do not change the other app. Anything that can change it still asks you first.",
    )
}
