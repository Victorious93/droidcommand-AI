package ai.droidcommand.app.companionassist

import ai.droidcommand.agent.InMemoryConversationStore
import ai.droidcommand.app.fleet.AndroidSigningCertObserver
import ai.droidcommand.app.localmodels.LocalModelRuntime
import ai.droidcommand.app.ui.theme.DroidCommandTheme
import ai.droidcommand.config.EncryptedSecretsVault
import ai.droidcommand.llm.factory.ChatResult
import ai.droidcommand.llm.factory.ChatSession
import ai.droidcommand.llm.factory.CloudPromptClient
import ai.droidcommand.llm.factory.CloudProviderCatalog
import ai.droidcommand.remote.HttpTransport
import ai.droidcommand.security.CompanionAssist
import ai.droidcommand.security.CompanionAssistParse
import ai.droidcommand.security.CompanionAssistRequest
import ai.droidcommand.security.InboundCertStatus
import ai.droidcommand.security.InboundEntryPointCatalog
import ai.droidcommand.security.NodeSignatureDecision
import ai.droidcommand.security.PasswordPairing
import ai.droidcommand.security.PasswordPairingDecision
import ai.droidcommand.security.PasswordPairingService
import ai.droidcommand.security.SigningCertSha256
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Inbound COMPANION_ASSIST entry point for the 13 included fleet apps.
 * Thin Android shell: request parse / 60k cap / result JSON live in
 * [CompanionAssist] (JVM-tested); caller package+cert uses
 * [CompanionAssist.verifyCaller] (catalog pin, then optional
 * password-pairing TOFU). This Activity shows a real Allow/Deny
 * consent screen and, only after Allow, runs the configured
 * [CloudPromptClient] or [ChatSession.sendLocal] path.
 *
 * Never auto-approves. Never runs the model before Allow. Never runs
 * tools or remote.command. Fail-closed (`RESULT_CANCELED`) on missing
 * caller identity, excluded app, pending-refused cert (unless a
 * password link already exists or pairing is ON and the owner
 * confirms), cert mismatch, deny, or bad input. [Certain] compiles.
 * [Certain] never run on a device in this environment.
 */
@AndroidEntryPoint
class CompanionAssistActivity : ComponentActivity() {
    @Inject
    lateinit var vault: EncryptedSecretsVault

    @Inject
    lateinit var transport: HttpTransport

    @Inject
    lateinit var localModels: LocalModelRuntime

    @Inject
    lateinit var pairing: PasswordPairingService

    private var running by mutableStateOf(false)
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        val caller = resolveCallerPackage()
        if (caller == null) {
            cancelAndFinish()
            return
        }
        val included = InboundEntryPointCatalog.includedByPackage(caller)
        if (included == null || InboundEntryPointCatalog.isExcluded(caller)) {
            cancelAndFinish()
            return
        }
        val observed = AndroidSigningCertObserver(this).certificateBytes(caller).map { bytes ->
            SigningCertSha256.fromCertificateBytes(bytes).canonical
        }
        val verified = CompanionAssist.verifyCaller(caller, observed, pairing)
        val canOfferLink = pairing.isEnabled() &&
            pairing.hasPassword() &&
            !PasswordPairing.isExcluded(caller) &&
            observed.isNotEmpty()
        if (verified is NodeSignatureDecision.Refused && !canOfferLink) {
            cancelAndFinish()
            return
        }
        if (included.certStatus != InboundCertStatus.ENFORCED &&
            verified !is NodeSignatureDecision.Allowed &&
            !canOfferLink
        ) {
            cancelAndFinish()
            return
        }

        val parsed = CompanionAssist.parseRequest(intent.getStringExtra(CompanionAssist.EXTRA_REQUEST_JSON))
        val request = when (parsed) {
            is CompanionAssistParse.Valid -> {
                when (val bound = CompanionAssist.bindToCaller(parsed.request, caller, pairing, observed)) {
                    is CompanionAssistParse.Valid -> bound.request
                    is CompanionAssistParse.Invalid -> {
                        cancelAndFinish()
                        return
                    }
                }
            }
            is CompanionAssistParse.Invalid -> {
                cancelAndFinish()
                return
            }
        }
        if (request.needChallenge) {
            when (val issued = pairing.issueChallenge(caller)) {
                is PasswordPairingDecision.Allowed -> {
                    val challengeId = issued.message.substringBefore(":")
                    val nonce = issued.message.substringAfter(":")
                    succeedAndFinish(CompanionAssist.pairingChallengeJson(challengeId, nonce))
                }
                is PasswordPairingDecision.Refused -> {
                    succeedAndFinish(CompanionAssist.errorJson(issued.reason))
                }
            }
            return
        }
        val proof = request.pairing
        val needsPassword = verified !is NodeSignatureDecision.Allowed &&
            (proof == null || proof.isBlank())

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    cancelAndFinish()
                }
            },
        )

        setContent {
            DroidCommandTheme {
                CompanionAssistConsentScreen(
                    callerPackage = caller,
                    displayName = included.displayName,
                    request = request,
                    running = running,
                    onAllow = { typed -> onAllow(request, caller, observed, typed) },
                    onDeny = { cancelAndFinish() },
                    needsPassword = needsPassword,
                    observedCert = observed.firstOrNull(),
                    pairingNote = if (verified is NodeSignatureDecision.Allowed) {
                        null
                    } else {
                        "Catalog pin is missing or does not match. Allow after the pairing " +
                            "password binds this package to the observed cert (TOFU)."
                    },
                )
            }
        }
    }

    /**
     * startActivityForResult identity. [getCallingPackage] is the primary
     * signal; [getCallingActivity] is a fallback. If both are present and
     * disagree, refuse.
     */
    private fun resolveCallerPackage(): String? {
        val fromPackage = callingPackage?.takeIf { it.isNotBlank() }
        val fromActivity = callingActivity?.packageName?.takeIf { it.isNotBlank() }
        return when {
            fromPackage != null && fromActivity != null && fromPackage != fromActivity -> null
            fromPackage != null -> fromPackage
            else -> fromActivity
        }
    }

    private fun onAllow(
        request: CompanionAssistRequest,
        caller: String,
        observed: List<String>,
        typedPassword: String?,
    ) {
        if (running || finished) return
        running = true
        lifecycleScope.launch(Dispatchers.IO) {
            val json = try {
                val already = CompanionAssist.verifyCaller(caller, observed, pairing)
                if (already !is NodeSignatureDecision.Allowed) {
                    val decision = pairing.evaluateInbound(
                        caller,
                        observed,
                        request.pairing,
                        typedPassword,
                        screenConfirmed = true,
                    )
                    if (decision is PasswordPairingDecision.Refused) {
                        CompanionAssist.errorJson(decision.reason)
                    } else if (request.task == CompanionAssist.TASK_PAIR) {
                        CompanionAssist.successJson(
                            (decision as PasswordPairingDecision.Allowed).message,
                        )
                    } else {
                        complete(request)
                    }
                } else if (request.task == CompanionAssist.TASK_PAIR) {
                    CompanionAssist.successJson(already.message)
                } else {
                    complete(request)
                }
            } catch (e: Exception) {
                CompanionAssist.errorJson("Request failed: ${e.message ?: e.javaClass.simpleName}")
            }
            withContext(Dispatchers.Main) {
                succeedAndFinish(json)
            }
        }
    }

    /**
     * Same provider/model the chat screen last used (`chat_prefs`), through
     * [CloudPromptClient] or [ChatSession.sendLocal]. One-shot: nothing is
     * written to the user's stored conversations. No tools run.
     */
    private fun complete(request: CompanionAssistRequest): String {
        val prompt = CompanionAssist.buildPrompt(request)
        val prefs = getSharedPreferences(CHAT_PREFS, MODE_PRIVATE)
        val localId = prefs.getString(KEY_LOCAL_MODEL, null)?.takeIf { it.isNotBlank() }
        val result = if (localId != null) {
            localModels.list()
            val session = ChatSession(
                vault,
                InMemoryConversationStore(),
                transport = transport,
                local = localModels.resources,
            )
            try {
                session.sendLocal(localId, prompt)
            } finally {
                session.close()
            }
        } else {
            val spec = CloudProviderCatalog.byId(prefs.getString(KEY_PROVIDER, null).orEmpty())
                ?: CloudProviderCatalog.all.first()
            val model = prefs.getString("model.${spec.id}", null) ?: spec.defaultModel
            CloudPromptClient(vault, transport).ask(spec, model, prompt)
        }
        return when (result) {
            is ChatResult.Reply -> CompanionAssist.successJson(result.text)
            is ChatResult.Failure -> CompanionAssist.errorJson(result.message)
        }
    }

    private fun succeedAndFinish(json: String) {
        if (finished) return
        finished = true
        setResult(RESULT_OK, Intent().putExtra(CompanionAssist.EXTRA_RESULT_JSON, json))
        finish()
    }

    private fun cancelAndFinish() {
        if (finished) return
        finished = true
        setResult(RESULT_CANCELED)
        finish()
    }

    private companion object {
        const val CHAT_PREFS = "chat_prefs"
        const val KEY_PROVIDER = "provider"
        const val KEY_LOCAL_MODEL = "local_model_id"
    }
}
