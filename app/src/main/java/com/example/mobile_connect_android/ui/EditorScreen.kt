package com.example.mobile_connect_android.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Scaffold
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.example.mobile_connect_android.R
import com.example.mobile_connect_android.data.FileKind
import com.example.mobile_connect_android.data.Path
import com.example.mobile_connect_android.domain.AppModel
import com.example.mobile_connect_android.domain.EditorDocument
import com.example.mobile_connect_android.domain.LiveText
import com.example.mobile_connect_android.domain.VoiceInputManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Opens a file from the server: text is edited in place, images and PDFs are previewed, anything else can be opened or shared. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(
    document: EditorDocument,
    model: AppModel,
    onBack: () -> Unit,
    bottomPadding: Dp = 0.dp
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by model.prefs.collectAsState()
    val voiceState by model.voiceInputManager.state.collectAsState()
    val voiceLevel by model.voiceInputManager.level.collectAsState()
    val voiceError by model.voiceInputManager.errorMessage.collectAsState()
    val path = document.path
    val kind = document.info?.kind
    val keyboardController = LocalSoftwareKeyboardController.current

    var editorView by remember { mutableStateOf<CodeEditorView?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    /** Counts manual saves; each one flashes the "Changes Saved" pill. */
    var savedCount by remember { mutableIntStateOf(0) }
    var showSaved by remember { mutableStateOf(false) }
    val keyboardVisible = WindowInsets.isImeVisible

    LaunchedEffect(document) {
        if (document.info == null) document.load(model.api, context)
    }

    fun close() {
        keyboardController?.hide()
        model.voiceInputManager.cancel()
        document.keepDraft(context)
        onBack()
    }

    /** Leaving the file: if it has unsaved changes, ask to save or discard them first. */
    fun leave() {
        model.voiceInputManager.cancel()
        document.refreshDirty()
        if (document.dirty) confirmLeave = true else close()
    }

    fun save() {
        keyboardController?.hide()
        scope.launch { if (document.save(model.api, context)) savedCount++ }
    }

    /** Start dictating (or stop): the words are typed into this file at the cursor as they are spoken. */
    val withMicrophone = rememberWithMicrophone(model)

    fun pressVoice() = withMicrophone {
        val live = LiveText()
        model.voiceInputManager.press(onText = { spoken ->
            val view = editorView ?: return@press
            if (confirmLeave || document.conflict) return@press
            live.apply(spoken, erase = { view.typeSpoken(it, "") }, type = { view.typeSpoken(0, it) })
        })
    }

    BackHandler(onBack = ::leave)

    // Switching apps (or a crash) never loses work.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, document) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                model.voiceInputManager.cancel()
                document.keepDraft(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            model.voiceInputManager.cancel()
            document.keepDraft(context)
        }
    }

    LaunchedEffect(savedCount) {
        if (savedCount == 0) return@LaunchedEffect
        showSaved = true
        delay(1500)
        showSaved = false
    }

    LaunchedEffect(document.conflict) {
        if (document.conflict) model.voiceInputManager.cancel()
    }

    Scaffold(
        topBar = {
            TopAppBar(
            title = {
                Column {
                    Text(Path.name(path), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        path,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.StartEllipsis
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = ::leave) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = "Back")
                }
            },
            actions = {
                if (kind == FileKind.TEXT) {
                    IconButton(onClick = ::pressVoice, enabled = !document.conflict) {
                        Icon(
                            painterResource(R.drawable.ic_mic),
                            contentDescription = "Voice input",
                            tint = if (voiceState != VoiceInputManager.State.IDLE) Color(0xFFC29BFF) else Color.White
                        )
                    }
                    Button(
                        onClick = ::save,
                        enabled = document.dirty && !document.saving,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        if (document.saving) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        } else {
                            Text("Save")
                        }
                    }
                } else if (document.localFile != null) {
                    IconButton(onClick = { shareFile(context, document.localFile!!) }) {
                        Icon(painterResource(R.drawable.ic_share), contentDescription = "Share")
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
        )
        },
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding(), bottom = bottomPadding)
                // The keyboard covers the nav bar, so only the part of it taller than the nav bar needs room.
                .consumeWindowInsets(PaddingValues(bottom = bottomPadding))
                .imePadding()
        ) {
            Box(
                modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (kind) {
                null -> {
                    val error = document.loadError
                    if (error != null) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(error, color = Color(0xFFFF6B6B))
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(onClick = { scope.launch { document.load(model.api, context) } }) { Text("Try Again") }
                        }
                    } else {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                }
                FileKind.TEXT -> AndroidView(
                    factory = { ctx ->
                        CodeEditorView(ctx, document).also {
                            it.language = SyntaxLanguage.detect(path)
                            editorView = it
                        }
                    },
                    update = { it.update(prefs.editorFontSize, prefs.wrap) },
                    modifier = Modifier.fillMaxSize()
                )
                FileKind.IMAGE -> Preview(document, model, autoLoad = true) { ZoomableImage(it) }
                FileKind.PDF -> Preview(document, model, autoLoad = true) { PdfPages(it) }
                FileKind.BINARY, FileKind.LARGE -> Preview(document, model, autoLoad = false) { file -> FileActions(file) }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AnimatedVisibility(visible = showSaved, enter = fadeIn(), exit = fadeOut()) {
                    Pill { Text("Changes Saved", fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
                }
                if (voiceState != VoiceInputManager.State.IDLE) {
                    VoiceHud(voiceState, voiceLevel, onClick = ::pressVoice)
                }
            }
        }

        val view = editorView
        if (kind == FileKind.TEXT && prefs.editorKeys && keyboardVisible && view != null) {
            EditorKeyBar(view)
        }
        }
    }

    if (confirmLeave) {
        AppDialog(
            title = "Save changes?",
            text = "Your changes to “${Path.name(path)}” haven't been saved.",
            onDismiss = { confirmLeave = false },
            confirmText = "Save",
            onConfirm = {
                confirmLeave = false
                scope.launch {
                    if (!document.save(model.api, context)) return@launch
                    savedCount++
                    delay(900)  // long enough to read "Changes Saved"
                    close()
                }
            },
            neutralText = "Discard",
            neutralDestructive = true,
            onNeutral = {
                confirmLeave = false
                document.discard(context)
                close()
            }
        )
    }

    if (document.conflict) {
        AppDialog(
            title = "File changed elsewhere",
            text = "“${Path.name(path)}” was modified after you opened it, for example from the terminal. Overwrite it with your version, or reload the latest version and lose your changes.",
            onDismiss = { document.conflict = false },
            confirmText = "Overwrite",
            onConfirm = {
                document.conflict = false
                scope.launch { if (document.save(model.api, context, force = true)) savedCount++ }
            },
            neutralText = "Reload",
            neutralDestructive = true,
            onNeutral = {
                document.conflict = false
                scope.launch { document.load(model.api, context, ignoreDraft = true) }
            }
        )
    }

    document.errorMessage?.let { message ->
        AppDialog(
            title = "Something went wrong",
            text = message,
            onDismiss = { document.errorMessage = null },
            dismissText = null,
            confirmText = "OK",
            onConfirm = { document.errorMessage = null }
        )
    }

    voiceError?.let { message -> VoiceErrorDialog(message) { model.voiceInputManager.clearError() } }
}

/** Indent, outdent, arrows, undo, redo and hide keyboard, above the keyboard. */
@Composable
private fun EditorKeyBar(view: CodeEditorView) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EditorKey(R.drawable.ic_indent, "Indent") { view.indent() }
        EditorKey(R.drawable.ic_outdent, "Outdent") { view.outdent() }
        EditorKey(label = "←", title = "Left") { view.moveLeft() }
        EditorKey(label = "↓", title = "Down") { view.moveDown() }
        EditorKey(label = "↑", title = "Up") { view.moveUp() }
        EditorKey(label = "→", title = "Right") { view.moveRight() }
        EditorKey(R.drawable.ic_undo, "Undo") { view.undo() }
        EditorKey(R.drawable.ic_redo, "Redo") { view.redo() }
        EditorKey(R.drawable.ic_keyboard_hide, "Hide keyboard") { view.hideKeyboard() }
    }
}

@Composable
private fun EditorKey(icon: Int? = null, title: String, label: String? = null, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .height(36.dp)
            .widthIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f))
            .clickable(onClickLabel = title) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = title, modifier = Modifier.size(20.dp), tint = Color.White)
        } else {
            Text(label ?: "", fontSize = 16.sp, color = Color.White)
        }
    }
}

/** Downloads the raw file and shows it, or for files that can't be shown, offers to download, open and share. */
@Composable
private fun Preview(document: EditorDocument, model: AppModel, autoLoad: Boolean, content: @Composable (File) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = document.localFile
    LaunchedEffect(document, autoLoad) {
        if (autoLoad) document.download(model.api, context)
    }
    if (file != null) {
        content(file)
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(Path.name(document.path), color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        document.info?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Text(android.text.format.Formatter.formatShortFileSize(context, it.size.toLong()), color = Color.Gray, fontSize = 14.sp)
        }
        Spacer(modifier = Modifier.height(20.dp))
        if (document.downloading) {
            CircularProgressIndicator()
        } else {
            Button(onClick = { scope.launch { document.download(model.api, context) } }) { Text("Download") }
        }
    }
}

/** Open in another app, or share. */
@Composable
private fun FileActions(file: File) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(file.name, color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(android.text.format.Formatter.formatShortFileSize(context, file.length()), color = Color.Gray, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = { openFile(context, file) }) { Text("Open With") }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = { shareFile(context, file) }) { Text("Share") }
    }
}

/** Pinch to zoom, drag to pan, double tap to reset. */
@Composable
private fun ZoomableImage(file: File) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    scale = if (scale > 1f) 1f else 2.5f
                    offset = Offset.Zero
                })
            }
    ) {
        AsyncImage(
            model = file,
            contentDescription = file.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        )
    }
}

/** Every page of a PDF, rendered at the screen's width as it scrolls into view. */
@Composable
private fun PdfPages(file: File) {
    val renderer = remember(file) {
        runCatching { PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) }.getOrNull()
    }
    // The renderer opens one page at a time.
    val lock = remember(file) { Mutex() }
    DisposableEffect(renderer) { onDispose { renderer?.close() } }
    if (renderer == null) {
        Text("This PDF can't be shown.", color = Color.Gray, modifier = Modifier.padding(24.dp))
        return
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(renderer.pageCount) { index ->
                val page by produceState<Bitmap?>(null, index, widthPx) {
                    value = withContext(Dispatchers.IO) {
                        lock.withLock {
                            renderer.openPage(index).use { p ->
                                val height = (widthPx.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                                Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888).also {
                                    it.eraseColor(android.graphics.Color.WHITE)
                                    p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                }
                            }
                        }
                    }
                }
                val bitmap = page
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Page ${index + 1}",
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(bitmap.width.toFloat() / bitmap.height)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.707f)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                }
            }
        }
    }
}

private fun contentUri(context: Context, file: File) =
    FileProvider.getUriForFile(context, context.packageName + ".files", file)

private fun mimeType(file: File): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"

private fun openFile(context: Context, file: File) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(contentUri(context, file), mimeType(file))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(intent, "Open With"))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this file.", Toast.LENGTH_SHORT).show()
    }
}

private fun shareFile(context: Context, file: File) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType(mimeType(file))
        .putExtra(Intent.EXTRA_STREAM, contentUri(context, file))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Share"))
}
