package ai.droidcommand.app.companionexec

import ai.droidcommand.agent.ToolRunner
import ai.droidcommand.app.fleet.AndroidSigningCertObserver
import ai.droidcommand.app.ui.theme.DroidCommandTheme
import ai.droidcommand.security.CompanionAssist
import ai.droidcommand.security.CompanionExec
import ai.droidcommand.security.ExecBroker
import ai.droidcommand.security.ExecCatalog
import ai.droidcommand.security.ExecConsent
import ai.droidcommand.security.ExecParse
import ai.droidcommand.security.ExecPrepared
import ai.droidcommand.security.ExecRequest
import ai.droidcommand.security.InboundEntryPointCatalog
import ai.droidcommand.security.JsonFileAuditLog
import ai.droidcommand.security.JsonFileExecReceiptStore
import ai.droidcommand.security.NodeSignatureDecision
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
import java.io.File
import javax.inject.Inject

/**
 * Inbound COMPANION_EXEC v1 (see `docs/COMPANION_EXEC.md`), a contract separate from the text-only
 * COMPANION_ASSIST. Thin Android shell around the JVM-tested [ExecBroker]:
 *
 *  - the caller must already be a PAIRED/pinned fleet app ([CompanionAssist.verifyCaller] must return
 *    Allowed); this Activity never offers the password-pairing flow itself;
 *  - `discover` and `receipt` return immediately and run nothing;
 *  - `execute` shows what will run and who asked; only the owner's Allow tap, in this app, runs it,
 *    through the same [ToolRunner] (policy, approvals, audit) as every other tool call;
 *  - back / Deny records a `denied` receipt, an expired request records `expired`.
 *
 * [Certain] compiles only with the Android toolchain; not built or run in the session that wrote it.
 */
@AndroidEntryPoint
class CompanionExecActivity : ComponentActivity() {
    @Inject lateinit var runner: ToolRunner

    @Inject lateinit var auditLog: JsonFileAuditLog

    @Inject lateinit var pairing: PasswordPairingService

    private var finished = false
    private var running by mutableStateOf(false)
    private lateinit var broker: ExecBroker
    private var consent: ExecConsent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val caller = resolveCallerPackage()
        if (caller == null || InboundEntryPointCatalog.includedByPackage(caller) == null || InboundEntryPointCatalog.isExcluded(caller)) {
            cancelAndFinish()
            return
        }
        val observed = AndroidSigningCertObserver(this).certificateBytes(caller).map { SigningCertSha256.fromCertificateBytes(it).canonical }
        if (CompanionAssist.verifyCaller(caller, observed, pairing) !is NodeSignatureDecision.Allowed) {
            cancelAndFinish()
            return
        }
        broker = ExecBroker(
            ExecCatalog.DEFAULT,
            runner,
            JsonFileExecReceiptStore(File(filesDir, "exec-receipts.jsonl").toPath()),
            auditLog,
        )
        val request = when (val parsed = CompanionExec.parse(intent.getStringExtra(CompanionExec.EXTRA_REQUEST_JSON), System.currentTimeMillis())) {
            is ExecParse.Invalid -> {
                // Invalid requests are answered (ok=false) so the caller can tell a refusal from a crash.
                succeedAndFinish(CompanionExec.errorJson(parsed.reason))
                return
            }
            is ExecParse.Valid -> parsed.request
        }
        when (request) {
            is ExecRequest.Discover -> succeedAndFinish(broker.discover(caller))
            is ExecRequest.Receipt -> succeedAndFinish(broker.receiptJson(caller, request.requestId))
            is ExecRequest.Execute -> when (val prep = broker.prepare(caller, request)) {
                is ExecPrepared.Rejected -> succeedAndFinish(CompanionExec.errorJson(prep.reason))
                is ExecPrepared.Done -> succeedAndFinish(ExecBroker.receiptToJson(prep.receipt))
                is ExecPrepared.NeedsConsent -> showConsent(prep.consent)
            }
        }
    }

    private fun showConsent(c: ExecConsent) {
        consent = c
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = decide(false)
            },
        )
        setContent {
            DroidCommandTheme {
                CompanionExecConsentScreen(
                    consent = c,
                    running = running,
                    onAllow = { decide(true) },
                    onDeny = { decide(false) },
                    onExpired = { expire() },
                )
            }
        }
    }

    private fun decide(allow: Boolean) {
        val c = consent ?: return
        if (running || finished) return
        running = true
        lifecycleScope.launch(Dispatchers.IO) {
            val json = try {
                ExecBroker.receiptToJson(broker.finish(c, allow))
            } catch (e: Exception) {
                CompanionExec.errorJson("Request failed: ${e.javaClass.simpleName}")
            }
            withContext(Dispatchers.Main) { succeedAndFinish(json) }
        }
    }

    private fun expire() {
        val c = consent ?: return
        if (running || finished) return
        running = true
        lifecycleScope.launch(Dispatchers.IO) {
            val json = ExecBroker.receiptToJson(broker.expire(c))
            withContext(Dispatchers.Main) { succeedAndFinish(json) }
        }
    }

    /** getCallingPackage is the primary signal; getCallingActivity a fallback; disagreement refuses. */
    private fun resolveCallerPackage(): String? {
        val fromPackage = callingPackage?.takeIf { it.isNotBlank() }
        val fromActivity = callingActivity?.packageName?.takeIf { it.isNotBlank() }
        return when {
            fromPackage != null && fromActivity != null && fromPackage != fromActivity -> null
            fromPackage != null -> fromPackage
            else -> fromActivity
        }
    }

    private fun succeedAndFinish(json: String) {
        if (finished) return
        finished = true
        setResult(RESULT_OK, Intent().putExtra(CompanionExec.EXTRA_RESULT_JSON, json))
        finish()
    }

    private fun cancelAndFinish() {
        if (finished) return
        finished = true
        setResult(RESULT_CANCELED)
        finish()
    }
}
