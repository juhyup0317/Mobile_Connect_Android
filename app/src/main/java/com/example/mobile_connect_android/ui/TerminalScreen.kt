package com.example.mobile_connect_android.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Scaffold
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.mobile_connect_android.R
import com.example.mobile_connect_android.data.Prefs
import com.example.mobile_connect_android.data.TermSession
import com.example.mobile_connect_android.domain.AppModel
import com.example.mobile_connect_android.domain.LinkState
import com.example.mobile_connect_android.domain.LiveText
import com.example.mobile_connect_android.domain.TermController
import com.example.mobile_connect_android.domain.VoiceInputManager
import kotlinx.coroutines.delay

/** One live terminal. Reconnects when the app returns to the foreground and keeps the web session alive while open. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(
    session: TermSession,
    model: AppModel,
    onBack: () -> Unit,
    onClose: () -> Unit,
    bottomPadding: Dp = 0.dp
) {
    val context = LocalContext.current
    val prefs by model.prefs.collectAsState()
    val termPaths by model.termPaths.collectAsState()
    val voiceState by model.voiceInputManager.state.collectAsState()
    val voiceLevel by model.voiceInputManager.level.collectAsState()
    val voiceError by model.voiceInputManager.errorMessage.collectAsState()

    val controller = remember(session.id) { TermController(model.api, context.applicationContext, session.id) }
    var termView by remember { mutableStateOf<TermView?>(null) }
    val keyboardVisible = WindowInsets.isImeVisible
    var showMenu by remember { mutableStateOf(false) }

    val withMicrophone = rememberWithMicrophone(model)

    fun pressVoice() = withMicrophone {
        val live = LiveText()
        model.voiceInputManager.press(
            onText = { spoken ->
                live.apply(spoken, erase = { controller.typeSpoken(it, "") }, type = { controller.typeSpoken(0, it) })
            },
            onFinish = { if (model.voiceEnter.value) controller.pressReturn() }
        )
    }

    controller.onPath = { model.setTermPath(session.id, it) }
    controller.onToggleKeyboard = { if (keyboardVisible) termView?.hideKeyboard() else termView?.showKeyboard() }

    BackHandler(onBack = onBack)

    DisposableEffect(controller) {
        onDispose {
            model.voiceInputManager.cancel()
            controller.close()
        }
    }

    // In the background the socket closes; back in the foreground it reconnects and the server replays the screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller.setActive(true)
                Lifecycle.Event.ON_RESUME -> controller.setWatching(true)
                Lifecycle.Event.ON_PAUSE -> {
                    controller.setWatching(false)
                    model.voiceInputManager.cancel()
                }
                Lifecycle.Event.ON_STOP -> controller.setActive(false)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The terminal socket doesn't refresh the server's idle timer.
    LaunchedEffect(Unit) {
        while (true) {
            delay(240_000)
            model.api.ping()
        }
    }

    // Only mention a slow connection, not the first moment of every open.
    var showLink by remember { mutableStateOf(false) }
    LaunchedEffect(controller.link) {
        if (controller.link == LinkState.LIVE) showLink = false else {
            model.voiceInputManager.cancel()
            delay(1200)
            showLink = true
        }
    }

    var showCopied by remember { mutableStateOf(false) }
    LaunchedEffect(controller.copies) {
        if (controller.copies == 0) return@LaunchedEffect
        showCopied = true
        delay(1200)
        showCopied = false
    }

    LaunchedEffect(controller.exited) {
        if (controller.exited) model.voiceInputManager.cancel()
    }

    Scaffold(
        topBar = {
            TopAppBar(
            title = {
                Column {
                    val termPath = termPaths[session.id] ?: session.cwd
                    Text(session.displayName(termPath), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        termPath,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.StartEllipsis
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = "Back")
                }
            },
            actions = {
                IconButton(
                    onClick = { pressVoice() },
                    enabled = controller.link == LinkState.LIVE && !controller.exited
                ) {
                    Icon(
                        painterResource(R.drawable.ic_mic),
                        contentDescription = "Voice input",
                        tint = if (voiceState != VoiceInputManager.State.IDLE) Color(0xFFC29BFF) else Color.White
                    )
                }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(painterResource(R.drawable.ic_paste), contentDescription = "Edit")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text("Paste") }, onClick = { showMenu = false; controller.paste() })
                        DropdownMenuItem(text = { Text("Select All") }, onClick = { showMenu = false; controller.selectAll() })
                        DropdownMenuItem(text = { Text("Copy Selection") }, onClick = { showMenu = false; controller.copySelection() })
                        DropdownMenuItem(text = { Text("Copy All Output") }, onClick = { showMenu = false; controller.copyAll() })
                    }
                }
                IconButton(onClick = { if (keyboardVisible) termView?.hideKeyboard() else termView?.showKeyboard() }) {
                    Icon(
                        painterResource(if (keyboardVisible) R.drawable.ic_keyboard_hide else R.drawable.ic_keyboard),
                        contentDescription = if (keyboardVisible) "Hide keyboard" else "Show keyboard"
                    )
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
            AndroidView(
                factory = { ctx ->
                    TermView(ctx, controller).also { view ->
                        view.onFontSizeChange = { size -> model.updatePrefs { it.termFontSize = size } }
                        termView = view
                        // The view can't take focus until it is in a window, so wait a moment before opening the keyboard.
                        view.postDelayed({ view.showKeyboard() }, 250)
                    }
                },
                update = { it.setFontSize(prefs.termFontSize) },
                modifier = Modifier.fillMaxSize()
            )

            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AnimatedVisibility(visible = showLink, enter = fadeIn(), exit = fadeOut()) {
                    Pill {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(if (controller.link == LinkState.RECONNECTING) "Reconnecting" else "Connecting", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                }
                AnimatedVisibility(visible = showCopied, enter = fadeIn(), exit = fadeOut()) {
                    Pill { Text("Copied", fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
                }
                if (voiceState != VoiceInputManager.State.IDLE) {
                    VoiceHud(voiceState, voiceLevel, onClick = { pressVoice() })
                }
            }

            if (controller.exited) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Exited" + (controller.exitCode?.let { " ($it)" } ?: ""), color = Color.White)
                        Spacer(modifier = Modifier.width(16.dp))
                        Button(onClick = onClose) { Text("Close") }
                    }
                }
            }
        }

        if (prefs.keybar != Prefs.KeybarMode.NEVER) {
            Spacer(modifier = Modifier.height(2.dp))
            KeyBarView(
                keys = prefs.keys,
                state = controller.keyState,
                onKey = { controller.press(it) }
            )
        }
        }
    }

    voiceError?.let { message -> VoiceErrorDialog(message) { model.voiceInputManager.clearError() } }
}

/** Runs an action once the app may use the microphone, asking for it first if needed. */
@Composable
fun rememberWithMicrophone(model: AppModel): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pending
        pending = null
        if (granted) action?.invoke()
        else model.voiceInputManager.reportError("Microphone access is off. Allow it in Settings to use voice input.")
    }
    return { action ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pending = action
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

@Composable
fun VoiceErrorDialog(message: String, onDismiss: () -> Unit) {
    AppDialog(
        title = "Voice input unavailable",
        text = message,
        onDismiss = onDismiss,
        dismissText = null,
        confirmText = "OK",
        onConfirm = onDismiss
    )
}

/** A small floating label over the terminal or editor. */
@Composable
fun Pill(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f), contentColor = Color.White, shape = RoundedCornerShape(50)) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) { content() }
    }
}

/** Shows that dictation is on: a mic between sound waves that follow the voice. Tapping it stops listening. */
@Composable
fun VoiceHud(state: VoiceInputManager.State, level: Float, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f),
        contentColor = Color.White,
        shape = RoundedCornerShape(50),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            VoiceWaves(state, level, mirrored = true)
            Icon(
                painterResource(R.drawable.ic_mic),
                contentDescription = null,
                tint = VoiceColor,
                modifier = Modifier.padding(horizontal = 6.dp).size(22.dp)
            )
            VoiceWaves(state, level, mirrored = false)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = when (state) {
                    VoiceInputManager.State.STARTING -> "Starting"
                    VoiceInputManager.State.LISTENING -> "Listening. Tap to stop"
                    VoiceInputManager.State.TRANSCRIBING -> "Transcribing"
                    VoiceInputManager.State.IDLE -> ""
                },
                fontSize = 14.sp
            )
        }
    }
}

private val VoiceColor = Color(0xFFC29BFF)

/**
 * Four bars beside the mic. While listening they rise and fall with the voice level;
 * while starting or transcribing they ripple gently so the app still looks busy.
 */
@Composable
private fun VoiceWaves(state: VoiceInputManager.State, level: Float, mirrored: Boolean) {
    val bars = 4
    // Quiet speech barely moves the raw level, so lift it before drawing.
    val loudness by animateFloatAsState(
        targetValue = if (state == VoiceInputManager.State.LISTENING) sqrt(level.coerceIn(0f, 1f)) else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "voice_loudness"
    )
    val phase by rememberInfiniteTransition(label = "voice_waves").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
        label = "voice_phase"
    )
    val listening = state == VoiceInputManager.State.LISTENING

    Canvas(modifier = Modifier.size(width = 26.dp, height = 22.dp)) {
        val barWidth = 3.dp.toPx()
        val gap = (size.width - bars * barWidth) / (bars - 1)
        val minHeight = barWidth
        for (i in 0 until bars) {
            // Bars nearest the mic are tallest, so the waves spread outward from it.
            val nearMic = if (mirrored) i else bars - 1 - i
            val weight = 0.55f + 0.45f * nearMic / (bars - 1)
            val wobble = 0.5f + 0.5f * sin(phase + i * 1.3f)
            val amount = if (listening) loudness * weight * (0.6f + 0.4f * wobble) else 0.25f * wobble
            val height = minHeight + (size.height - minHeight) * amount
            val x = i * (barWidth + gap)
            drawRoundRect(
                color = VoiceColor,
                topLeft = Offset(x, (size.height - height) / 2),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2)
            )
        }
    }
}
