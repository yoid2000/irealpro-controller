@file:Suppress("MissingPermission")

package com.paul.voicekeys

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File

data class DetectionEntry(val at: String, val word: String, val key: String, val lagMs: Long, val practice: Boolean)

class MainActivity : ComponentActivity() {
    private lateinit var store: MappingStore
    private lateinit var engine: VoiceEngine
    private lateinit var keyboard: BluetoothKeyboard
    private lateinit var soundStore: SoundStore
    var sounds by mutableStateOf(TrainedSounds.defaults()); private set
    var recognitionMode by mutableStateOf(RecognitionMode.TRAINED_SOUNDS); private set
    var soundAudio by mutableStateOf<ShortArray?>(null); private set
    var enrollingSoundId by mutableStateOf<String?>(null); private set
    var mappings by mutableStateOf<List<CommandMapping>>(emptyList()); private set
    var permissionsGranted by mutableStateOf(false); private set
    var bluetoothEnabled by mutableStateOf(false); private set
    var keyboardRegistered by mutableStateOf(false); private set
    var bleKeyboard by mutableStateOf(false); private set
    var keyboardSetupStatus by mutableStateOf("Waiting for Bluetooth setup."); private set
    var connectedHost by mutableStateOf<String?>(null); private set
    var headsets by mutableStateOf<List<AudioDeviceInfo>>(emptyList()); private set
    var selectedHeadsetId by mutableStateOf<Int?>(null); private set
    var bonded by mutableStateOf<List<BluetoothDevice>>(emptyList()); private set
    var selectedHostAddress by mutableStateOf(""); private set
    var selectedHostName by mutableStateOf(""); private set
    var status by mutableStateOf("Voice control is OFF."); private set
    var error by mutableStateOf<String?>(null); private set
    var sessionKind by mutableStateOf<SessionKind?>(null); private set
    var ready by mutableStateOf(false); private set
    var microphone by mutableStateOf(""); private set
    var levelDb by mutableFloatStateOf(-100f); private set
    var detections by mutableStateOf<List<DetectionEntry>>(emptyList()); private set
    var detectedCount by mutableIntStateOf(0); private set
    var sentCount by mutableIntStateOf(0); private set
    var lastSent by mutableStateOf(""); private set
    var enrollmentWord by mutableStateOf<String?>(null); private set
    var commandBias by mutableStateOf(true); private set
    var practiceEntries by mutableStateOf<List<PracticeEntry>>(emptyList()); private set
    var heardText by mutableStateOf(""); private set
    var recordNextPractice by mutableStateOf(false); private set
    var recordingActive by mutableStateOf(false); private set
    var recordedSeconds by mutableFloatStateOf(0f); private set
    var recordedInput by mutableStateOf<RecordedInput?>(null); private set
    var recordedSession by mutableIntStateOf(0); private set
    var playingRecording by mutableStateOf(false); private set
    private var practiceSession = 0
    private var player: MediaPlayer? = null
    private var resumed = false

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshDevices()
        if (permissionsGranted && resumed) keyboard.enterForeground(store.hostAddress)
        if (!permissionsGranted) error = "Microphone and Nearby devices permissions are required. You can grant them in Android app settings."
    }
    private val systemDialog = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshDevices()
        if (permissionsGranted && resumed) keyboard.enterForeground(store.hostAddress)
    }
    private val exportLog = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) runCatching {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { out ->
                out.write("time,command,key,decoder_lookback_ms,practice\n")
                detections.reversed().forEach {
                    val cells = listOf(it.at, it.word, it.key, it.lagMs.toString(), it.practice.toString())
                    out.write(cells.joinToString(",") { cell -> "\"${cell.replace("\"", "\"\"")}\"" } + "\n")
                }
            }
        }.onFailure { error = "Could not export the log: ${it.message}" }
    }
    private val exportPractice = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) runCatching {
            checkNotNull(contentResolver.openOutputStream(uri)).bufferedWriter().use { out ->
                out.write(practiceCsv(practiceEntries.reversed()))
            }
        }.onFailure { error = "Could not export Practice diagnostics: ${it.message}" }
    }
    private val exportRecording = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        if (uri != null) runCatching {
            val clip = checkNotNull(recordedInput) { "The recording is no longer available." }
            checkNotNull(contentResolver.openOutputStream(uri)).use { it.write(clip.wav) }
        }.onFailure { error = "Could not export the recording: ${it.message}" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = MappingStore(this)
        mappings = store.load()
        commandBias = store.commandBias
        recognitionMode = store.recognitionMode
        soundStore = SoundStore(this)
        sounds = soundStore.load()
        error = soundStore.loadError
        // Playback cache is disposable; recordings otherwise live only in memory.
        File(cacheDir, "practice-input.wav").delete()
        bleKeyboard = store.useBleKeyboard
        selectedHostAddress = store.hostAddress
        engine = VoiceEngine(this)
        keyboard = BluetoothKeyboard(this,
            onState = { registered, host, detail ->
                keyboardRegistered = registered
                keyboardSetupStatus = detail
                connectedHost = host
                if ((!registered || host == null) && sessionKind == SessionKind.CONTROL) {
                    stopSession("Keyboard disconnected. Voice control is OFF.")
                }
                updateKeepAwake()
            },
            onError = { message -> stopSession(); error = message },
            onSent = { key -> sentCount++; lastSent = key.label },
            preferBle = bleKeyboard,
            onHostSelected = { device ->
                store.hostAddress = device.address
                selectedHostAddress = device.address
                selectedHostName = device.name ?: device.address
            },
        )
        setContent { VoiceKeysApp(this) }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        refreshDevices()
        if (permissionsGranted) keyboard.enterForeground(store.hostAddress)
        updateKeepAwake()
    }
    override fun onPause() {
        resumed = false
        stopSession("Voice control is OFF. Enable it again when ready.")
        super.onPause()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && resumed && permissionsGranted) keyboard.enterForeground(store.hostAddress)
    }
    override fun onStop() {
        if (permissionsGranted) keyboard.leaveForeground()
        super.onStop()
    }
    override fun onDestroy() {
        engine.close()
        if (permissionsGranted) keyboard.close()
        super.onDestroy()
    }

    fun requestPermissions() { permissionRequest.launch(REQUIRED_PERMISSIONS) }
    fun dismissError() { error = null }
    fun dismissEnrollment() { enrollmentWord = null }
    fun reportError(message: String) { error = message }
    fun refreshDevices() {
        permissionsGranted = REQUIRED_PERMISSIONS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (!permissionsGranted) return
        runCatching {
            bluetoothEnabled = keyboard.adapter?.isEnabled == true
            headsets = engine.headsets()
            if (headsets.none { it.id == selectedHeadsetId }) {
                selectedHeadsetId = headsets.firstOrNull { it.productName.toString() == store.headsetName }?.id
                    ?: headsets.firstOrNull { it.productName.toString().contains("openrun", true) }?.id
                    ?: headsets.firstOrNull()?.id
            }
            bonded = keyboard.bondedDevices()
            selectedHostName = bonded.firstOrNull { it.address == selectedHostAddress }?.let { it.name ?: it.address } ?: ""
        }.onFailure { error = "Could not read Bluetooth devices: ${it.message}" }
    }
    fun selectHeadset(id: Int) {
        stopSession()
        selectedHeadsetId = id
        store.headsetName = headsets.firstOrNull { it.id == id }?.productName?.toString() ?: ""
    }
    fun connect(device: BluetoothDevice) {
        stopSession()
        store.hostAddress = device.address
        selectedHostAddress = device.address
        selectedHostName = device.name ?: device.address
        keyboard.selectAndConnect(device)
    }
    fun reconnect() {
        val device = bonded.firstOrNull { it.address == selectedHostAddress }
        if (device == null) error = "Select device B from the paired devices list." else connect(device)
    }
    fun retryKeyboardSetup() {
        stopSession()
        error = null
        if (!permissionsGranted) { requestPermissions(); return }
        keyboard.retryRegistration()
    }
    fun switchKeyboardMode(useBle: Boolean) {
        stopSession()
        error = null
        if (!permissionsGranted) { requestPermissions(); return }
        bleKeyboard = useBle
        store.useBleKeyboard = useBle
        if (useBle) keyboard.useBleKeyboard() else keyboard.useClassicKeyboard()
    }
    fun enableBluetooth() {
        if (!permissionsGranted) { requestPermissions(); return }
        systemDialog.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }
    fun makeDiscoverable() {
        stopSession()
        if (!keyboardRegistered) { error = "Wait for keyboard registration before pairing."; return }
        if (bleKeyboard) { keyboard.advertiseBleKeyboard(); return }
        systemDialog.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300))
    }
    fun startSession(kind: SessionKind) {
        if (!resumed) return
        if (sessionKind != null) { stopSession(); return }
        refreshDevices()
        if (!permissionsGranted) { requestPermissions(); return }
        if (kind == SessionKind.CONTROL && !keyboard.connected) { error = "Connect device B first."; return }
        val headset = headsets.firstOrNull { it.id == selectedHeadsetId }
        if (headset == null) { error = "Connect the OpenRun Pro to device A, enable its phone-call audio, then refresh devices."; return }
        if (kind == SessionKind.CONTROL || kind == SessionKind.PRACTICE) {
            if (recognitionMode == RecognitionMode.TRAINED_SOUNDS) {
                sounds.problem(kind == SessionKind.CONTROL)?.let { error = it; return }
            } else if (mappings.isEmpty()) { error = "Add at least one command first."; return }
        }
        stopPlayback()
        val recordThisSession = kind == SessionKind.PRACTICE && recordNextPractice
        if (kind == SessionKind.PRACTICE) { practiceSession++; heardText = "" }
        val currentPracticeSession = practiceSession
        val decoderName = if (recognitionMode == RecognitionMode.TRAINED_SOUNDS) "trained-sounds"
            else if (commandBias) "command-biased" else "original"
        recordingActive = recordThisSession
        if (recordThisSession) {
            recordedInput = null; recordedSeconds = 0f; recordedSession = currentPracticeSession
            recordNextPractice = false
        }
        error = null; ready = false; microphone = ""; levelDb = -100f
        sessionKind = kind
        status = "Preparing headset. Wait for the ready message…"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (kind == SessionKind.CONTROL) keyboard.arm() else keyboard.disarm()
        engine.start(headset, mappings, kind, object : VoiceListener {
            override fun onReady(microphone: String) {
                this@MainActivity.microphone = microphone
                ready = true
                status = when (kind) {
                    SessionKind.CONTROL -> "Listening — commands send keys to device B."
                    SessionKind.PRACTICE -> "Practice — detections shown below; no keys sent."
                    SessionKind.ENROLL -> "Speak ONE command now. Recording for 3 seconds…"
                    SessionKind.SOUND_ENROLL -> "Get ready to make ${sounds.profiles.firstOrNull { it.id == enrollingSoundId }?.label ?: "your sound"} once…"
                    SessionKind.SOUND_BACKGROUND -> "Play violin without speaking. Recording 10 seconds…"
                }
            }
            override fun onLevel(db: Float) { levelDb = db }
            override fun onKeyword(word: String, processingLagMs: Long) {
                if (sessionKind != kind || !resumed) return
                val mapping = mappings.firstOrNull { it.word == word } ?: return
                detectedCount++
                val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date())
                detections = (listOf(DetectionEntry(time, word, mapping.key.label, processingLagMs,
                    kind == SessionKind.PRACTICE)) + detections).take(500)
                if (kind == SessionKind.CONTROL) keyboard.enqueue(mapping.key)
            }
            override fun onSoundDecision(decision: SoundDecision) {
                if (sessionKind != kind || !resumed) return
                val profile = sounds.profiles.firstOrNull { it.id == decision.id } ?: return
                if (kind == SessionKind.PRACTICE) {
                    heardText = "${decision.label}: ${decision.reason}"
                    addPracticeEntry(PracticeEntry(logTime(), currentPracticeSession, decoderName, decision.audioMs,
                        "word", decision.label, decision.reason, lookbackMs = decision.lookbackMs,
                        distance = decision.distance, distanceLimit = decision.limit,
                        competingDistance = decision.competingDistance, backgroundDistance = decision.backgroundDistance))
                }
                if (decision.accepted) {
                    detectedCount++
                    detections = (listOf(DetectionEntry(logTime(), profile.label, profile.key.label,
                        decision.lookbackMs, kind == SessionKind.PRACTICE)) + detections).take(500)
                    if (kind == SessionKind.CONTROL) keyboard.enqueue(profile.key)
                }
            }
            override fun onSoundAudio(pcm: ShortArray) { stopSession(); soundAudio = pcm }
            override fun onBackground(examples: List<SoundExample>, seconds: Float) {
                stopSession()
                saveSounds(sounds.copy(background = SoundEnrollment.mergeBackground(sounds.background, examples),
                    backgroundSeconds = (sounds.backgroundSeconds + seconds).coerceAtMost(600f)))
                status = "Violin background saved. Train each sound, then test in Practice."
            }
            override fun onTrainingProgress(seconds: Float, total: Float) {
                status = if (kind == SessionKind.SOUND_ENROLL) {
                    val label = sounds.profiles.firstOrNull { it.id == enrollingSoundId }?.label.orEmpty()
                    if (seconds < .5f) "Get ready…" else "Make $label ONCE now — ${String.format(Locale.ROOT, "%.1f", total - seconds)} s remaining"
                } else "Play violin without speaking — ${String.format(Locale.ROOT, "%.1f", total - seconds)} s remaining"
            }
            override fun onEnrollment(word: String) {
                stopSession()
                enrollmentWord = word
            }
            override fun onTranscript(text: String, audioMs: Long, endpoint: Boolean) {
                if (sessionKind != SessionKind.PRACTICE) return
                heardText = text
                addPracticeEntry(PracticeEntry(logTime(), currentPracticeSession, decoderName, audioMs,
                    "transcript", text, if (endpoint) "Segment ended" else "Partial; may change"))
            }
            override fun onDecision(decision: RecognitionDecision) {
                if (sessionKind != SessionKind.PRACTICE) return
                addPracticeEntry(PracticeEntry(logTime(), currentPracticeSession, decoderName,
                    decision.audioStartMs, "word", decision.word, decision.reason.label,
                    decision.score, decision.threshold, decision.lookbackMs))
            }
            override fun onRecordingProgress(seconds: Float) { recordedSeconds = seconds }
            override fun onError(message: String) { stopSession(); error = message }
        }, commandBias = commandBias, recordPractice = recordThisSession, mode = recognitionMode, sounds = sounds)
    }
    fun stopSession(message: String = "Voice control is OFF.") {
        // Revoke transmission before releasing audio or processing pending main-thread callbacks.
        keyboard.disarm()
        sessionKind = null
        engine.stop()?.let { recordedInput = it; recordedSeconds = it.seconds }
        recordingActive = false
        stopPlayback()
        ready = false; levelDb = -100f; status = message
        updateKeepAwake()
    }
    private fun updateKeepAwake() {
        if (resumed && (sessionKind != null || bleKeyboard && keyboardRegistered)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    fun test(key: OutputKey) { stopSession(); keyboard.test(key) }
    fun saveMapping(mapping: CommandMapping, done: () -> Unit) {
        stopSession()
        val word = try { validateWord(mapping.word) } catch (e: IllegalArgumentException) {
            error = e.message; return
        }
        if (mappings.any { it.word == word && it.id != mapping.id }) { error = "That word already has a mapping."; return }
        val candidate = if (mappings.any { it.id == mapping.id }) mappings.map {
            if (it.id == mapping.id) mapping.copy(word = word) else it
        } else mappings + mapping.copy(word = word)
        if (candidate.size > 9) { error = "A maximum of nine commands is supported."; return }
        mappings = candidate
        store.save(candidate)
        done()
    }
    fun deleteMapping(id: String) { stopSession(); mappings = mappings.filterNot { it.id == id }; store.save(mappings) }
    fun clearLog() { detections = emptyList(); detectedCount = 0; sentCount = 0; lastSent = "" }
    fun export() { stopSession(); exportLog.launch("voicekeys-detections.csv") }
    private fun logTime() = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date())
    private fun addPracticeEntry(entry: PracticeEntry) { practiceEntries = (listOf(entry) + practiceEntries).take(1000) }
    fun clearPractice() { stopSession(); practiceEntries = emptyList(); heardText = ""; clearLog() }
    fun exportPractice() { stopSession(); exportPractice.launch("voicekeys-practice.csv") }
    fun changeCommandBias(enabled: Boolean) {
        stopSession(); commandBias = enabled; store.commandBias = enabled
    }
    fun changeRecordNextPractice(enabled: Boolean) { recordNextPractice = enabled }
    fun changeRecognitionMode(mode: RecognitionMode) {
        stopSession(); recognitionMode = mode; store.recognitionMode = mode; heardText = ""
    }
    private fun saveSounds(model: TrainedSounds): Boolean = runCatching {
        soundStore.save(model); sounds = model; true
    }.getOrElse { error = "Could not save training: ${it.message}"; false }
    fun editSound(id: String, label: String, key: OutputKey, tolerance: Float) {
        stopSession()
        val name = label.trim()
        if (name.length !in 1..32) { error = "Give the sound a label of 1–32 characters."; return }
        if (sounds.profiles.any { it.id != id && it.label.equals(name, true) }) { error = "Choose a different label for each sound."; return }
        saveSounds(sounds.copy(profiles = sounds.profiles.map {
            if (it.id == id) it.copy(label = name, key = key, tolerance = tolerance.coerceIn(.65f, 1.6f)) else it
        }))
    }
    fun trainSound(id: String) {
        stopSession()
        val profile = sounds.profiles.first { it.id == id }
        if (profile.examples.size >= TrainedSounds.MAX_EXAMPLES) { error = "Twelve examples are saved. Remove the last example before recording another."; return }
        enrollingSoundId = id; soundAudio = null
        startSession(SessionKind.SOUND_ENROLL)
    }
    fun cancelSoundExample() { stopPlayback(); soundAudio = null; enrollingSoundId = null }
    fun acceptSoundExample(startMs: Float, endMs: Float) {
        val pcm = soundAudio ?: return
        val id = enrollingSoundId ?: return
        runCatching {
            val example = SoundEnrollment.example(selectedSound(pcm, startMs, endMs))
            val updated = sounds.copy(profiles = sounds.profiles.map {
                if (it.id == id) it.copy(examples = (it.examples + example).takeLast(TrainedSounds.MAX_EXAMPLES)) else it
            })
            if (saveSounds(updated)) { cancelSoundExample(); status = "Sound example saved. Add more examples, then test in Practice." }
        }.onFailure { error = it.message }
    }
    fun removeSoundExample(id: String, all: Boolean = false) {
        stopSession()
        saveSounds(sounds.copy(profiles = sounds.profiles.map {
            if (it.id == id) it.copy(examples = if (all) emptyList() else it.examples.dropLast(1)) else it
        }))
    }
    fun clearBackground() { stopSession(); saveSounds(sounds.copy(background = emptyList(), backgroundSeconds = 0f)) }
    private fun selectedSound(pcm: ShortArray, startMs: Float, endMs: Float): ShortArray =
        pcm.copyOfRange((startMs * 16).toInt().coerceIn(0, pcm.size), (endMs * 16).toInt().coerceIn(0, pcm.size))
    fun playSoundExample(startMs: Float, endMs: Float) {
        if (playingRecording) { stopPlayback(); return }
        val pcm = soundAudio ?: return
        val selection = selectedSound(pcm, startMs, endMs)
        val recording = PracticeRecording(selection.size)
        recording.append(selection, selection.size)
        recording.finish()?.let { playAudio(it) }
    }
    fun exportRecording() {
        stopSession()
        if (recordedInput != null) exportRecording.launch("voicekeys-practice-$recordedSession.wav")
    }
    fun discardRecording() {
        stopPlayback(); recordedInput = null; recordedSeconds = 0f
    }
    fun playRecording() {
        if (playingRecording) { stopPlayback(); return }
        stopSession()
        val clip = recordedInput ?: return
        playAudio(clip)
    }
    private fun playAudio(clip: RecordedInput) {
        stopPlayback()
        runCatching {
            val file = File(cacheDir, "practice-input.wav")
            file.writeBytes(clip.wav)
            val playback = MediaPlayer()
            player = playback
            playback.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            playback.setDataSource(file.absolutePath)
            playback.setOnCompletionListener { stopPlayback() }
            playback.setOnErrorListener { _, _, _ -> stopPlayback(); error = "Could not play the recording."; true }
            playback.prepare()
            playback.start()
            playingRecording = true
        }.onFailure { stopPlayback(); error = "Could not play the recording: ${it.message}" }
    }
    private fun stopPlayback() {
        player?.release(); player = null; playingRecording = false
        File(cacheDir, "practice-input.wav").delete()
    }

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
    }
}
