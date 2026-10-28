package ai.droidcommand.app.fleet

import ai.droidcommand.security.FleetThemeClient
import ai.droidcommand.security.FleetThemeContract
import ai.droidcommand.security.FleetThemeDecision
import ai.droidcommand.security.FleetThemePalette
import ai.droidcommand.security.FleetThemeSource
import ai.droidcommand.security.SigningCertSha256
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android adapter for [FleetThemeClient]: query-only reader of
 * `content://com.victorious.launch.theme/current`.
 *
 * VictorLaunch is the sole writer. This class never calls insert / update /
 * delete. Palette values missing from the row stay at the app's existing
 * Compose defaults — no colors are invented here.
 *
 * [Certain] query / observer / receiver wiring compiles. [Guessing] live
 * ContentProvider + explicit broadcast on a phone — this environment has
 * no device and no VictorLaunch APK.
 */
@Singleton
class AndroidFleetThemeClient @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val reader = FleetThemeClient(log = { message -> Log.i(TAG, message) })
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refresh("observer")
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            refresh("observer")
        }
    }

    private val _state = MutableStateFlow(FleetThemeUiState.DEFAULT)
    val state: StateFlow<FleetThemeUiState> = _state.asStateFlow()

    @Volatile
    private var observing = false

    fun start() {
        if (!observing) {
            observing = true
            try {
                context.contentResolver.registerContentObserver(THEME_URI, true, observer)
            } catch (error: SecurityException) {
                Log.i(TAG, "Could not register fleet-theme observer: ${error.message}")
                applyDecision(reader.evaluate(FleetThemeSource.SecurityDenied))
                return
            } catch (error: IllegalArgumentException) {
                Log.i(TAG, "Fleet-theme observer not registered (provider absent): ${error.message}")
            }
        }
        refresh("start")
    }

    fun refresh(reason: String) {
        Log.i(TAG, "Re-querying fleet theme ($reason)")
        applyDecision(reader.evaluate(query()))
    }

    fun dismissTrustHint() {
        _state.update { it.copy(showTrustHint = false) }
    }

    private fun applyDecision(decision: FleetThemeDecision) {
        when (decision) {
            is FleetThemeDecision.Apply -> {
                _state.value = FleetThemeUiState(
                    palette = decision.snapshot.palette,
                    showTrustHint = false,
                )
            }
            is FleetThemeDecision.KeepCurrent -> {
                // schemaVersion != 1, empty row, or VictorLaunch not installed.
            }
            is FleetThemeDecision.Fallback -> {
                _state.value = FleetThemeUiState(
                    palette = FleetThemePalette(),
                    showTrustHint = decision.showTrustHint,
                )
            }
        }
    }

    private fun query(): FleetThemeSource {
        val provider = try {
            context.packageManager.resolveContentProvider(FleetThemeContract.AUTHORITY, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } ?: return FleetThemeSource.NotInstalled

        val fingerprints = try {
            AndroidSigningCertObserver(context).certificateBytes(provider.packageName).map { bytes ->
                SigningCertSha256.fromCertificateBytes(bytes).canonical
            }
        } catch (_: SecurityException) {
            emptyList()
        }

        return try {
            context.contentResolver.query(THEME_URI, null, null, null, null).use { cursor ->
                if (cursor == null || !cursor.moveToFirst()) {
                    return FleetThemeSource.Empty
                }
                FleetThemeSource.Row(
                    publisherPackage = provider.packageName,
                    fingerprints = fingerprints,
                    columns = readColumns(cursor),
                )
            }
        } catch (_: SecurityException) {
            FleetThemeSource.SecurityDenied
        }
    }

    private fun readColumns(cursor: Cursor): Map<String, String?> {
        val columns = linkedMapOf<String, String?>()
        for (index in 0 until cursor.columnCount) {
            val name = cursor.getColumnName(index) ?: continue
            columns[name] = cursorValue(cursor, index)
        }
        return columns
    }

    private fun cursorValue(cursor: Cursor, index: Int): String? {
        if (cursor.isNull(index)) return null
        return when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index).toString()
            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index).toString()
            Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
            else -> null
        }
    }

    companion object {
        private const val TAG = "FleetTheme"
        val THEME_URI: Uri = Uri.parse(FleetThemeContract.URI)
    }
}

data class FleetThemeUiState(
    val palette: FleetThemePalette,
    val showTrustHint: Boolean,
) {
    companion object {
        val DEFAULT = FleetThemeUiState(
            palette = FleetThemePalette(),
            showTrustHint = false,
        )
    }
}
