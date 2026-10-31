package ai.droidcommand.app.ui.notes

import ai.droidcommand.app.brain.BrainRuntime
import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitleWithHelp
import ai.droidcommand.app.ui.components.rememberDraft
import ai.droidcommand.app.ux.ScreenHelpCatalog
import ai.droidcommand.notes.BrainLibrarian
import ai.droidcommand.notes.DailyNotes
import ai.droidcommand.notes.FileTree
import ai.droidcommand.notes.FileTreeNode
import ai.droidcommand.notes.MarkdownBlock
import ai.droidcommand.notes.NoteGraph
import ai.droidcommand.notes.NoteParser
import ai.droidcommand.notes.NotePlugin
import ai.droidcommand.notes.NoteVault
import ai.droidcommand.notes.PluginStore
import ai.droidcommand.notes.SearchHit
import ai.droidcommand.notes.VaultEntry
import ai.droidcommand.notes.renderMarkdown
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

enum class NotesPane { TREE, SEARCH, GRAPH }

/**
 * State for the Brain screen (the same vault Notes already used). The notes live in a [NoteVault];
 * the Path constructor keeps JVM tests free of Android types. Hilt uses [BrainRuntime] so a
 * user-picked folder of markdown files can replace app storage.
 */
@HiltViewModel
class NotesViewModel internal constructor(
    root: Path,
    private val runtime: BrainRuntime? = null,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    @Inject
    constructor(runtime: BrainRuntime, @ApplicationContext context: Context) : this(
        context.filesDir.toPath().resolve("notes-vault"),
        runtime,
    )

    private var vault: NoteVault = runtime?.vault() ?: NoteVault(root)
    private val plugins = PluginStore(root)
    private var librarian = BrainLibrarian(vault)

    var folder by mutableStateOf("")
        private set
    var entries by mutableStateOf(vault.list())
        private set
    var tree by mutableStateOf<List<FileTreeNode>>(emptyList())
        private set
    var graph by mutableStateOf(NoteGraph(emptyList(), emptyList()))
        private set
    var pane by mutableStateOf(NotesPane.TREE)
        private set
    var expandedFolders by mutableStateOf<Set<String>>(emptySet())
        private set
    var openPath by mutableStateOf<String?>(null)
        private set
    var body by mutableStateOf("")
        private set
    var preview by mutableStateOf(false)
        private set
    var pluginText by mutableStateOf("")
    var installed by mutableStateOf(plugins.list())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var query by mutableStateOf("")
        private set
    var hits by mutableStateOf<List<SearchHit>>(emptyList())
        private set
    var openTags by mutableStateOf<List<String>>(emptyList())
        private set
    var openBacklinks by mutableStateOf<List<String>>(emptyList())
        private set
    var usingSaf by mutableStateOf(runtime?.usingSaf() == true)
        private set
    var aiCanReadWrite by mutableStateOf(runtime?.enabled() == true)
        private set

    /** The open note's text as last read from or written to disk. [body] differing from it means the edits are not saved yet. */
    private var savedBody by mutableStateOf("")

    /** True while the open note has edits that are not on disk. */
    val unsavedChanges: Boolean get() = openPath != null && body != savedBody

    init {
        refreshListing()
    }

    fun showPane(next: NotesPane) {
        pane = next
        if (next == NotesPane.GRAPH) {
            graph = librarian.graph()
        }
    }

    fun toggleFolder(path: String) {
        expandedFolders = if (path in expandedFolders) expandedFolders - path else expandedFolders + path
    }

    fun openTreeNode(node: FileTreeNode) {
        if (node.folder) {
            toggleFolder(node.relativePath)
            return
        }
        folder = node.relativePath.substringBeforeLast('/', "")
        entries = vault.list(folder)
        open(VaultEntry(node.name, node.relativePath, folder = false))
    }

    fun openGraphNode(path: String) {
        val name = path.substringAfterLast('/')
        folder = path.substringBeforeLast('/', "")
        entries = vault.list(folder)
        open(VaultEntry(name, path, folder = false))
    }

    fun openToday() {
        error = null
        val path = DailyNotes.today(clock)
        val date = LocalDate.now(clock)
        try {
            if (!vault.exists(path)) {
                vault.createFolder(DailyNotes.FOLDER)
                vault.write(path, DailyNotes.markdown(date))
                librarian.refresh()
            }
            refreshListing()
            folder = DailyNotes.FOLDER
            entries = vault.list(folder)
            open(VaultEntry(path.substringAfterLast('/'), path, folder = false))
        } catch (e: IllegalArgumentException) {
            error = e.message
        } catch (e: IOException) {
            error = "Could not open today's note: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun enableAiReadWrite(enabled: Boolean) {
        val host = runtime ?: return
        host.setEnabled(enabled)
        aiCanReadWrite = host.enabled()
    }

    fun open(entry: VaultEntry) {
        error = null
        if (entry.folder) {
            folder = entry.relativePath
            openPath = null
            entries = vault.list(folder)
            return
        }
        val text = try {
            vault.read(entry.relativePath)
        } catch (e: IOException) {
            // An unreadable file (not valid UTF-8, removed behind our back) must not crash the screen.
            error = "Could not open ${entry.name}: ${e.message ?: e.javaClass.simpleName}"
            return
        }
        openPath = entry.relativePath
        body = text
        savedBody = text
        preview = false
        val parsed = runCatching { librarian.parse(entry.relativePath) }.getOrNull()
        openTags = parsed?.tags?.sorted().orEmpty()
        openBacklinks = librarian.backlinks(entry.relativePath).map { it.title }
    }

    fun search(text: String) {
        query = text
        hits = if (text.isBlank()) emptyList() else librarian.search(text)
        if (text.isNotBlank()) pane = NotesPane.SEARCH
    }

    fun openHit(hit: SearchHit) {
        val name = hit.path.substringAfterLast('/')
        val folders = hit.path.substringBeforeLast('/', "")
        folder = folders
        open(VaultEntry(name, hit.path, folder = false))
    }

    fun useSafFolder(uri: Uri) {
        val host = runtime ?: return
        try {
            host.useSafFolder(uri)
            vault = host.vault()
            librarian = BrainLibrarian(vault)
            usingSaf = true
            folder = ""
            openPath = null
            hits = emptyList()
            error = null
            refreshListing()
        } catch (e: Exception) {
            error = "Could not open that folder: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun useAppFolder() {
        val host = runtime ?: return
        host.useAppFolder()
        vault = host.vault()
        librarian = BrainLibrarian(vault)
        usingSaf = false
        folder = ""
        openPath = null
        hits = emptyList()
        error = null
        refreshListing()
    }

    /**
     * Closes the open note and shows the folder it is in, or, with no note open, goes up one folder. Edits to an open note are
     * saved first; if that fails the note stays open with the reason in [error], so leaving never throws edits away.
     */
    fun up() {
        if (openPath != null) {
            if (unsavedChanges && !save()) return
            openPath = null
        } else {
            folder = folder.substringBeforeLast('/', "")
        }
        error = null
        entries = vault.list(folder)
    }

    fun newFolder(name: String) {
        val relative = child(name)
        try {
            vault.createFolder(relative)
            refreshListing()
            error = null
        } catch (e: IllegalArgumentException) {
            error = e.message
        } catch (e: IOException) {
            error = "Could not create the folder: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun newNote(name: String) {
        val file = if (name.endsWith(".md")) name else "$name.md"
        val relative = child(file)
        try {
            // write() replaces whatever is there, so a taken name is refused here. Typing the name of an existing note and
            // tapping Note used to empty that note.
            if (vault.exists(relative)) {
                error = "A note called $file already exists here."
                return
            }
            vault.write(relative, "")
            refreshListing()
            open(VaultEntry(file, relative, folder = false))
        } catch (e: IllegalArgumentException) {
            error = e.message
        } catch (e: IOException) {
            error = "Could not create the note: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun edit(text: String) {
        body = text
    }

    /** Writes the open note. Returns false, with the reason in [error], when it could not be saved. */
    fun save(): Boolean {
        val path = openPath ?: return true
        return try {
            vault.write(path, body)
            savedBody = body
            librarian.refresh()
            openTags = runCatching { librarian.parse(path).tags.sorted() }.getOrDefault(openTags)
            openBacklinks = librarian.backlinks(path).map { it.title }
            graph = librarian.graph()
            tree = FileTree.build(vault)
            error = null
            true
        } catch (e: IOException) {
            error = "Could not save: ${e.message ?: e.javaClass.simpleName}"
            false
        }
    }

    fun togglePreview() {
        preview = !preview
    }

    fun previewBlocks(): List<MarkdownBlock> = renderMarkdown(plugins.apply(body))

    fun installPlugin() {
        try {
            plugins.install(pluginText)
            pluginText = ""
            installed = plugins.list()
            error = null
        } catch (e: IllegalArgumentException) {
            error = e.message
        }
    }

    fun setPluginEnabled(id: String, enabled: Boolean) {
        plugins.setEnabled(id, enabled)
        installed = plugins.list()
    }

    fun removePlugin(id: String) {
        plugins.remove(id)
        installed = plugins.list()
    }

    private fun child(name: String): String = if (folder.isEmpty()) name else "$folder/$name"

    private fun refreshListing() {
        librarian.refresh()
        entries = vault.list(folder)
        tree = FileTree.build(vault)
        expandedFolders = expandedFolders + FileTree.folderPaths(tree)
        graph = librarian.graph()
        aiCanReadWrite = runtime?.enabled() == true
    }
}

/**
 * Folders and markdown notes on this device (the Brain). Preview is headings, bullets, code fences,
 * paragraphs, and `[[wikilinks]]`. Plugins are user-written text manifests. None are installed until
 * the user adds one, and none run as code.
 */
@Composable
fun NotesScreen(viewModel: NotesViewModel = hiltViewModel()) {
    var showPlugins by rememberSaveable { mutableStateOf(false) }
    var draftName by rememberDraft()
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.useSafFolder(uri)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("brain_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
    ) {
        item {
            ScreenTitleWithHelp("Brain", ScreenHelpCatalog.BRAIN)
            Text(
                when {
                    viewModel.openPath != null -> viewModel.openPath.orEmpty()
                    viewModel.folder.isEmpty() -> "Vault"
                    else -> viewModel.folder
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!showPlugins) {
            item { AiReadWriteRow(viewModel) }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (viewModel.folder.isNotEmpty() || viewModel.openPath != null) {
                    OutlinedButton(onClick = viewModel::up) { Text("Up") }
                }
                OutlinedButton(onClick = { showPlugins = !showPlugins }) { Text(if (showPlugins) "Notes" else "Plugins") }
                OutlinedButton(onClick = viewModel::openToday, modifier = Modifier.testTag("brain_today")) { Text("Today") }
            }
        }
        if (!showPlugins && viewModel.openPath == null) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PaneButton("Tree", NotesPane.TREE, viewModel, "brain_pane_tree")
                    PaneButton("Search", NotesPane.SEARCH, viewModel, "brain_pane_search")
                    PaneButton("Graph", NotesPane.GRAPH, viewModel, "brain_pane_graph")
                }
            }
        }
        viewModel.error?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        if (showPlugins) {
            item { PluginPanel(viewModel) }
        } else if (viewModel.openPath != null) {
            item { Editor(viewModel) }
        } else {
            if (viewModel.pane != NotesPane.GRAPH) {
                item { VaultPickerCard(viewModel, onPickFolder = { pickFolder.launch(null) }) }
            }
            if (viewModel.pane == NotesPane.SEARCH) {
                item { SearchCard(viewModel) }
                items(viewModel.hits, key = { "hit-${it.path}" }) { hit ->
                    DroidCard {
                        TextButton(onClick = { viewModel.openHit(hit) }, modifier = Modifier.fillMaxWidth()) {
                            Column {
                                Text(hit.title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                                Text(hit.snippet, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            } else if (viewModel.pane == NotesPane.GRAPH) {
                item { GraphPane(viewModel.graph, onOpen = viewModel::openGraphNode) }
            } else {
                item {
                    NewNoteCard(
                        draftName = draftName,
                        onDraftName = { draftName = it },
                        onNote = {
                            viewModel.newNote(draftName)
                            if (viewModel.error == null) draftName = ""
                        },
                        onFolder = {
                            viewModel.newFolder(draftName)
                            if (viewModel.error == null) draftName = ""
                        },
                    )
                }
                item { FileTreeCard(viewModel) }
            }
        }
    }
}

@Composable
private fun PaneButton(label: String, pane: NotesPane, viewModel: NotesViewModel, tag: String) {
    if (viewModel.pane == pane) {
        Button(onClick = { viewModel.showPane(pane) }, modifier = Modifier.testTag(tag)) { Text(label) }
    } else {
        OutlinedButton(onClick = { viewModel.showPane(pane) }, modifier = Modifier.testTag(tag)) { Text(label) }
    }
}

@Composable
private fun AiReadWriteRow(viewModel: NotesViewModel) {
    DroidCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text("AI can read and write these notes", fontWeight = FontWeight.Bold)
                Text(
                    if (viewModel.aiCanReadWrite) {
                        "On. Chat can search this vault and save a note after you confirm. Off again next time you turn the switch off."
                    } else {
                        "Off. Chat will not read or write this vault until you turn this on."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = viewModel.aiCanReadWrite,
                onCheckedChange = viewModel::enableAiReadWrite,
                modifier = Modifier.testTag("brain_ai_toggle"),
            )
        }
    }
}

@Composable
private fun VaultPickerCard(viewModel: NotesViewModel, onPickFolder: () -> Unit) {
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (viewModel.usingSaf) {
                    "This is the folder you picked. The same plain markdown files can be opened on a computer."
                } else {
                    "Notes stay on this phone as a folder of plain .md files. You can use a folder on this phone."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPickFolder) { Text("Use a folder on this phone") }
                if (viewModel.usingSaf) {
                    OutlinedButton(onClick = viewModel::useAppFolder) { Text("Use app folder") }
                }
            }
        }
    }
}

@Composable
private fun SearchCard(viewModel: NotesViewModel) {
    DroidCard {
        OutlinedTextField(
            value = viewModel.query,
            onValueChange = viewModel::search,
            label = { Text("Search notes") },
            modifier = Modifier.fillMaxWidth().testTag("brain_search"),
            singleLine = true,
        )
    }
}

@Composable
private fun NewNoteCard(
    draftName: String,
    onDraftName: (String) -> Unit,
    onNote: () -> Unit,
    onFolder: () -> Unit,
) {
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draftName,
                onValueChange = onDraftName,
                label = { Text("New name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onNote, enabled = draftName.isNotBlank()) { Text("Note") }
                OutlinedButton(onClick = onFolder, enabled = draftName.isNotBlank()) { Text("Folder") }
            }
        }
    }
}

@Composable
private fun FileTreeCard(viewModel: NotesViewModel) {
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("brain_file_tree")) {
            Text("File tree", fontWeight = FontWeight.Bold)
            if (viewModel.tree.isEmpty()) {
                Text("No notes yet. Add a note or tap Today.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FileTree.visible(viewModel.tree, viewModel.expandedFolders).forEach { (node, depth) ->
                val indent = "  ".repeat(depth)
                val label = when {
                    node.folder && node.relativePath in viewModel.expandedFolders -> "$indent Folder  ${node.name}"
                    node.folder -> "$indent Folder  ${node.name} …"
                    else -> "$indent${node.name}"
                }
                TextButton(onClick = { viewModel.openTreeNode(node) }, modifier = Modifier.fillMaxWidth()) {
                    Text(label, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun GraphPane(graph: NoteGraph, onOpen: (String) -> Unit) {
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Note graph", fontWeight = FontWeight.Bold)
            if (graph.nodes.isEmpty()) {
                Text("No notes in the graph yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                GraphCanvas(graph, onOpen)
                graph.nodes.forEach { node ->
                    TextButton(onClick = { onOpen(node.path) }, modifier = Modifier.fillMaxWidth()) {
                        Text(node.title, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                if (graph.edges.isNotEmpty()) {
                    Text("Links", fontWeight = FontWeight.Bold)
                    graph.edges.forEach { edge ->
                        val from = graph.nodes.firstOrNull { it.path == edge.from }?.title ?: edge.from
                        val to = graph.nodes.firstOrNull { it.path == edge.to }?.title ?: edge.to
                        Text(
                            "$from → $to",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GraphCanvas(graph: NoteGraph, onOpen: (String) -> Unit) {
    val edgeColor = MaterialTheme.colorScheme.outline
    val nodeColor = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .testTag("brain_graph_canvas")
            .pointerInput(graph) {
                detectTapGestures { offset ->
                    val path = graph.hit(size.width.toFloat(), size.height.toFloat(), offset.x, offset.y)
                    if (path != null) onOpen(path)
                }
            },
    ) {
        val points = graph.layout(size.width, size.height)
        val byPath = points.associateBy { it.path }
        for (edge in graph.edges) {
            val from = byPath[edge.from] ?: continue
            val to = byPath[edge.to] ?: continue
            drawLine(color = edgeColor, start = Offset(from.x, from.y), end = Offset(to.x, to.y), strokeWidth = 3f)
        }
        for (point in points) {
            drawCircle(color = nodeColor, radius = 14f, center = Offset(point.x, point.y))
        }
    }
}

@Composable
private fun Editor(viewModel: NotesViewModel) {
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(viewModel.openPath.orEmpty(), fontWeight = FontWeight.Bold)
            if (viewModel.openTags.isNotEmpty()) {
                Text(
                    "Tags: ${viewModel.openTags.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (viewModel.openBacklinks.isNotEmpty()) {
                Text(
                    "Linked from: ${viewModel.openBacklinks.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    "No other notes link here yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Wikilinks use [[Note name]]. Preview shows the label; the file stays plain markdown.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (viewModel.unsavedChanges) {
                Text("Unsaved changes. Save, or go Up: leaving saves them.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.save() }) { Text("Save") }
                OutlinedButton(onClick = viewModel::togglePreview) { Text(if (viewModel.preview) "Edit" else "Preview") }
            }
            if (viewModel.preview) {
                viewModel.previewBlocks().forEach { block ->
                    when (block) {
                        is MarkdownBlock.Heading -> PreviewText(block.text, fontWeight = FontWeight.Bold, fontSize = (22 - block.level).sp)
                        is MarkdownBlock.Bullet -> PreviewText("• ${block.text}")
                        is MarkdownBlock.Code -> Text(block.text, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        is MarkdownBlock.Paragraph -> PreviewText(block.text)
                    }
                }
            } else {
                OutlinedTextField(
                    value = viewModel.body,
                    onValueChange = viewModel::edit,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 8,
                    label = { Text("Markdown") },
                )
            }
        }
    }
}

@Composable
private fun PreviewText(
    text: String,
    fontWeight: FontWeight? = null,
    fontSize: TextUnit = TextUnit.Unspecified,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val links = NoteParser.wikiLinks(text)
    if (links.isEmpty()) {
        Text(text, fontWeight = fontWeight ?: FontWeight.Normal, fontSize = fontSize, color = MaterialTheme.colorScheme.onSurface)
        return
    }
    val annotated = buildAnnotatedString {
        var last = 0
        for (link in links) {
            val start = link.start.coerceIn(0, text.length)
            val end = link.end.coerceIn(start, text.length)
            if (start > last) append(text.substring(last, start))
            withStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.Bold)) {
                append(link.alias ?: link.target)
            }
            last = end
        }
        if (last < text.length) append(text.substring(last))
    }
    Text(annotated, fontWeight = fontWeight ?: FontWeight.Normal, fontSize = fontSize)
}

@Composable
private fun PluginPanel(viewModel: NotesViewModel) {
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your plugins", style = MaterialTheme.typography.titleMedium)
            Text(
                "None come with the app. A plugin is a text manifest: literal replacements and an optional prefix or suffix. It is not code, and it is saved disabled until you enable it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = viewModel.pluginText,
                onValueChange = { viewModel.pluginText = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                label = { Text("id, name, replace") },
            )
            Button(onClick = viewModel::installPlugin, enabled = viewModel.pluginText.isNotBlank()) { Text("Install") }
            if (viewModel.installed.isEmpty()) {
                Text("No plugins installed.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            viewModel.installed.forEach { plugin ->
                PluginRow(
                    plugin,
                    onEnabled = { enabled -> viewModel.setPluginEnabled(plugin.id, enabled) },
                    onRemove = { viewModel.removePlugin(plugin.id) },
                )
            }
        }
    }
}

@Composable
private fun PluginRow(plugin: NotePlugin, onEnabled: (Boolean) -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(plugin.name, fontWeight = FontWeight.Bold)
            Text(plugin.id, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onRemove) { Text("Remove") }
        Switch(checked = plugin.enabled, onCheckedChange = onEnabled)
    }
}
