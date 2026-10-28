package ai.droidcommand.app.codeassist

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
import ai.droidcommand.security.CodeAssist
import ai.droidcommand.security.CodeAssistParse
import ai.droidcommand.security.CodeAssistRequest
import ai.droidcommand.security.NodeSignatureDecision
import ai.droidcommand.security.NodeSignatureGuard
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
 * Inbound CODE_ASSIST entry point for VictorFiles. Thin Android shell:
 * request parse / 60k cap / result JSON live in [CodeAssist] (JVM-tested);
 * caller package+cert uses [NodeSignatureGuard] with
 * [CodeAssist.callerVerifier]. This Activity shows a real Allow/Deny
 * consent screen and, only after Allow, runs the configured
 * [CloudPromptClient] or [ChatSession.sendLocal] path.
 *
 * Never auto-approves. Never runs the model before Allow. Fail-closed
 * (`RESULT_CANCELED`) on missing caller identity, cert mismatch, deny,
 * or bad input. Compiles; never run on a device in this environment.
 */
@AndroidEntryPoint
class CodeAssistActivity : ComponentActivity() {
    @Inject
    lateinit var vault: EncryptedSecretsVault

    @Inject
    lateinit var transport: HttpTransport

    @Inject
    lateinit var localModels: LocalModelRuntime

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
        val guard = NodeSignatureGuard(
            observer = AndroidSigningCertObserver(this),
            verifier = CodeAssist.callerVerifier(),
        )
        if (guard.check(caller) is NodeSignatureDecision.Refused) {
            cancelAndFinish()
            return
        }

        val parsed = CodeAssist.parseRequest(intent.getStringExtra(CodeAssist.EXTRA_REQUEST_JSON))
        val request = when (parsed) {
            is CodeAssistParse.Valid -> parsed.request
            is CodeAssistParse.Invalid -> {
                cancelAndFinish()
                return
            }
        }

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
                CodeAssistConsentScreen(
                    callerPackage = caller,
                    request = request,
                    running = running,
                    onAllow = { onAllow(request) },
                    onDeny = { cancelAndFinish() },
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

    private fun onAllow(request: CodeAssistRequest) {
        if (running || finished) return
        running = true
        lifecycleScope.launch(Dispatchers.IO) {
            val json = try {
                complete(request)
            } catch (e: Exception) {
                CodeAssist.errorJson("Request failed: ${e.message ?: e.javaClass.simpleName}")
            }
            withContext(Dispatchers.Main) {
                succeedAndFinish(json)
            }
        }
    }

    /**
     * Same provider/model the chat screen last used (`chat_prefs`), through
     * [CloudPromptClient] or [ChatSession.sendLocal]. One-shot: nothing is
     * written to the user's stored conversations.
     */
    private fun complete(request: CodeAssistRequest): String {
        val prompt = CodeAssist.buildPrompt(request)
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
            is ChatResult.Reply -> CodeAssist.successJson(request.task, result.text)
            is ChatResult.Failure -> CodeAssist.errorJson(result.message)
        }
    }

    private fun succeedAndFinish(json: String) {
        if (finished) return
        finished = true
        setResult(RESULT_OK, Intent().putExtra(CodeAssist.EXTRA_RESULT_JSON, json))
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
