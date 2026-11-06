package com.paul.voicekeys

import android.content.Context
import android.util.AtomicFile
import java.io.*

/** Atomic, bounded private feature storage. Enrollment PCM is not persisted. */
class SoundStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "trained-sounds.bin"))
    var loadError: String? = null; private set

    fun load(): TrainedSounds {
        loadError = null
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return TrainedSounds.defaults()
        return runCatching {
            DataInputStream(file.openRead().buffered()).use { input ->
                require(input.readInt() == 0x564b5331) { "Unknown sound-training format" }
                val profiles = List(input.boundedInt(4, 4)) {
                    val id = input.readUTF(); val label = input.readUTF()
                    require(id.length in 1..40 && label.length in 1..32)
                    val key = OutputKey.valueOf(input.readUTF())
                    val tolerance = input.readFloat().also { require(it.isFinite() && it in .65f..1.6f) }
                    SoundProfile(id, label, key, input.examples(TrainedSounds.MAX_EXAMPLES), tolerance)
                }
                require(profiles.map { it.id }.distinct().size == 4)
                val seconds = input.readFloat().also { require(it.isFinite() && it in 0f..600f) }
                TrainedSounds(profiles, input.examples(60), seconds)
            }
        }.getOrElse {
            loadError = "Saved sound training could not be read. Retrain the sounds. (${it.message})"
            TrainedSounds.defaults()
        }
    }

    fun save(model: TrainedSounds) {
        require(model.profiles.size == 4 && model.background.size <= 60)
        val stream = file.startWrite()
        try {
            val output = DataOutputStream(BufferedOutputStream(stream))
            output.writeInt(0x564b5331)
            output.writeInt(model.profiles.size)
            model.profiles.forEach { profile ->
                require(profile.label.trim().length in 1..32 && profile.tolerance in .65f..1.6f)
                output.writeUTF(profile.id); output.writeUTF(profile.label); output.writeUTF(profile.key.name)
                output.writeFloat(profile.tolerance); output.examples(profile.examples, TrainedSounds.MAX_EXAMPLES)
            }
            output.writeFloat(model.backgroundSeconds)
            output.examples(model.background, 60)
            output.flush()
            file.finishWrite(stream)
        } catch (e: Exception) { file.failWrite(stream); throw e }
    }

    private fun DataInputStream.boundedInt(min: Int, max: Int) = readInt().also { require(it in min..max) }
    private fun DataInputStream.examples(max: Int): List<SoundExample> = List(boundedInt(0, max)) {
        val db = readFloat().also { require(it.isFinite() && it in -120f..0f) }
        val frames = List(boundedInt(6, 120)) {
            FloatArray(AcousticFeatures.DIMENSIONS) { readFloat().also { require(it.isFinite() && it in -1.01f..1.01f) } }
        }
        SoundExample(frames, db)
    }
    private fun DataOutputStream.examples(examples: List<SoundExample>, max: Int) {
        require(examples.size <= max)
        writeInt(examples.size)
        examples.forEach { example ->
            require(example.frames.size in 6..120 && example.db.isFinite() && example.db in -120f..0f)
            writeFloat(example.db); writeInt(example.frames.size)
            example.frames.forEach { frame ->
                require(frame.size == AcousticFeatures.DIMENSIONS)
                frame.forEach { require(it.isFinite() && it in -1.01f..1.01f); writeFloat(it) }
            }
        }
    }
}
