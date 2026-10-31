package ai.droidcommand.app.ui.onboarding

import ai.droidcommand.app.localmodels.ImportOutcome
import ai.droidcommand.app.localmodels.LocalModelRuntime
import ai.droidcommand.app.prefs.FirstRunPrefs
import ai.droidcommand.llm.local.GgufFileName
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class FirstRunStep {
    WELCOME,
    MODEL,
    FLEET,
    PERMISSIONS,
}

data class FirstRunUiState(
    val step: FirstRunStep = FirstRunStep.WELCOME,
    val modelStatus: String? = null,
    val importing: Boolean = false,
    val hasModel: Boolean = false,
    val micGranted: Boolean = false,
    val notificationsGranted: Boolean = false,
    val allFilesGranted: Boolean = false,
    val finished: Boolean = false,
    val openModels: Boolean = false,
)

@HiltViewModel
class FirstRunViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: FirstRunPrefs,
    private val models: LocalModelRuntime,
) : ViewModel() {
    private val mutable = MutableStateFlow(FirstRunUiState())
    val state: StateFlow<FirstRunUiState> = mutable.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        mutable.update {
            it.copy(
                hasModel = models.list().isNotEmpty(),
                micGranted = granted(Manifest.permission.RECORD_AUDIO),
                notificationsGranted = notificationsAlreadyGranted(),
                allFilesGranted = hasAllFilesAccess(),
            )
        }
    }

    fun next() {
        val order = FirstRunStep.entries
        val index = order.indexOf(mutable.value.step)
        if (index < order.lastIndex) {
            mutable.update { it.copy(step = order[index + 1], modelStatus = null) }
        } else {
            finish()
        }
    }

    fun back() {
        val order = FirstRunStep.entries
        val index = order.indexOf(mutable.value.step)
        if (index > 0) mutable.update { it.copy(step = order[index - 1], modelStatus = null) }
    }

    fun finish(openModels: Boolean = false) {
        prefs.markComplete()
        mutable.update { it.copy(finished = true, openModels = openModels) }
    }

    fun skip() = finish()

    fun browseCatalog() = finish(openModels = true)

    fun importUri(uri: Uri) {
        if (mutable.value.importing) return
        mutable.update { it.copy(importing = true, modelStatus = "Copying the file…") }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val name = GgufFileName.resolve(displayName(uri), uri.lastPathSegment, uri.toString())
                ?: (displayName(uri) ?: "model.gguf")
            val size = sizeOf(uri)
            val outcome = context.contentResolver.openInputStream(uri)?.use { stream ->
                models.importModel(name, stream, size) { }
            } ?: ImportOutcome.Failed("The file could not be opened.")
            val message = when (outcome) {
                is ImportOutcome.Imported -> "Ready: ${outcome.model.label}. Chat can use this model."
                is ImportOutcome.Duplicate -> "That model is already on the phone."
                is ImportOutcome.Rejected -> outcome.reason
                is ImportOutcome.Failed -> outcome.reason
            }
            mutable.update {
                it.copy(
                    importing = false,
                    modelStatus = message,
                    hasModel = models.list().isNotEmpty(),
                )
            }
        }
    }

    fun onPermissionResult(permission: String, granted: Boolean) {
        when (permission) {
            Manifest.permission.RECORD_AUDIO -> mutable.update { it.copy(micGranted = granted) }
            Manifest.permission.POST_NOTIFICATIONS -> mutable.update { it.copy(notificationsGranted = granted) }
        }
    }

    fun onReturnedFromSettings() {
        mutable.update { it.copy(allFilesGranted = hasAllFilesAccess()) }
    }

    fun allFilesSettingsIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
    }

    private fun displayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return null
    }

    private fun sizeOf(uri: Uri): Long? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index)
            }
        }
        return null
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun notificationsAlreadyGranted(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return granted(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}
