package ai.droidcommand.app.remotepc

import ai.droidcommand.app.fleet.AndroidSigningCertObserver
import ai.droidcommand.remotepc.PcProfileContract
import ai.droidcommand.remotepc.PcProfileSource
import ai.droidcommand.remotepc.PcProviderTrust
import ai.droidcommand.security.KnownNodeSigningCatalog
import ai.droidcommand.security.SigningCertSha256
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-only client for VictorSuite's `content://com.termux.victor.pc/profiles` (victorpc spec v1 §5).
 * Never calls insert / update / delete / openFile and never binds PcEnrollService: droidcommand-AI
 * holds no key in v1. Blocking; call off the main thread.
 *
 * Compiles and is unit-tested at the parsing layer. Not run against a real VictorSuite yet: the provider
 * lands in a later VictorSuite PR (plan PR 8).
 */
@Singleton
class AndroidPcProfileReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun read(): PcProfileSource {
        if (!isInstalled(PcProfileContract.VICTORSUITE_PACKAGE)) return PcProfileSource.VictorSuiteNotInstalled

        val provider = try {
            context.packageManager.resolveContentProvider(PcProfileContract.AUTHORITY, 0)
        } catch (_: RuntimeException) {
            null
        } ?: return PcProfileSource.ProviderMissing

        val signers = try {
            AndroidSigningCertObserver(context).certificateBytes(provider.packageName).map {
                SigningCertSha256.fromCertificateBytes(it).canonical
            }
        } catch (_: SecurityException) {
            emptyList()
        }
        if (!PcProviderTrust.isTrusted(provider.packageName, signers, VICTORSUITE_PINS)) {
            Log.w(TAG, "Refusing PC profiles from ${provider.packageName}: package or signer is not pinned")
            return PcProfileSource.UntrustedPublisher(provider.packageName)
        }

        return try {
            val consent = consentStatus()
            val rows = context.contentResolver.query(PROFILES_URI, null, null, null, null)?.use(::readRows)
                ?: return PcProfileSource.ProviderMissing
            PcProfileSource.Rows(rows, consent)
        } catch (_: SecurityException) {
            PcProfileSource.PermissionDenied
        } catch (error: IllegalArgumentException) {
            Log.i(TAG, "PC provider rejected the query: ${error.message}")
            PcProfileSource.ProviderMissing
        } catch (error: RuntimeException) {
            Log.w(TAG, "Reading PC profiles failed", error)
            PcProfileSource.Failed(error.javaClass.simpleName)
        }
    }

    /** §5 `consent_status`. A missing or odd answer is null; SecurityException propagates (pin refused). */
    private fun consentStatus(): String? = try {
        context.contentResolver.call(PROFILES_URI, PcProfileContract.METHOD_CONSENT_STATUS, null, null)
            ?.getString(PcProfileContract.CONSENT_KEY)
    } catch (error: SecurityException) {
        throw error
    } catch (_: RuntimeException) {
        null
    }

    private fun isInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun readRows(cursor: Cursor): List<Map<String, String?>> {
        val rows = mutableListOf<Map<String, String?>>()
        while (cursor.moveToNext() && rows.size < MAX_ROWS) {
            val row = linkedMapOf<String, String?>()
            for (index in 0 until cursor.columnCount) {
                val name = cursor.getColumnName(index) ?: continue
                row[name] = when {
                    cursor.isNull(index) -> null
                    cursor.getType(index) == Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index).toString()
                    cursor.getType(index) == Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                    else -> null
                }
            }
            rows += row
        }
        return rows
    }

    companion object {
        private const val TAG = "RemotePc"
        private const val MAX_ROWS = 64
        val PROFILES_URI: Uri = Uri.parse(PcProfileContract.PROFILES_URI)

        /** VictorSuite's own debug and release certs, already pinned for `com.termux` in core-security. */
        val VICTORSUITE_PINS: Set<String> = setOf(
            KnownNodeSigningCatalog.VICTORSUITE_DEBUG_SHA256,
            KnownNodeSigningCatalog.VICTORSUITE_RELEASE_SHA256,
        )
    }
}
