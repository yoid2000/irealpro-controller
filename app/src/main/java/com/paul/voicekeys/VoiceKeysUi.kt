@file:Suppress("MissingPermission")

package com.paul.voicekeys

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

private val Green = Color(0xff136b5b)

@Composable
fun VoiceKeysApp(app: MainActivity) {
    var tab by remember { mutableIntStateOf(0) }
    var edit by remember { mutableStateOf<CommandMapping?>(null) }
    var keyPicker by remember { mutableStateOf<((OutputKey) -> Unit)?>(null) }
    var licenseText by remember { mutableStateOf<String?>(null) }
    MaterialTheme(colorScheme = lightColorScheme(primary = Green, secondary = Color(0xff47675e),
        background = Color(0xfff4f7f5), surface = Color.White)) {
        Scaffold(topBar = {
            Column(Modifier.statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text("VoiceKeys", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("OpenRun Pro → Android keyboard", style = MaterialTheme.typography.bodyMedium)
            }
        }, bottomBar = {
            NavigationBar {
                listOf("Control", "Commands", "Connections").forEachIndexed { index, title ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index },
                        icon = { Text(listOf("●", "≡", "↔")[index]) }, label = { Text(title) })
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (!app.permissionsGranted) {
                    Panel("Get started") {
                        Text("Allow microphone and Nearby devices access. Pair the OpenRun Pro with this phone and enable its phone-call audio.")
                        Button(onClick = app::requestPermissions) { Text("Grant permissions") }
                    }
                }
                when (tab) {
                    0 -> {
                        RecognitionModePicker(app)
                        Panel("Voice control") {
                            Text(app.status)
                            Button(onClick = {
                                if (app.sessionKind != null) app.stopSession() else app.startSession(SessionKind.CONTROL)
                            }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                                colors = ButtonDefaults.buttonColors(containerColor =
                                    if (app.sessionKind == SessionKind.CONTROL) Color(0xff9c302b) else Green)) {
                                Text(if (app.sessionKind != null) "STOP LISTENING" else "VOICE CONTROL OFF — TURN ON")
                            }
                            Text("Microphone: ${app.microphone.ifEmpty { "Not capturing" }}")
                            LinearProgressIndicator(progress = { ((app.levelDb + 65) / 55).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth())
                            Text("Keyboard: ${app.connectedHost ?: "Not connected"}")
                            Text("Leaving the app or locking the screen stops listening. Returning always requires turning it on again.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Panel("Practice with your violin") {
                            Text("Check for music-only false detections, then speak while playing. Practice sends no keys.")
                            if (app.recognitionMode == RecognitionMode.SPEECH) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(checked = app.commandBias, onCheckedChange = app::changeCommandBias,
                                    enabled = app.sessionKind == null)
                                Text("Favour command words", Modifier.padding(start = 8.dp))
                            }
                            Text(if (app.commandBias) "Command bias is on for both Practice and voice control."
                                else "Original recognition is selected for comparison.", style = MaterialTheme.typography.bodySmall)
                            } else Text("Trained sounds compare your acoustic examples directly. Train the four sounds and violin background under Commands.")
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(checked = app.recordNextPractice, onCheckedChange = app::changeRecordNextPractice,
                                    enabled = app.sessionKind == null)
                                Text("Record next Practice (30 seconds max)", Modifier.padding(start = 8.dp))
                            }
                            Text("Optional: keep the actual headset input in memory so you can listen for missing word beginnings. Stop Practice to play or export it. Nothing is uploaded.",
                                style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = {
                                if (app.sessionKind == SessionKind.PRACTICE) app.stopSession()
                                else { app.stopSession(); app.startSession(SessionKind.PRACTICE) }
                            }, enabled = app.sessionKind == null || app.sessionKind == SessionKind.PRACTICE) {
                                Text(if (app.sessionKind == SessionKind.PRACTICE) "Stop practice" else "Start practice")
                            }
                            Text("Since clear — detected: ${app.detectedCount}    Keys sent: ${app.sentCount}")
                            if (app.recordingActive) Text("Recorded: ${String.format(Locale.ROOT, "%.1f", app.recordedSeconds)} / 30 seconds. Practice continues after the recording limit.")
                            if (app.lastSent.isNotBlank()) Text("Last key sent: ${app.lastSent}")
                            Text("Each comma is spaced at least 200 ms apart. No command sequence is filled in automatically.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Panel(if (app.recognitionMode == RecognitionMode.TRAINED_SOUNDS) "Sound matches" else "What recognition heard") {
                            Text(app.heardText.ifBlank { "No detections yet. Start Practice and wait for the ready message." })
                            Text(if (app.recognitionMode == RecognitionMode.TRAINED_SOUNDS)
                                "No words are transcribed. Lower sound distance means a closer match; ambiguous matches and matches resembling violin are rejected. Ordinary nonmatches are silent."
                                else "This transcript may change. Word decisions below explain acceptance or rejection; a missing word may be absent from the transcript too.",
                                style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = app::clearPractice) { Text("Clear practice") }
                                TextButton(onClick = app::exportPractice, enabled = app.practiceEntries.isNotEmpty()) { Text("Export diagnostics") }
                            }
                            app.practiceEntries.filter { it.type == "word" }.take(15).forEach { entry ->
                                Text("${entry.at}  ${entry.text.ifBlank { "Recognition changed" }} — ${entry.outcome}",
                                    style = MaterialTheme.typography.bodyMedium)
                                if (entry.score != null) Text("Score ${String.format(Locale.ROOT, "%.3f", entry.score)}" +
                                    (entry.threshold?.let { "; selectivity ${String.format(Locale.ROOT, "%.2f", it)}" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall)
                                if (entry.distance != null) Text("Distance ${String.format(Locale.ROOT, "%.3f", entry.distance)}; limit ${String.format(Locale.ROOT, "%.3f", entry.distanceLimit)}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (app.recordedInput != null) Panel("Recorded headset input · Practice ${app.recordedSession}") {
                            Text("${String.format(Locale.ROOT, "%.1f", app.recordedSeconds)} seconds, 16 kHz mono. Kept until discarded, replaced, or this screen is recreated. Playback volume follows media volume.")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = app::playRecording, enabled = app.sessionKind == null) {
                                    Text(if (app.playingRecording) "Stop playback" else "Play")
                                }
                                TextButton(onClick = app::exportRecording, enabled = app.sessionKind == null) { Text("Export WAV") }
                                TextButton(onClick = app::discardRecording, enabled = app.sessionKind == null) { Text("Discard") }
                            }
                        }
                        Panel("Recent detections") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = app::clearLog) { Text("Clear") }
                                TextButton(onClick = app::export, enabled = app.detections.isNotEmpty()) { Text("Export CSV") }
                            }
                            if (app.detections.isEmpty()) Text("No commands detected yet.")
                            app.detections.take(20).forEach { entry ->
                                Text("${entry.at}  ${entry.word} → ${entry.key}${if (entry.practice) "  (practice)" else ""}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    1 -> {
                        RecognitionModePicker(app)
                        if (app.recognitionMode == RecognitionMode.TRAINED_SOUNDS) TrainedSoundCommands(app) else {
                        Text("Programmable commands", style = MaterialTheme.typography.titleLarge)
                        Text("Higher selectivity rejects more uncertain matches, but can miss quiet commands. Tune while playing the violin.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { app.stopSession(); edit = CommandMapping(word = "", key = OutputKey.COMMA) },
                                enabled = app.mappings.size < 9) { Text("Add word") }
                            OutlinedButton(onClick = { app.startSession(SessionKind.ENROLL) },
                                enabled = app.mappings.size < 9 && app.sessionKind == null) { Text("Speak new word") }
                        }
                        if (app.sessionKind == SessionKind.ENROLL) Panel("Teach a command") { Text(app.status) }
                        app.mappings.forEach { mapping ->
                            Panel("${mapping.word} → ${mapping.key.label}") {
                                Text("Selectivity: ${String.format(Locale.ROOT, "%.2f", mapping.threshold)}")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { app.stopSession(); edit = mapping }) { Text("Edit") }
                                    TextButton(onClick = { app.test(mapping.key) }, enabled = app.connectedHost != null) { Text("Test key") }
                                }
                            }
                        }
                        Text("Supported: up to nine English single-word commands. Speaking a word suggests its spelling; confirm or correct it before saving.",
                            style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    2 -> {
                        Panel("1 · Headset on device A") {
                            Text("Pair the Shokz OpenRun Pro in Android settings. Keep this headset connected to A while B receives keyboard input.")
                            OutlinedButton(onClick = app::refreshDevices) { Text("Refresh devices") }
                            if (app.headsets.isEmpty()) Text("No Bluetooth communication headset found.")
                            app.headsets.forEach { headset ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = headset.id == app.selectedHeadsetId,
                                        onClick = { app.selectHeadset(headset.id) })
                                    Text(headset.productName.toString())
                                }
                            }
                        }
                        Panel("2 · Keyboard on device B") {
                            Text("Bluetooth: ${if (app.bluetoothEnabled) "On" else "Off"}")
                            Text("Keyboard mode: ${if (app.bleKeyboard) "Bluetooth Low Energy (BLE)" else "Classic Bluetooth"}")
                            if (app.bleKeyboard) Text("A stays awake while this BLE keyboard is active. Keep VoiceKeys visible while pairing and controlling B.",
                                style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { app.switchKeyboardMode(!app.bleKeyboard) },
                                enabled = app.permissionsGranted && app.bluetoothEnabled) {
                                Text(if (app.bleKeyboard) "Use classic Bluetooth" else "Use BLE keyboard")
                            }
                            Text("Keyboard service: ${if (app.keyboardRegistered) "Registered" else "Not registered"}")
                            Text(app.keyboardSetupStatus)
                            if (!app.keyboardRegistered) {
                                OutlinedButton(onClick = app::retryKeyboardSetup,
                                    enabled = app.permissionsGranted && app.bluetoothEnabled) { Text("Retry keyboard setup") }
                            }
                            if (!app.bluetoothEnabled) Button(onClick = app::enableBluetooth) { Text("Enable Bluetooth") }
                            Text(if (app.bleKeyboard) "Keep this app visible. On B, open Bluetooth settings → Pair new device and select A's phone name. Accept pairing on both devices. BLE keyboard connections are initiated from B."
                                else "Keep this app visible on A. Make A discoverable, then pair from B's Bluetooth settings and enable its keyboard/input option. The advertised device name may remain A's phone name.")
                            OutlinedButton(onClick = app::makeDiscoverable, enabled = app.keyboardRegistered) {
                                Text(if (app.bleKeyboard) "Allow BLE pairing" else "Make A discoverable (5 min)")
                            }
                            Text("Paired devices — select device B:")
                            app.bonded.forEach { device ->
                                OutlinedButton(onClick = { app.connect(device) }, modifier = Modifier.fillMaxWidth(),
                                    enabled = app.permissionsGranted && app.bluetoothEnabled) {
                                    Text("${if (device.address == app.selectedHostAddress) "✓ " else ""}${device.name ?: "Unnamed device"}\n${device.address}")
                                }
                            }
                            if (app.selectedHostName.isNotBlank()) Text("Selected: ${app.selectedHostName}")
                            Text("Connected: ${app.connectedHost ?: "None"}")
                            Button(onClick = app::reconnect, enabled = app.permissionsGranted && app.bluetoothEnabled && app.selectedHostAddress.isNotBlank()) { Text("Connect selected B") }
                            OutlinedButton(onClick = { keyPicker = { app.test(it) } }, enabled = app.connectedHost != null) { Text("Send a test key…") }
                            Text("If keyboard input does not appear on B, close other keyboard apps on A, return here, and try forgetting/re-pairing A on B. Check B's physical keyboard layout if punctuation differs.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Panel("Offline by design") {
                            Text("Recognition stays on device A with no internet permission. Sound training stores acoustic features privately on A; enrollment audio is discarded after review. Optional Practice recordings last up to 30 seconds. Playback uses a temporary private cache file. Only explicit export saves audio elsewhere.")
                            TextButton(onClick = {
                                licenseText = listOf("NOTICE.txt", "Apache-2.0.txt", "ONNX-Runtime.txt").joinToString("\n\n") {
                                    app.assets.open("licenses/$it").bufferedReader().use { reader -> reader.readText() }
                                }
                            }) { Text("Open source licenses") }
                        }
                    }
                }
            }
        }
        app.enrollmentWord?.let { word ->
            LaunchedEffect(word) {
                edit = CommandMapping(word = word, key = OutputKey.COMMA)
                app.dismissEnrollment()
            }
        }
        app.soundAudio?.let { SoundCaptureDialog(app, it) }
        edit?.let { mapping ->
            MappingDialog(mapping, onDismiss = { edit = null },
                onSave = { app.saveMapping(it) { edit = null } },
                onDelete = if (app.mappings.any { it.id == mapping.id }) ({ app.deleteMapping(mapping.id); edit = null }) else null)
        }
        keyPicker?.let { chosen -> KeyDialog(onDismiss = { keyPicker = null }, onSelect = { chosen(it); keyPicker = null }) }
        app.error?.let { message ->
            AlertDialog(onDismissRequest = app::dismissError, title = { Text("Action needed") },
                text = { Text(message) }, confirmButton = { TextButton(onClick = app::dismissError) { Text("OK") } })
        }
        licenseText?.let { text ->
            AlertDialog(onDismissRequest = { licenseText = null }, title = { Text("Open source licenses") },
                text = { Text(text, Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall) },
                confirmButton = { TextButton(onClick = { licenseText = null }) { Text("Close") } })
        }
    }
}

@Composable
fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun MappingDialog(initial: CommandMapping, onDismiss: () -> Unit,
                          onSave: (CommandMapping) -> Unit, onDelete: (() -> Unit)?) {
    var word by remember(initial.id) { mutableStateOf(initial.word) }
    var key by remember(initial.id) { mutableStateOf(initial.key) }
    var threshold by remember(initial.id) { mutableFloatStateOf(initial.threshold) }
    var chooseKey by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Command mapping") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Confirm the spoken word, or type one. Its spelling defines what the model listens for.")
            OutlinedTextField(value = word, onValueChange = { word = it }, label = { Text("English command word") }, singleLine = true)
            OutlinedButton(onClick = { chooseKey = true }) { Text("Key: ${key.label}") }
            Text("Selectivity: ${String.format(Locale.ROOT, "%.2f", threshold)}")
            Slider(value = threshold, onValueChange = { threshold = it }, valueRange = 0.15f..0.9f)
            Text("Lower: easier to trigger. Higher: rejects more uncertain matches.", style = MaterialTheme.typography.bodySmall)
            if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete mapping", color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { onSave(initial.copy(word = word, key = key, threshold = threshold)) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
    if (chooseKey) KeyDialog(onDismiss = { chooseKey = false }, onSelect = { key = it; chooseKey = false })
}

@Composable
fun KeyDialog(onDismiss: () -> Unit, onSelect: (OutputKey) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Select output key") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            OutputKey.entries.forEach { key ->
                TextButton(onClick = { onSelect(key) }, modifier = Modifier.fillMaxWidth()) { Text(key.label) }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
