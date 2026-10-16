package ai.droidcommand.app.ui.chat

import ai.droidcommand.app.ui.components.rememberDraft
import ai.droidcommand.templates.PromptTemplate
import ai.droidcommand.templates.TemplateEngine
import ai.droidcommand.templates.TemplateResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Saved chats, skills, and prompt templates. Templates expand into the input field and are not sent
 * on their own. A skill's text is added to later requests and is not written into the saved transcript.
 * The bundled set is seeded on first open. None of this has been run on a device.
 */
@Composable
fun ChatLibrary(state: ChatUiState, viewModel: ChatViewModel) {
    var filling by remember { mutableStateOf<PromptTemplate?>(null) }
    var slotValues by remember { mutableStateOf(mapOf<String, String>()) }
    var slotError by remember { mutableStateOf<String?>(null) }
    // Drafts of a template or skill being written survive rotation; the fill-in form above is tied to a template object, so it does not.
    var newTemplateName by rememberDraft()
    var newTemplateBody by rememberDraft()
    var newSkillName by rememberDraft()
    var newSkillPrompt by rememberDraft()

    Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Chats", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        if (state.savedChats.isEmpty()) {
            Text("No saved chats yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        state.savedChats.forEach { chat ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { viewModel.openChat(chat.id) }, enabled = chat.id != state.conversationId) {
                    Text(if (chat.id == state.conversationId) "${chat.title} (open)" else chat.title)
                }
                TextButton(onClick = { viewModel.deleteChat(chat.id) }) { Text("Delete") }
            }
        }

        Text("Skill", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text(
            "Added to each later request. It is not stored in the chat transcript.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { viewModel.selectSkill(null) }, enabled = state.skillId != null) { Text("No skill") }
        state.skills.forEach { skill ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (skill.id == state.skillId) {
                    Button(onClick = { viewModel.selectSkill(skill.id) }) { Text(skill.name) }
                } else {
                    OutlinedButton(onClick = { viewModel.selectSkill(skill.id) }) { Text(skill.name) }
                }
                if (!skill.builtIn) {
                    TextButton(onClick = { viewModel.deleteSkill(skill.id) }) { Text("Delete") }
                }
            }
        }
        OutlinedTextField(value = newSkillName, onValueChange = { newSkillName = it }, label = { Text("New skill name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = newSkillPrompt, onValueChange = { newSkillPrompt = it }, label = { Text("System text") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = {
                viewModel.saveSkill(newSkillName, newSkillPrompt)
                newSkillName = ""
                newSkillPrompt = ""
            },
            enabled = newSkillName.isNotBlank() && newSkillPrompt.isNotBlank(),
        ) { Text("Save skill") }

        Text("Templates", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text(
            "Fills the message box. Nothing is sent until you tap send.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        state.templates.forEach { template ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    slotError = null
                    if (template.variables.isEmpty()) {
                        filling = null
                        viewModel.insertDraft(template.body)
                    } else {
                        filling = template
                        slotValues = template.variables.associateWith { "" }
                    }
                }) { Text(template.name) }
                if (!template.builtIn) {
                    TextButton(onClick = { viewModel.deleteTemplate(template.id) }) { Text("Delete") }
                }
            }
        }
        filling?.takeIf { it.variables.isNotEmpty() }?.let { template ->
            Text(template.name, fontWeight = FontWeight.Bold)
            template.variables.forEach { name ->
                OutlinedTextField(
                    value = slotValues[name].orEmpty(),
                    onValueChange = { slotValues = slotValues + (name to it) },
                    label = { Text(name) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            slotError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(onClick = {
                when (val result = TemplateEngine.expand(template, slotValues)) {
                    is TemplateResult.Expanded -> {
                        viewModel.insertDraft(result.text)
                        filling = null
                        slotError = null
                    }
                    is TemplateResult.MissingVariables -> slotError = "Fill in: ${result.names.joinToString()}"
                }
            }) { Text("Use template") }
        }
        OutlinedTextField(value = newTemplateName, onValueChange = { newTemplateName = it }, label = { Text("New template name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = newTemplateBody, onValueChange = { newTemplateBody = it }, label = { Text("Template text, with {{slots}}") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = {
                viewModel.saveTemplate(newTemplateName, newTemplateBody)
                newTemplateName = ""
                newTemplateBody = ""
            },
            enabled = newTemplateName.isNotBlank() && newTemplateBody.isNotBlank(),
        ) { Text("Save template") }
        state.promptError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
