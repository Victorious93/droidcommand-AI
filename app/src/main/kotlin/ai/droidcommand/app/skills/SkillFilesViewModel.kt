package ai.droidcommand.app.skills

import ai.droidcommand.config.AssistantCapability
import ai.droidcommand.config.AssistantCapabilitySettingsStore
import ai.droidcommand.skills.DiscoveredSkill
import ai.droidcommand.skills.FileSkillStateStore
import ai.droidcommand.skills.SkillBindResult
import ai.droidcommand.skills.SkillDiscovery
import ai.droidcommand.skills.SkillParameter
import ai.droidcommand.skills.SkillParameterBinder
import ai.droidcommand.skills.SkillRegistry
import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject

data class SkillFileRow(
    val name: String,
    val description: String,
    val enabled: Boolean,
    val declaredTools: List<String>,
    val parameters: List<SkillParameter>,
    val values: Map<String, String>,
    val missingRequired: List<String>,
    val sourcePath: String,
)

data class SkillFilesUi(
    val skills: List<SkillFileRow> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val status: String? = null,
    val folderHint: String? = null,
)

@HiltViewModel
class SkillFilesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val capabilities: AssistantCapabilitySettingsStore,
) : ViewModel() {
    private val filesDir: Path = context.filesDir.toPath()
    private val skillsDir: Path = SkillFilesLocations.skillsDir(filesDir)
    private val stateFile: Path = SkillFilesLocations.stateFile(filesDir)
    private val valuesFile: Path = SkillFilesLocations.valuesFile(filesDir)
    private val stateStore = FileSkillStateStore(stateFile)
    private val discovery = SkillDiscovery()
    private val binder = SkillParameterBinder()
    private val paramValues = linkedMapOf<String, MutableMap<String, String>>()
    private var registry = SkillRegistry()

    private val mutable = MutableStateFlow(SkillFilesUi())
    val state: StateFlow<SkillFilesUi> = mutable.asStateFlow()

    init {
        Files.createDirectories(skillsDir)
        paramValues.putAll(SkillParameterValues.load(valuesFile).mapValues { it.value.toMutableMap() })
        refresh()
    }

    fun refresh() {
        if (!capabilities.load().isEnabled(AssistantCapability.SKILL_SCRIPTS)) {
            mutable.update {
                it.copy(
                    skills = emptyList(),
                    error = "Skill scripts are off. Turn them on under Advanced → Kai capabilities.",
                    status = null,
                    folderHint = skillsDir.toAbsolutePath().toString(),
                )
            }
            return
        }
        mutable.update { it.copy(busy = true, error = null) }
        runCatching {
            val report = discovery.discover(skillsDir)
            val next = SkillRegistry()
            report.discovered.forEach { discovered -> next.install(discovered.manifest) }
            next.applyEnabledState(stateStore.load())
            registry = next
            report.discovered.map { toRow(it, next) } to report.failures.size
        }.fold(
            onSuccess = { (rows, failureCount) ->
                mutable.update {
                    it.copy(
                        skills = rows.sortedBy { row -> row.name.lowercase() },
                        busy = false,
                        status = buildString {
                            append(if (rows.isEmpty()) "No skill files found." else "Found ${rows.size} skill file(s).")
                            if (failureCount > 0) append(" $failureCount file(s) could not be read.")
                        },
                        folderHint = skillsDir.toAbsolutePath().toString(),
                    )
                }
            },
            onFailure = { e ->
                mutable.update {
                    it.copy(
                        busy = false,
                        error = e.message ?: e.javaClass.simpleName,
                        folderHint = skillsDir.toAbsolutePath().toString(),
                    )
                }
            },
        )
    }

    fun setEnabled(name: String, enabled: Boolean) {
        if (enabled) registry.enable(name) else registry.disable(name)
        stateStore.save(registry.enabledStateByName())
        mutable.update { ui ->
            ui.copy(
                skills = ui.skills.map { if (it.name == name) it.copy(enabled = enabled) else it },
                status = if (enabled) "On: $name" else "Off: $name",
            )
        }
    }

    fun saveParameters(name: String, values: Map<String, String>) {
        paramValues[name] = values.toMutableMap()
        persistParamValues()
        mutable.update { ui ->
            ui.copy(
                skills = ui.skills.map { row ->
                    if (row.name != name) {
                        row
                    } else {
                        row.copy(values = values, missingRequired = missingRequired(row.parameters, values))
                    }
                },
                status = "Saved settings for $name.",
            )
        }
        registry.get(name)?.let { installed ->
            when (val bound = binder.bind(installed.manifest) { key -> paramValues[name]?.get(key) }) {
                is SkillBindResult.Bound -> Unit
                is SkillBindResult.MissingRequired -> mutable.update { ui ->
                    ui.copy(
                        status = "Saved settings for $name, but still missing: ${bound.missing.joinToString(", ")}.",
                    )
                }
            }
        }
    }

    fun openSkillsFolder() {
        mutable.update {
            it.copy(
                folderHint = skillsDir.toAbsolutePath().toString(),
                status = "Skills folder: ${skillsDir.toAbsolutePath()}",
            )
        }
    }

    private fun toRow(discovered: DiscoveredSkill, reg: SkillRegistry): SkillFileRow {
        val manifest = discovered.manifest
        val values = paramValues[manifest.name].orEmpty()
        return SkillFileRow(
            name = manifest.name,
            description = manifest.description,
            enabled = reg.get(manifest.name)?.enabled == true,
            declaredTools = manifest.declaredTools,
            parameters = manifest.parameters,
            values = values,
            missingRequired = missingRequired(manifest.parameters, values),
            sourcePath = discovered.sourcePath,
        )
    }

    private fun missingRequired(parameters: List<SkillParameter>, values: Map<String, String>): List<String> =
        parameters
            .filter { it.required && values[it.name].isNullOrBlank() && it.default.isNullOrBlank() }
            .map { it.name }

    private fun persistParamValues() {
        runCatching { SkillParameterValues.save(valuesFile, paramValues) }
    }
}
