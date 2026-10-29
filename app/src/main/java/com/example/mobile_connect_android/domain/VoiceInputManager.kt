package com.example.mobile_connect_android.domain

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.mobile_connect_android.data.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class VoiceInputManager(
    private val context: Context,
    private val api: ApiClient
) {
    enum class Engine { LIVE, SERVER }
    enum class State { IDLE, STARTING, LISTENING, TRANSCRIBING }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    var engine by mutableStateOf(Engine.LIVE)
    var languageCode: String = ""

    private var speechRecognizer: SpeechRecognizer? = null
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null

    private var onTextCallback: ((String) -> Unit)? = null
    private var onFinishCallback: (() -> Unit)? = null

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val handler = Handler(Looper.getMainLooper())
    private var levelPollRunnable: Runnable? = null

    // Live dictation. Android's recognizer hears one sentence at a time, so a session chains segments together
    // until the user taps stop (or stays quiet for a while).

    /** Bumped whenever a session ends, so late callbacks from a dropped recognizer are ignored. */
    private var session = 0
    /** Text from the segments already finished in this session. */
    private var committed = ""
    /** The user tapped stop: the next result ends the session. */
    private var stopping = false
    /** Segments in a row that heard nothing. */
    private var quietSegments = 0
    /** Recognizer failures in a row; a few are retried with a fresh recognizer. */
    private var failures = 0
    /** Fires when the recognizer goes silent without calling back. */
    private val watchdog = Runnable { onWatchdog() }

    fun press(
        onText: (String) -> Unit,
        onFinish: () -> Unit = {}
    ) {
        when (_state.value) {
            State.LISTENING -> stopAndFinish()
            State.STARTING -> cancel()
            State.TRANSCRIBING -> if (engine == Engine.SERVER) cancel()
            State.IDLE -> start(onText, onFinish)
        }
    }

    private fun start(
        onText: (String) -> Unit,
        onFinish: () -> Unit
    ) {
        _errorMessage.value = null
        onTextCallback = onText
        onFinishCallback = onFinish

        if (engine == Engine.LIVE) {
            startLiveSpeech()
        } else {
            startServerRecording()
        }
    }

    private fun startLiveSpeech() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _errorMessage.value = "Speech recognition is not available on this device. Install or enable Google's Speech Recognition and Synthesis app, or switch the engine to Server Whisper in Settings."
            resetState()
            return
        }
        session++
        committed = ""
        stopping = false
        quietSegments = 0
        failures = 0
        _state.value = State.STARTING
        listenNextSegment(freshRecognizer = true)
    }

    private fun listenNextSegment(freshRecognizer: Boolean) {
        if (freshRecognizer) destroyRecognizer()
        val recognizer = speechRecognizer ?: try {
            SpeechRecognizer.createSpeechRecognizer(context).also {
                it.setRecognitionListener(Listener(session))
                speechRecognizer = it
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't create the recognizer", e)
            fail("Speech recognition couldn't start.")
            return
        }
        armWatchdog(START_TIMEOUT_MS)
        try {
            recognizer.startListening(recognizerIntent())
        } catch (e: Exception) {
            Log.w(TAG, "startListening failed", e)
            retryOrFail(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        // Recognizers expect a BCP 47 tag ("en-US"), not Java's "en_US".
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode.ifEmpty { Locale.getDefault().toLanguageTag() })
    }

    private inner class Listener(private val owner: Int) : RecognitionListener {
        private val current get() = owner == session && _state.value != State.IDLE

        override fun onReadyForSpeech(params: Bundle?) {
            if (!current) return
            Log.d(TAG, "ready")
            if (!stopping) _state.value = State.LISTENING
            armWatchdog(SEGMENT_TIMEOUT_MS)
        }

        override fun onBeginningOfSpeech() {
            if (!current) return
            quietSegments = 0
            armWatchdog(SEGMENT_TIMEOUT_MS)
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (!current) return
            _level.value = ((rmsdB + 2) / 10f).coerceIn(0f, 1f)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            if (!current) return
            _level.value = 0f
            armWatchdog(RESULT_TIMEOUT_MS)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!current) return
            val text = bestMatch(partialResults)
            if (text.isNotEmpty()) deliver(join(committed, text))
            armWatchdog(SEGMENT_TIMEOUT_MS)
        }

        override fun onResults(results: Bundle?) {
            if (!current) return
            val text = bestMatch(results)
            Log.d(TAG, "results: ${text.length} chars")
            failures = 0
            if (text.isNotEmpty()) {
                committed = join(committed, text)
                deliver(committed)
                quietSegments = 0
            } else {
                quietSegments++
            }
            segmentEnded()
        }

        override fun onError(error: Int) {
            if (!current) return
            Log.d(TAG, "error $error")
            when (error) {
                // Nothing was said in this segment.
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    quietSegments++
                    segmentEnded()
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> if (stopping) finish() else fail(errorText(error))
                else -> if (stopping) finish() else retryOrFail(error)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /** A segment finished: end the session if asked to (or after a long silence), otherwise keep listening. */
    private fun segmentEnded() {
        if (stopping || quietSegments >= MAX_QUIET_SEGMENTS) finish() else listenNextSegment(freshRecognizer = false)
    }

    /** Recognizers sometimes drop a session (busy, disconnected, a network blip): start over with a fresh one. */
    private fun retryOrFail(error: Int) {
        failures++
        if (failures > MAX_FAILURES) {
            fail(errorText(error))
            return
        }
        val owner = session
        disarmWatchdog()
        destroyRecognizer()
        handler.postDelayed({ if (owner == session && _state.value != State.IDLE) listenNextSegment(freshRecognizer = true) }, RETRY_DELAY_MS)
    }

    private fun onWatchdog() {
        if (engine != Engine.LIVE || _state.value == State.IDLE) return
        Log.d(TAG, "recognizer went quiet")
        if (stopping) finish() else retryOrFail(SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
    }

    private fun armWatchdog(delayMs: Long) {
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, delayMs)
    }

    private fun disarmWatchdog() = handler.removeCallbacks(watchdog)

    private fun deliver(text: String) {
        onTextCallback?.invoke(text)
    }

    /** Ends the session, keeping what was typed. */
    private fun finish() {
        val spoke = committed.isNotEmpty()
        val onFinish = onFinishCallback
        endLiveSession()
        if (spoke) onFinish?.invoke()
    }

    /** Ends the session with a message. What was already typed stays. */
    private fun fail(message: String) {
        endLiveSession()
        _errorMessage.value = message
    }

    private fun endLiveSession() {
        session++
        disarmWatchdog()
        destroyRecognizer()
        resetState()
    }

    private fun destroyRecognizer() {
        val recognizer = speechRecognizer ?: return
        speechRecognizer = null
        try {
            recognizer.cancel()
            recognizer.destroy()
        } catch (_: Exception) {}
    }

    private fun bestMatch(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()

    private fun join(before: String, text: String) = if (before.isEmpty()) text else "$before $text"

    private fun startServerRecording() {
        try {
            _state.value = State.STARTING
            val file = File(context.cacheDir, "speech_${System.currentTimeMillis()}.m4a")
            audioFile = file

            @Suppress("DEPRECATION")
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
            mediaRecorder = recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16_000)
                setAudioEncodingBitRate(64_000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            _state.value = State.LISTENING
            startLevelPolling()
        } catch (e: Exception) {
            Log.w(TAG, "Recording failed to start", e)
            cancel()
            _errorMessage.value = "The microphone couldn't be used. Another app may be recording."
        }
    }

    private fun startLevelPolling() {
        levelPollRunnable = object : Runnable {
            override fun run() {
                mediaRecorder?.let {
                    try {
                        val maxAmp = it.maxAmplitude
                        _level.value = (maxAmp / 32768f).coerceIn(0f, 1f)
                    } catch (_: Exception) {}
                    handler.postDelayed(this, 100)
                }
            }
        }
        handler.post(levelPollRunnable!!)
    }

    private fun stopLevelPolling() {
        levelPollRunnable?.let { handler.removeCallbacks(it) }
        levelPollRunnable = null
        _level.value = 0f
    }

    fun stopAndFinish() {
        if (engine == Engine.LIVE) {
            if (_state.value == State.IDLE || stopping) return
            stopping = true
            _state.value = State.TRANSCRIBING
            _level.value = 0f
            // If the recognizer never answers, end anyway with what was heard.
            armWatchdog(RESULT_TIMEOUT_MS)
            try {
                speechRecognizer?.stopListening() ?: finish()
            } catch (_: Exception) {
                finish()
            }
        } else {
            stopServerRecordingAndTranscribe()
        }
    }

    private fun stopServerRecordingAndTranscribe() {
        stopLevelPolling()
        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {
            // Stopping right after starting throws: there's no audio to send.
            audioFile?.delete()
            audioFile = null
        }
        mediaRecorder?.release()
        mediaRecorder = null

        val file = audioFile ?: run { resetState(); return }
        audioFile = null
        if (!file.exists() || file.length() == 0L) {
            file.delete()
            resetState()
            return
        }

        _state.value = State.TRANSCRIBING
        scope.launch {
            try {
                val text = api.transcribe(file.readBytes()).trim()
                // Cancelled while waiting: drop the result.
                if (_state.value != State.TRANSCRIBING) return@launch
                if (text.isNotEmpty()) {
                    onTextCallback?.invoke(text)
                    onFinishCallback?.invoke()
                }
                resetState()
            } catch (e: Exception) {
                if (_state.value == State.TRANSCRIBING) {
                    _errorMessage.value = e.message ?: "Transcription failed"
                    resetState()
                }
            } finally {
                file.delete()
            }
        }
    }

    fun cancel() {
        session++
        disarmWatchdog()
        stopLevelPolling()
        destroyRecognizer()

        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {}
        mediaRecorder?.release()
        mediaRecorder = null

        audioFile?.delete()
        audioFile = null

        resetState()
    }

    private fun resetState() {
        _state.value = State.IDLE
        _level.value = 0f
        stopping = false
        onTextCallback = null
        onFinishCallback = null
    }

    fun reportError(message: String) {
        _errorMessage.value = message
    }

    private fun errorText(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone access is off. Allow it in Settings to use voice input."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
            "Speech recognition couldn't reach its service. Check the connection and try again."
        SpeechRecognizer.ERROR_AUDIO -> "The microphone couldn't be used. Another app may be recording."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "Speech recognition doesn't support this language yet. Try again once the language pack has downloaded, or use Server Whisper."
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Speech recognition is being used too often. Wait a moment and try again."
        else -> "Speech recognition stopped unexpectedly. Try again."
    }

    fun clearError() {
        _errorMessage.value = null
    }

    private companion object {
        const val TAG = "VoiceInput"
        const val START_TIMEOUT_MS = 6_000L
        const val SEGMENT_TIMEOUT_MS = 15_000L
        const val RESULT_TIMEOUT_MS = 5_000L
        const val RETRY_DELAY_MS = 300L
        const val MAX_FAILURES = 2
        const val MAX_QUIET_SEGMENTS = 2
    }
}
