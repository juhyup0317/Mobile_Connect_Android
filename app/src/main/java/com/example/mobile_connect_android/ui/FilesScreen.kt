package com.example.mobile_connect_android.ui

import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.mutableStateListOf
import androidx.activity.compose.BackHandler
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.example.mobile_connect_android.data.Entry
import com.example.mobile_connect_android.data.Listing
import com.example.mobile_connect_android.data.Path
import com.example.mobile_connect_android.data.SearchHit
import com.example.mobile_connect_android.data.SortKey
import com.example.mobile_connect_android.domain.AppModel
import com.example.mobile_connect_android.R
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    model: AppModel,
    navigation: FolderNavigation,
    onOpenFile: (String) -> Unit,
    bottomPadding: Dp = 0.dp
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val currentPath = navigation.path
    var listing by remember { mutableStateOf<Listing?>(null) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    var searchQuery by remember { mutableStateOf("") }
    var searchHits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    var isSelecting by remember { mutableStateOf(false) }
    var selectedEntries by remember { mutableStateOf(setOf<String>()) }
    var showMenu by remember { mutableStateOf(false) }
    var showSearchBar by remember { mutableStateOf(false) }

    var promptDialog by remember { mutableStateOf<PromptType?>(null) }
    var promptText by remember { mutableStateOf(TextFieldValue("")) }
    var newFileFormat by remember { mutableStateOf(NewFileFormat.all.first()) }
    var deleteConfirmEntry by remember { mutableStateOf<Entry?>(null) }
    var bulkDeleteConfirm by remember { mutableStateOf(false) }

    val uiState by model.ui.collectAsState()
    val screenKeyboard = LocalSoftwareKeyboardController.current

    fun loadCurrentDir() {
        scope.launch {
            loading = true
            errorMsg = null
            try {
                listing = model.api.list(currentPath)
                model.updateUiState { ui ->
                    ui.cwd = currentPath
                    ui.recent = (listOf(currentPath) + ui.recent.filter { it != currentPath }).take(20)
                }
            } catch (e: Exception) {
                errorMsg = e.message ?: "Failed to list folder"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(currentPath) {
        loadCurrentDir()
    }

    LaunchedEffect(searchQuery) {
        if (searchQuery.length >= 2) {
            searching = true
            try {
                searchHits = model.api.search(currentPath, searchQuery)
            } catch (_: Exception) {
                searchHits = emptyList()
            } finally {
                searching = false
            }
        } else {
            searchHits = emptyList()
            searching = false
        }
    }

    val visibleEntries = remember(listing, uiState) {
        val all = (listing?.entries ?: emptyList()).filter { uiState.showHidden || !it.name.startsWith(".") }
        all.sortedWith { a, b ->
            if (uiState.foldersFirst && a.isDir != b.isDir) {
                if (a.isDir) -1 else 1
            } else {
                val comp = when (uiState.sort) {
                    SortKey.NAME -> a.name.compareTo(b.name, ignoreCase = true)
                    SortKey.MTIME -> a.mtime.compareTo(b.mtime)
                    SortKey.SIZE -> a.size.compareTo(b.size)
                }
                if (uiState.sortDesc) -comp else comp
            }
        }
    }

    val entriesList = rememberLazyListState()
    val hitsList = rememberLazyListState()
    val showingHits = searchQuery.length >= 2
    // Back closes search, then select mode, then returns to the previous folder.
    BackHandler(enabled = showSearchBar || isSelecting || navigation.canGoBack) {
        when {
            showSearchBar -> {
                showSearchBar = false
                searchQuery = ""
            }
            isSelecting -> {
                isSelecting = false
                selectedEntries = emptySet()
            }
            else -> navigation.back()
        }
    }

    val hideOnScroll = rememberHideOnScroll {
        val list = if (showingHits) hitsList else entriesList
        list.canScrollForward || list.canScrollBackward
    }
    // New contents may be too short to scroll, which would leave the header out of reach.
    LaunchedEffect(currentPath, visibleEntries.size, searchHits.size) { hideOnScroll.show() }

    Scaffold(
        modifier = Modifier.nestedScroll(hideOnScroll.connection),
        topBar = {
            HideOnScrollHeader(hideOnScroll) {
                CenterAlignedTopAppBar(
                    title = {
                        Text(if (isSelecting) "${selectedEntries.size} Selected" else "Files", fontSize = 18.sp)
                    },
                    navigationIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (currentPath != "/") {
                                IconButton(onClick = {
                                    val parent = Path.parent(currentPath)
                                    navigation.open(parent)
                                }) {
                                    Icon(painterResource(R.drawable.ic_back), contentDescription = "Back")
                                }
                            }
                            if (isSelecting) {
                                TextButton(onClick = {
                                    isSelecting = false
                                    selectedEntries = emptySet()
                                }) {
                                    Text("Done")
                                }
                            }
                        }
                    },
                    actions = {
                        if (isSelecting) {
                            TextButton(onClick = {
                                selectedEntries = if (selectedEntries.size == visibleEntries.size) emptySet() else visibleEntries.map { it.name }.toSet()
                            }) {
                                Text("Select All")
                            }
                            IconButton(
                                onClick = { bulkDeleteConfirm = true },
                                enabled = selectedEntries.isNotEmpty()
                            ) {
                                Text("🗑️", fontSize = 18.sp)
                            }
                        } else {
                            IconButton(onClick = { showSearchBar = !showSearchBar }) {
                                Icon(painterResource(R.drawable.ic_search), contentDescription = "Search")
                            }
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Text("⋮", color = MaterialTheme.colorScheme.onBackground, fontSize = 20.sp)
                                }
                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Select") },
                                        enabled = visibleEntries.isNotEmpty(),
                                        onClick = {
                                            showMenu = false
                                            isSelecting = true
                                        }
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("New File") },
                                        onClick = {
                                            showMenu = false
                                            promptText = TextFieldValue("")
                                            newFileFormat = NewFileFormat.all.first()
                                            promptDialog = PromptType.NEW_FILE
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("New Folder") },
                                        onClick = {
                                            showMenu = false
                                            promptText = TextFieldValue("")
                                            promptDialog = PromptType.NEW_FOLDER
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Home") },
                                        onClick = {
                                            showMenu = false
                                            navigation.open(model.home)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Root /") },
                                        onClick = {
                                            showMenu = false
                                            navigation.open("/")
                                        }
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    windowInsets = WindowInsets(0)
                )

                // Path breadcrumbs
                PathBar(path = currentPath, onSelect = { navigation.open(it) })

                // Search bar
                if (showSearchBar) {
                    val focusRequester = remember { FocusRequester() }
                    val keyboardController = LocalSoftwareKeyboardController.current

                    LaunchedEffect(Unit) {
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search in this folder", color = Color.Gray, fontSize = 14.sp) },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = {
                            showSearchBar = false
                            searchQuery = ""
                            keyboardController?.hide()
                        }) {
                            Text("Cancel")
                        }
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            if (loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (errorMsg != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(errorMsg!!, color = Color.Red)
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = { loadCurrentDir() }) {
                            Text("Retry")
                        }
                    }
                }
            } else if (searchQuery.length >= 2) {
                if (searching) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Searching", color = Color.Gray)
                    }
                } else if (searchHits.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No results found", color = Color.Gray)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize(), state = hitsList, contentPadding = PaddingValues(bottom = bottomPadding)) {
                        items(searchHits) { hit ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (hit.dir) navigation.open(hit.path) else onOpenFile(hit.path)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(if (hit.dir) "📁" else "📄", fontSize = 18.sp, modifier = Modifier.padding(end = 12.dp))
                                Text(hit.path.removePrefix("$currentPath/"), color = MaterialTheme.colorScheme.onBackground, fontSize = 15.sp)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f))
                        }
                    }
                }
            } else if (visibleEntries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Folder is empty", color = Color.Gray)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), state = entriesList, contentPadding = PaddingValues(bottom = bottomPadding)) {
                    items(visibleEntries) { entry ->
                        var rowMenuOpen by remember { mutableStateOf(false) }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isSelecting) {
                                        selectedEntries = if (selectedEntries.contains(entry.name)) {
                                            selectedEntries - entry.name
                                        } else {
                                            selectedEntries + entry.name
                                        }
                                    } else {
                                        val fullPath = Path.join(currentPath, entry.name)
                                        if (entry.isDir) {
                                            navigation.open(fullPath)
                                        } else {
                                            onOpenFile(fullPath)
                                        }
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                if (isSelecting) {
                                    Checkbox(
                                        checked = selectedEntries.contains(entry.name),
                                        onCheckedChange = { checked ->
                                            selectedEntries = if (checked) selectedEntries + entry.name else selectedEntries - entry.name
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(if (entry.isDir) "📁" else "📄", fontSize = 20.sp, modifier = Modifier.padding(end = 12.dp))
                                Column {
                                    Text(entry.name, color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                    val dateStr = if (entry.mtime > 0) SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(entry.mtime.toLong())) else ""
                                    val sizeStr = if (entry.isDir) dateStr else "${entry.size.toLong()} B · $dateStr"
                                    Text(sizeStr, color = Color.Gray, fontSize = 12.sp)
                                }
                            }

                            if (!isSelecting) {
                                Box {
                                    IconButton(onClick = { rowMenuOpen = true }) {
                                        Text("⋮", color = Color.Gray, fontSize = 18.sp)
                                    }
                                    DropdownMenu(expanded = rowMenuOpen, onDismissRequest = { rowMenuOpen = false }) {
                                        DropdownMenuItem(
                                            text = { Text("Rename") },
                                            onClick = {
                                                rowMenuOpen = false
                                                // Select the name without its extension, ready to retype.
                                                val stem = entry.name.lastIndexOf('.').takeIf { it > 0 && !entry.isDir } ?: entry.name.length
                                                promptText = TextFieldValue(entry.name, TextRange(0, stem))
                                                promptDialog = PromptType.RENAME(entry)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Copy Path") },
                                            onClick = {
                                                rowMenuOpen = false
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                clipboard.setPrimaryClip(ClipData.newPlainText("Path", Path.join(currentPath, entry.name)))
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Delete") },
                                            onClick = {
                                                rowMenuOpen = false
                                                deleteConfirmEntry = entry
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
                    }
                }
            }
        }
    }

    promptDialog?.let { prompt ->
        val (title, action) = when (prompt) {
            PromptType.NEW_FILE -> "New file" to "Create"
            PromptType.NEW_FOLDER -> "New folder" to "Create"
            is PromptType.RENAME -> "Rename" to "Rename"
        }
        val typed = promptText.text.trim()
        val name = if (prompt == PromptType.NEW_FILE && typed.isNotEmpty()) NewFileFormat.apply(typed, newFileFormat) else typed
        val original = (prompt as? PromptType.RENAME)?.entry?.name
        val nameError = when {
            name.contains('/') -> "Names can't contain /"
            name == "." || name == ".." -> "That name is reserved"
            name != original && listing?.entries?.any { it.name == name } == true -> "Something with this name already exists"
            else -> null
        }
        val canSubmit = name.isNotEmpty() && nameError == null && name != original

        // The dialog has its own window, so its keyboard is closed through that window's controller.
        var dialogKeyboard by remember { mutableStateOf<androidx.compose.ui.platform.SoftwareKeyboardController?>(null) }
        fun closePrompt() {
            dialogKeyboard?.hide()
            screenKeyboard?.hide()
            promptDialog = null
        }
        fun submit() {
            if (!canSubmit) return
            closePrompt()
            scope.launch {
                try {
                    when (prompt) {
                        PromptType.NEW_FILE -> {
                            val created = Path.join(currentPath, name)
                            model.api.touch(created)
                            onOpenFile(created)
                        }
                        PromptType.NEW_FOLDER -> model.api.mkdir(Path.join(currentPath, name))
                        is PromptType.RENAME -> model.api.rename(Path.join(currentPath, prompt.entry.name), Path.join(currentPath, name))
                    }
                    loadCurrentDir()
                } catch (e: Exception) {
                    errorMsg = e.message
                }
            }
        }

        AppDialog(
            title = title,
            onDismiss = ::closePrompt,
            confirmText = action,
            onConfirm = ::submit,
            confirmEnabled = canSubmit
        ) {
            val focusRequester = remember { FocusRequester() }
            val keyboard = LocalSoftwareKeyboardController.current
            LaunchedEffect(Unit) {
                dialogKeyboard = keyboard
                focusRequester.requestFocus()
                keyboard?.show()
            }
            if (prompt == PromptType.NEW_FILE) {
                // The format, right under the title: its extension is added to the name.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NewFileFormat.all.forEach { format ->
                        FilterChip(
                            selected = format == newFileFormat,
                            onClick = { newFileFormat = format },
                            label = { Text(if (format.isEmpty()) "None" else ".$format", fontFamily = FontFamily.Monospace) }
                        )
                    }
                }
            }
            OutlinedTextField(
                value = promptText,
                onValueChange = { promptText = it },
                label = { Text(if (prompt == PromptType.NEW_FOLDER) "Folder name" else "File name") },
                suffix = if (prompt == PromptType.NEW_FILE && newFileFormat.isNotEmpty() && !typed.endsWith(".$newFileFormat", ignoreCase = true)) {
                    { Text(".$newFileFormat", fontFamily = FontFamily.Monospace) }
                } else null,
                singleLine = true,
                isError = nameError != null,
                supportingText = nameError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )
        }
    }

    deleteConfirmEntry?.let { entry ->
        AppDialog(
            title = "Delete “${entry.name}”?",
            text = if (entry.isDir) "This folder and everything in it will be permanently deleted." else "This file will be permanently deleted.",
            onDismiss = { deleteConfirmEntry = null },
            confirmText = "Delete",
            destructive = true,
            onConfirm = {
                deleteConfirmEntry = null
                scope.launch {
                    try {
                        model.api.delete(Path.join(currentPath, entry.name))
                        loadCurrentDir()
                    } catch (e: Exception) {
                        errorMsg = e.message
                    }
                }
            }
        )
    }

    if (bulkDeleteConfirm) {
        val count = selectedEntries.size
        AppDialog(
            title = if (count == 1) "Delete 1 item?" else "Delete $count items?",
            text = if (count == 1) "It will be permanently deleted, including anything inside it." else "They will be permanently deleted, including anything inside them.",
            onDismiss = { bulkDeleteConfirm = false },
            confirmText = "Delete",
            destructive = true,
            onConfirm = {
                bulkDeleteConfirm = false
                val items = selectedEntries
                isSelecting = false
                selectedEntries = emptySet()
                scope.launch {
                    try {
                        items.forEach { name ->
                            model.api.delete(Path.join(currentPath, name))
                        }
                        loadCurrentDir()
                    } catch (e: Exception) {
                        errorMsg = e.message
                    }
                }
            }
        )
    }
}

/** The folder the Files tab shows and the ones visited before it, for Back. It outlives the screen, so opening a file keeps the place. */
class FolderNavigation(start: String) {
    var path by mutableStateOf(start)
        private set
    private val history = mutableStateListOf<String>()
    val canGoBack: Boolean get() = history.isNotEmpty()

    fun open(newPath: String) {
        if (newPath == path) return
        history.add(path)
        path = newPath
    }

    fun back() {
        if (history.isNotEmpty()) path = history.removeAt(history.lastIndex)
    }
}

/** The formats offered for a new file, the first one the default; "" means no extension (Makefile, Dockerfile). */
object NewFileFormat {
    val all = listOf("txt", "md", "py", "js", "ts", "json", "html", "css", "sh", "yaml", "")

    /** The name with the format's extension added, unless it already ends with it. */
    fun apply(name: String, format: String): String =
        if (format.isEmpty() || name.endsWith(".$format", ignoreCase = true)) name else "$name.$format"
}

sealed class PromptType {
    object NEW_FILE : PromptType()
    object NEW_FOLDER : PromptType()
    data class RENAME(val entry: Entry) : PromptType()
}

@Composable
fun PathBar(path: String, onSelect: (String) -> Unit) {
    val parts = remember(path) {
        var built = ""
        listOf("/" to "/") + path.split("/").filter { it.isNotEmpty() }.map { part ->
            built += "/$part"
            part to built
        }
    }

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            parts.forEachIndexed { idx, (name, fullPath) ->
                if (idx > 0) {
                    Text(" › ", color = Color.Gray, fontSize = 12.sp)
                }
                Text(
                    text = name,
                    color = if (fullPath == path) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                    fontSize = 14.sp,
                    fontWeight = if (fullPath == path) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clickable { onSelect(fullPath) }
                )
            }
        }
    }
}
