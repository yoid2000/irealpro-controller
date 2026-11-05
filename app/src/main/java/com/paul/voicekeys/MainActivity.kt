@file:Suppress("MissingPermission")

package com.paul.voicekeys

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
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

data class DetectionEntry(val at: String, val word: String, val key: String, val lagMs: Long, val practice: Boolean)

class MainActivity : ComponentActivity() {
    private lateinit var store: MappingStore
    private lateinit var engine: VoiceEngine
    private lateinit var keyboard: BluetoothKeyboard
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = MappingStore(this)
        mappings = store.load()
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
        if (mappings.isEmpty() && kind != SessionKind.ENROLL) { error = "Add at least one command first."; return }
        error = null; ready = false; microphone = ""; levelDb = -100f
        sessionKind = kind
        status = if (kind == SessionKind.ENROLL) "Preparing microphone. Wait for the speaking prompt…" else "Preparing headset and recognition…"
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
            override fun onEnrollment(word: String) {
                stopSession()
                enrollmentWord = word
            }
            override fun onError(message: String) { stopSession(); error = message }
        })
    }
    fun stopSession(message: String = "Voice control is OFF.") {
        // Revoke transmission before releasing audio or processing pending main-thread callbacks.
        keyboard.disarm()
        sessionKind = null
        engine.stop()
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

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
    }
}
