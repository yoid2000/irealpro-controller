package com.paul.voicekeys

import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.content.Context
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SoundStoreTest {
    @Test fun trainingMappingsToleranceAndBackgroundSurviveReload() {
        val context = RuntimeEnvironment.getApplication()
        val example = SoundExample(List(16) { FloatArray(13) { i -> if (i == 0) 1f else 0f } }, -30f)
        val original = TrainedSounds.defaults().copy(background = List(5) { example }, backgroundSeconds = 10f)
        val model = original.copy(profiles = original.profiles.map { it.copy(label = "${it.label}!", examples = List(5) { example }, tolerance = 1.2f) })
        SoundStore(context).save(model)
        val restored = SoundStore(context).load()
        assertEquals(model.profiles.map { it.label to it.key }, restored.profiles.map { it.label to it.key })
        assertEquals(5, restored.profiles.first().examples.size)
        assertEquals(1.2f, restored.profiles.first().tolerance, .001f)
        assertEquals(10f, restored.backgroundSeconds, .001f)
        assertArrayEquals(example.frames[0], restored.background[0].frames[0], 0f)
    }

    @Test fun corruptTrainingFailsClosedAndDoesNotEraseSpeechSettings() {
        val context = RuntimeEnvironment.getApplication()
        val speech = listOf(CommandMapping(word = "pause", key = OutputKey.PERIOD, threshold = .15f))
        MappingStore(context).save(speech)
        File(context.filesDir, "trained-sounds.bin").writeBytes(byteArrayOf(1, 2, 3))
        val store = SoundStore(context)
        assertNotNull(store.load().problem(true)); assertNotNull(store.loadError)
        assertEquals(speech, MappingStore(context).load())
    }

    @Test fun recognitionModeIsPersistedSeparatelyFromBluetoothAndSpeech() {
        val context = RuntimeEnvironment.getApplication()
        val store = MappingStore(context)
        store.useBleKeyboard = true
        store.recognitionMode = RecognitionMode.SPEECH
        assertEquals(RecognitionMode.SPEECH, MappingStore(context).recognitionMode)
        store.recognitionMode = RecognitionMode.TRAINED_SOUNDS
        assertTrue(MappingStore(context).useBleKeyboard)
    }
}
