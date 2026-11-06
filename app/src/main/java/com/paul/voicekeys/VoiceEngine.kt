@file:Suppress("MissingPermission")

package com.paul.voicekeys

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.sqrt

enum class SessionKind { CONTROL, PRACTICE, ENROLL, SOUND_ENROLL, SOUND_BACKGROUND }

interface VoiceListener {
    fun onReady(microphone: String)
    fun onLevel(db: Float)
    fun onKeyword(word: String, processingLagMs: Long)
    fun onEnrollment(word: String)
    fun onError(message: String)
    fun onTranscript(text: String, audioMs: Long, endpoint: Boolean) {}
    fun onDecision(decision: RecognitionDecision) {}
    fun onRecordingProgress(seconds: Float) {}
    fun onSoundAudio(pcm: ShortArray) {}
    fun onBackground(examples: List<SoundExample>, seconds: Float) {}
    fun onSoundDecision(decision: SoundDecision) {}
    fun onTrainingProgress(seconds: Float, total: Float) {}
}

/** A single serial worker owns all native model/stream lifetimes. No microphone service. */
class VoiceEngine(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var session: AtomicBoolean? = null
    private val recordLock = Any()
    private var recorder: AudioRecord? = null
    private var previousMode: Int? = null
    private var transcriber: OnlineRecognizer? = null
    private var transcriberBiased: Boolean? = null
    private var inputRecording: PracticeRecording? = null

    fun headsets(): List<AudioDeviceInfo> = audio.availableCommunicationDevices.filter {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
    }

    fun start(headset: AudioDeviceInfo, mappings: List<CommandMapping>, kind: SessionKind, listener: VoiceListener,
              commandBias: Boolean = true, recordPractice: Boolean = false,
              mode: RecognitionMode = RecognitionMode.SPEECH, sounds: TrainedSounds = TrainedSounds.defaults()) {
        check(session == null) { "Stop the current session first." }
        val alive = AtomicBoolean(true)
        session = alive
        val capture = if (recordPractice && kind == SessionKind.PRACTICE) PracticeRecording() else null
        inputRecording = capture
        try {
            previousMode = audio.mode
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            check(audio.setCommunicationDevice(headset)) { "Android could not select the headset microphone." }
        } catch (e: Exception) {
            stop()
            listener.onError(e.message ?: "Could not select the headset.")
            return
        }
        worker.execute {
            var stream: OnlineStream? = null
            var record: AudioRecord? = null
            try {
                if (!alive.get()) return@execute
                val soundMode = kind == SessionKind.SOUND_ENROLL || kind == SessionKind.SOUND_BACKGROUND ||
                    mode == RecognitionMode.TRAINED_SOUNDS && kind != SessionKind.ENROLL
                val biased = commandBias && kind != SessionKind.ENROLL
                if (soundMode || transcriberBiased != biased) {
                    transcriber?.release(); transcriber = null
                }
                val asr = if (soundMode) null else transcriber ?: OnlineRecognizer(context.assets, RecognitionConfig.create(biased))
                    .also { transcriber = it; transcriberBiased = biased }
                if (asr != null) stream = if (biased) asr.createStream(RecognitionConfig.hotwords(mappings)) else asr.createStream()
                var segmentSamples = 0L
                var segmentStartMs = 0L
                var previousText = ""
                val matcher = StreamingCommandMatcher(mappings, revisable = biased) { decision ->
                    if (kind == SessionKind.PRACTICE) {
                        val absolute = decision.copy(audioStartMs = decision.audioStartMs + segmentStartMs)
                        post(alive) { listener.onDecision(absolute) }
                    }
                }
                if (stream != null) check(stream.ptr != 0L) { "Could not initialize the command vocabulary." }
                val acoustic = if (soundMode) AcousticFeatures() else null
                val soundDetector = if (soundMode && (kind == SessionKind.CONTROL || kind == SessionKind.PRACTICE))
                    TrainedSoundDetector(sounds) { decision -> post(alive) { listener.onSoundDecision(decision) } } else null
                val trainingLength = when (kind) {
                    SessionKind.SOUND_ENROLL -> SAMPLE_RATE * 3
                    SessionKind.SOUND_BACKGROUND -> SAMPLE_RATE * 10
                    else -> 0
                }
                val training = if (trainingLength > 0) ShortArray(trainingLength) else null
                var trainingCount = 0
                if (!alive.get()) return@execute
                val deadline = SystemClock.elapsedRealtime() + 5000
                while (alive.get() && audio.communicationDevice?.id != headset.id) {
                    check(SystemClock.elapsedRealtime() < deadline) { "Headset microphone route timed out." }
                    Thread.sleep(20)
                }
                if (!alive.get()) return@execute
                val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0) { "16 kHz microphone capture is unavailable." }
                record = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(minimum * 2, SAMPLE_RATE * 2)).build()
                check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed." }
                val bluetoothInputs = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).filter {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                val preferredInput = bluetoothInputs.firstOrNull {
                    it.address.isNotBlank() && headset.address.isNotBlank() && it.address.equals(headset.address, true)
                } ?: bluetoothInputs.firstOrNull { it.productName.toString() == headset.productName.toString() }
                    ?: bluetoothInputs.singleOrNull()
                if (preferredInput != null) {
                    check(record.setPreferredDevice(preferredInput)) { "Android rejected the Bluetooth microphone selection." }
                }
                synchronized(recordLock) {
                    if (!alive.get()) return@execute
                    recorder = record
                    record.startRecording()
                }
                val buffer = ShortArray(320) // 20 ms; model chunks are handled internally.
                var sampleCount = 0L
                var ready = false
                var lastLevelAt = 0L
                val startedAt = SystemClock.elapsedRealtime()
                while (alive.get()) {
                    val count = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (!alive.get()) break
                    check(count > 0) { "Microphone capture failed ($count)." }
                    check(audio.communicationDevice?.id == headset.id) { "Headset disconnected or audio route changed." }
                    val routed = record.routedDevice
                    if (routed != null) {
                        check(isSelectedMicrophone(routed, headset)) {
                            "Microphone switched away from the selected Bluetooth headset."
                        }
                        if (!ready) {
                            ready = true
                            post(alive) { listener.onReady(routed.productName.toString()) }
                        }
                    }
                    check(ready || SystemClock.elapsedRealtime() - startedAt < 2000) {
                        "Android did not confirm the Bluetooth microphone input."
                    }
                    check(!ready || routed != null) { "Android no longer confirms the headset microphone input." }
                    // Do not decode unverified audio from the phone's built-in microphone.
                    if (!ready) continue
                    capture?.append(buffer, count)
                    val samples = FloatArray(count) { buffer[it] / 32768f }
                    sampleCount += count
                    check(SystemClock.elapsedRealtime() - startedAt - sampleCount * 1000 / SAMPLE_RATE < 1000) {
                        "Recognition is over a second behind live audio. Stop other demanding apps and try again."
                    }
                    if (soundMode) {
                        if (training != null) {
                            val n = minOf(count, training.size - trainingCount)
                            buffer.copyInto(training, trainingCount, 0, n)
                            trainingCount += n
                            if (trainingCount == training.size) {
                                if (kind == SessionKind.SOUND_ENROLL) post(alive) { listener.onSoundAudio(training) }
                                else {
                                    val examples = SoundEnrollment.background(training)
                                    post(alive) { listener.onBackground(examples, training.size / SAMPLE_RATE.toFloat()) }
                                }
                                break
                            }
                        } else acoustic!!.accept(buffer, count) { frame -> soundDetector?.accept(frame) }
                    } else if (asr != null && stream != null && kind != SessionKind.ENROLL) {
                        stream.acceptWaveform(samples, SAMPLE_RATE)
                        segmentSamples += count
                        while (alive.get() && asr.isReady(stream)) {
                            asr.decode(stream)
                            check(SystemClock.elapsedRealtime() - startedAt - sampleCount * 1000 / SAMPLE_RATE < 1000) {
                                "Recognition cannot keep up with live audio. Voice control stopped."
                            }
                            val result = asr.getResult(stream)
                            val endpoint = asr.isEndpoint(stream)
                            val clock = segmentSamples * 1000 / SAMPLE_RATE
                            if (kind == SessionKind.PRACTICE && (result.text != previousText || endpoint)) {
                                previousText = result.text
                                val audioMs = sampleCount * 1000 / SAMPLE_RATE
                                post(alive) { listener.onTranscript(result.text.trim(), audioMs, endpoint) }
                            }
                            matcher.accept(result.tokens.toList(), result.ysProbs, result.timestamps,
                                clock, endpoint).forEach { detection ->
                                post(alive) { listener.onKeyword(detection.word, detection.lookbackMs) }
                            }
                            if (endpoint || matcher.needsReset) {
                                asr.reset(stream)
                                matcher.reset()
                                segmentSamples = 0
                                segmentStartMs = sampleCount * 1000 / SAMPLE_RATE
                                previousText = ""
                            }
                        }
                    } else if (asr != null && stream != null) {
                        stream.acceptWaveform(samples, SAMPLE_RATE)
                        while (alive.get() && asr.isReady(stream)) asr.decode(stream)
                        // Enrollment is a short recording, transcribed once, then explicitly confirmed.
                        if (sampleCount >= SAMPLE_RATE * 3) {
                            stream.acceptWaveform(FloatArray(SAMPLE_RATE * 2), SAMPLE_RATE)
                            stream.inputFinished()
                            while (asr.isReady(stream)) asr.decode(stream)
                            val word = asr.getResult(stream).text.trim().lowercase()
                            post(alive) { listener.onEnrollment(word) }
                            break
                        }
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastLevelAt >= 100) {
                        val rms = sqrt(samples.sumOf { it.toDouble() * it } / count)
                        val db = (20 * log10(rms.coerceAtLeast(0.00001))).toFloat()
                        post(alive) { listener.onLevel(db) }
                        if (training != null) {
                            val seconds = trainingCount / SAMPLE_RATE.toFloat()
                            val total = trainingLength / SAMPLE_RATE.toFloat()
                            post(alive) { listener.onTrainingProgress(seconds, total) }
                        }
                        if (capture != null) {
                            val seconds = capture.sampleCount / SAMPLE_RATE.toFloat()
                            post(alive) { listener.onRecordingProgress(seconds) }
                        }
                        lastLevelAt = now
                    }
                }
            } catch (e: Exception) {
                post(alive) { listener.onError(e.message ?: "Audio recognition failed.") }
            } catch (e: LinkageError) {
                post(alive) { listener.onError("Recognition library unavailable: ${e.message}") }
            } finally {
                synchronized(recordLock) {
                    runCatching { record?.stop() }
                    record?.release()
                    if (recorder === record) recorder = null
                }
                stream?.release()
            }
        }
    }

    private fun isSelectedMicrophone(source: AudioDeviceInfo, sink: AudioDeviceInfo): Boolean {
        if (source.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO && source.type != AudioDeviceInfo.TYPE_BLE_HEADSET) return false
        if (source.address.isNotBlank() && sink.address.isNotBlank()) return source.address.equals(sink.address, true)
        // Some vendors expose a generic input name. The selected communication
        // sink determines its matching source; both the sink and Bluetooth input
        // type are verified continuously above.
        return audio.communicationDevice?.id == sink.id
    }
    private fun post(alive: AtomicBoolean, action: () -> Unit) { main.post { if (alive.get()) action() } }

    fun stop(): RecordedInput? {
        session?.set(false)
        session = null
        val captured = inputRecording?.finish()
        inputRecording = null
        synchronized(recordLock) { runCatching { recorder?.stop() } }
        if (previousMode != null) {
            runCatching {
                audio.clearCommunicationDevice()
                if (audio.mode == AudioManager.MODE_IN_COMMUNICATION) audio.mode = previousMode!!
            }
            previousMode = null
        }
        return captured
    }

    fun close() {
        stop()
        worker.execute { transcriber?.release(); transcriber = null }
        worker.shutdown()
    }

    companion object { const val SAMPLE_RATE = 16000 }
}
