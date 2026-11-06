package com.paul.voicekeys

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs

@Composable
fun RecognitionModePicker(app: MainActivity) {
    Panel("Recognition mode") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = app.recognitionMode == RecognitionMode.TRAINED_SOUNDS,
                onClick = { app.changeRecognitionMode(RecognitionMode.TRAINED_SOUNDS) }, enabled = app.sessionKind == null)
            Text("Trained sounds")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = app.recognitionMode == RecognitionMode.SPEECH,
                onClick = { app.changeRecognitionMode(RecognitionMode.SPEECH) }, enabled = app.sessionKind == null)
            Text("Speech recognition (previous mode)")
        }
    }
}

@Composable
fun TrainedSoundCommands(app: MainActivity) {
    var editing by remember { mutableStateOf<SoundProfile?>(null) }
    Text("Teach four sounds", style = MaterialTheme.typography.titleLarge)
    Text("Labels are names, not spellings to recognize. Try BAP (cap), DEET (feet), GOKE (coke, with a G) and SHOOM (boom), or choose your own. Start with at least five examples of each, without violin. Aim for 8–12 varied examples, including short, quick versions.")
    if (app.sessionKind != null) Panel("Microphone session") {
        Text(app.status)
        Button(onClick = { app.stopSession() }) { Text("Stop listening") }
    }
    app.sounds.profiles.forEach { profile ->
        Panel("${profile.label} → ${profile.key.label}") {
            Text("${profile.examples.size} / ${TrainedSounds.MAX_EXAMPLES} examples saved · " +
                if (profile.examples.size >= TrainedSounds.MIN_EXAMPLES) "Ready for Practice" else "Need at least ${TrainedSounds.MIN_EXAMPLES}")
            Button(onClick = { app.trainSound(profile.id) }, enabled = app.sessionKind == null,
                modifier = Modifier.fillMaxWidth()) { Text("Record one example") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { editing = profile }, enabled = app.sessionKind == null) { Text("Edit") }
                TextButton(onClick = { app.removeSoundExample(profile.id) }, enabled = app.sessionKind == null && profile.examples.isNotEmpty()) { Text("Remove last") }
                TextButton(onClick = { app.test(profile.key) }, enabled = app.connectedHost != null && app.sessionKind == null) { Text("Test key") }
            }
        }
    }
    Panel("Violin-only background") {
        Text("${app.sounds.backgroundSeconds.toInt()} seconds sampled · ${app.sounds.background.size} background examples saved. Record at least 10 seconds while playing without speaking. Include bow changes and different registers; add another passage if needed.")
        Button(onClick = { app.startSession(SessionKind.SOUND_BACKGROUND) }, enabled = app.sessionKind == null) { Text("Record violin · 10 seconds") }
        TextButton(onClick = app::clearBackground, enabled = app.sessionKind == null && app.sounds.background.isNotEmpty()) { Text("Clear background") }
    }
    Text("Next: Control → Start practice. Test fresh examples with violin, then rapid repeated sounds. A held sound should count once. Training alone does not establish reliability.")
    editing?.let { profile ->
        SoundEditDialog(profile, onDismiss = { editing = null }, onSave = { label, key, tolerance ->
            app.editSound(profile.id, label, key, tolerance); editing = null
        }, onClear = { app.removeSoundExample(profile.id, all = true); editing = null })
    }
}

@Composable
private fun SoundEditDialog(profile: SoundProfile, onDismiss: () -> Unit,
    onSave: (String, OutputKey, Float) -> Unit, onClear: () -> Unit) {
    var label by remember { mutableStateOf(profile.label) }
    var key by remember { mutableStateOf(profile.key) }
    var tolerance by remember { mutableFloatStateOf(profile.tolerance) }
    var pickKey by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Trained sound") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Sound label") }, singleLine = true)
            Text("Renaming changes only the label. Clear the training if you want to use a different utterance.")
            OutlinedButton(onClick = { pickKey = true }) { Text("Key: ${key.label}") }
            Text("Match tolerance: ${String.format(Locale.ROOT, "%.2f", tolerance)}×")
            Slider(value = tolerance, onValueChange = { tolerance = it }, valueRange = .65f..1.6f)
            Text("Higher tolerance accepts more variation; lower tolerance is stricter. This is separate from speech selectivity. Start at 1.00×.")
            TextButton(onClick = onClear, enabled = profile.examples.isNotEmpty()) { Text("Clear this sound's training", color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { onSave(label, key, tolerance) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
    if (pickKey) KeyDialog(onDismiss = { pickKey = false }, onSelect = { key = it; pickKey = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundCaptureDialog(app: MainActivity, pcm: ShortArray) {
    var selection by remember(pcm) { mutableStateOf(SoundEnrollment.suggestedRange(pcm)) }
    val total = pcm.size / 16f
    val duration = selection.endInclusive - selection.start
    val bars = remember(pcm) { FloatArray(180) { index ->
        val start = index * pcm.size / 180; val end = (index + 1) * pcm.size / 180
        (start until end).maxOfOrNull { abs(pcm[it].toInt()) }?.div(32768f) ?: 0f
    } }
    AlertDialog(onDismissRequest = app::cancelSoundExample, title = { Text("Review sound example") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Keep the whole utterance, including its beginning and ending. Trim surrounding silence or violin; record again if the voice is obscured.")
            Canvas(Modifier.fillMaxWidth().height(90.dp)) {
                drawRect(Color(0xffe0e8e4))
                drawRect(Color(0xffa5d4bf), Offset(size.width * selection.start / total, 0f),
                    Size(size.width * duration / total, size.height))
                bars.forEachIndexed { index, peak ->
                    val x = size.width * index / bars.size
                    val height = (peak * 3).coerceAtMost(1f) * size.height / 2
                    drawLine(Color(0xff136b5b), Offset(x, size.height / 2 - height), Offset(x, size.height / 2 + height), 2f)
                }
            }
            RangeSlider(value = selection, onValueChange = { selection = it }, valueRange = 0f..total)
            Text("${String.format(Locale.ROOT, "%.2f", selection.start / 1000)}–${String.format(Locale.ROOT, "%.2f", selection.endInclusive / 1000)} s · ${duration.toInt()} ms selected")
            Text("Select 100–1,200 ms. Saved training contains acoustic features, not a recording.")
            OutlinedButton(onClick = { app.playSoundExample(selection.start, selection.endInclusive) }) {
                Text(if (app.playingRecording) "Stop playback" else "Play selection")
            }
        }
    }, confirmButton = { TextButton(onClick = { app.acceptSoundExample(selection.start, selection.endInclusive) },
        enabled = duration in 100f..1200f) { Text("Use example") } },
        dismissButton = { TextButton(onClick = app::cancelSoundExample) { Text("Discard") } })
}
