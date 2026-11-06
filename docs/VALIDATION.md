# Validation record

This record separates source/build checks from testing on the user's hardware.

## Automated checks

- Android debug APK builds for ARM64, targeting API 36 (Android 16).
- Unit tests cover pacing, report contents and streaming word
  matching, including continuous counting, repeated token occurrences, partial
  words, unknown/uncertain words, missing scores, revision handling and stopping.
- Android callback tests cover selecting B before registration completes, missing
  service/registration callbacks, timeout/retry recovery, bounded retries,
  automatic unregistration, late callbacks from earlier requests, and leaving or
  reopening the app while setup is pending. Hardware registration still requires
  confirmation on the Moto; these tests simulate Android's callbacks.
- Android lint passes with no errors. Remaining warnings concern available
  dependency updates and optional Kotlin convenience APIs.
- APK signature verification, 16 KB ZIP alignment and native ELF alignment pass.
- Packaged permissions contain microphone, audio settings, Bluetooth connection
  and discoverability access, plus AndroidX's app-private receiver permission.
  No internet, location, storage or background microphone permission is present.

## Offline acoustic integration tests

Test runtime: sherpa-onnx 2.0.1 on Windows, using the exact model shipped in the
APK. Synthetic input: Microsoft David Desktop, mono PCM16 at 16 kHz. Recognition
receives 20 ms blocks, with two seconds of trailing silence for final context.
The smoke-test matcher mirrors the Android matcher; the Kotlin matcher itself
is separately unit-tested.

| Input | Audio duration | Expected | Result |
| --- | --- | --- | --- |
| Silence | 5 seconds | No commands | Pass |
| one two three four, rate +3 | 1.365 seconds | Four words in order | Pass |
| one two three four, rate 0 | 1.945 seconds | Four words in order | Pass |
| one two three four start stop pause | 2.950 seconds | All seven words in order | Pass |

The 0.1.4 defaults used acoustic selectivity 0.50, except pause at 0.40. Scores are not
calibrated probabilities and these settings have not been tuned against violin.
The model uses 160 ms streaming chunks; that number is not measured latency.

One additional stress sample, “one one one one,” was transcribed by the selected
model as only two occurrences. The app preserves repeated occurrences actually
emitted by the model, but cannot recover words absent from its output without
inventing commands. Repeated identical spoken commands therefore remain a known
acoustic limitation to evaluate with the user's voice. No expected-sequence
completion has been added.

Reproduce synthetic inputs on Windows:

```powershell
.\scripts\generate_smoke_audio.ps1
python -m pip install -r scripts/requirements-test.txt
python scripts/recognition_smoke_test.py .downloads/smoke-audio/counting.wav --expect one two three four
python scripts/recognition_smoke_test.py .downloads/smoke-audio/counting-slower.wav --expect one two three four
python scripts/recognition_smoke_test.py .downloads/smoke-audio/commands.wav --expect one two three four start stop pause
```

## Moto USB diagnostics and BLE mode (2026-11-05)

The Moto g56 5G is running API 36, build
`W1VOS36.88-71-6-2-1-4`. ADB confirmed `BLUETOOTH_CONNECT`,
`BLUETOOTH_ADVERTISE` and `RECORD_AUDIO` are granted. A fresh VoiceKeys process
logs a profile-19 lookup followed by `getProfileProxy(),bluetooth service not
start`. The phone's Bluetooth service dump includes HidHostService but no
HidDeviceService. Inspection of that firmware's framework implementation ties
the message to a null profile-service binder. Thus the observed failure occurs
before registration; changing app permission grants does not solve it.

Version 0.1.2 adds an explicit BLE HID-over-GATT mode, retaining the classic mode
for other devices. BLE callback tests cover encrypted input access, bonding and
notification subscription, service publication, notification ordering, queued
key cancellation, long report-map reads, service failures and stalled reports.
They simulate GATT callbacks; publication and pairing on the actual phones need
separate confirmation.

Live follow-up on A confirmed all three BLE services (HID, Battery and Device
Information) are published and connectable advertising starts. B briefly bonded
and enabled both report and boot-keyboard notifications. Further attempts were
interrupted when A left the app or reached its 60-second screen timeout.
Version 0.1.4 is installed on A and keeps the visible BLE keyboard awake;
`dumpsys window` confirms `KEEP_SCREEN_ON` and the holding window is VoiceKeys.
Microphone/voice control still stops on app pause.

With A left visible in VoiceKeys 0.1.4, B (Samsung Tab S10 FE) connected and
enabled encrypted report and boot-keyboard notifications. The native trace
confirmed the 65-byte report map was read at offsets 0, 22 and 44. The user then
sent the Comma test key from A and confirmed a comma appeared in a text field on
B. This verifies actual keyboard input delivery for that key on the target
devices. Sustained voice-command operation and end-to-end voice latency remain
unverified. See the subsequent Practice observation below for acoustic results.

The 0.1.4 suite passed 36 tests. BLE reads are bounded by the negotiated ATT MTU,
including multi-part report-map reads; handshake tracing records attribute IDs,
lengths and notification status without recording audio or report contents.

## User Practice observation (2026-11-06)

The user reported no false detections from violin during a Practice session.
Spoken commands were usually detected, but some were missed, most often "four"
and "pause". The user suspects the microphone sometimes loses the start of
speech; this has not been confirmed from captured audio. Session duration,
command counts and whether misses occurred with or without simultaneous violin
were not recorded. This is an encouraging initial
observation of violin rejection, not a measured false-trigger rate or proof of
reliable command capture. The accepted-detection log does not distinguish
low-score rejection from words omitted or misrecognized by the decoder.

In a subsequent report, the user had all selectivity values at 0.20 except four
and pause at 0.15. Violin still caused no observed false detections. Pause was
missed most of the time, four about one third of the time, and stop about half
the time. These are approximate user observations, not a counted trial. They
motivate the 0.2.0 diagnostics and decoder changes; they do not establish whether
the headset suppresses speech onsets.

## Recognition changes in 0.2.0 (2026-11-06)

- Practice now displays partial transcripts, accepted/rejected whole-word
  decisions and acoustic scores. The separate diagnostics CSV retains up to
  1,000 rows with session IDs, decoder mode and audio offsets. Rejected final
  fragments are not treated as complete unknown words until a boundary or
  endpoint, so a partial PA can still become PAUSE.
- An optional next-Practice-only recording retains the first 30 seconds of
  verified headset input as mono PCM16 at 16 kHz. Unit tests check PCM fidelity,
  WAV headers, the recording cap and exclusion of writes after stop. Playback
  uses a disposable private cache file; saving a WAV requires explicit export.
- Modified beam search uses four active paths and modest command bias (0.5).
  Commands are tokenized in uppercase using a vocabulary derived from the
  checksum-verified model archive. Stronger bias caused lower-scoring paths on
  some synthetic samples and was not selected. A persistent switch allows
  comparison with the original greedy decoder.
- Beam matching allows revisions before a decision and requires 120 ms prefix
  stability except at an endpoint. A revision of committed output restarts the
  segment without replaying keys. Tests cover revisions, repeated occurrences,
  growing transcripts, rejection diagnostics and score recovery.
- The nonempty segment's silence allowance is 1.6 seconds, increased from 0.8.
  This governs decoder resets, not the timing of accepted word events. The
  shorter allowance truncated delayed output on the repeated-word stress clip.
- Existing selectivity settings are preserved. New mappings/installations use
  0.20, except the default four and pause mappings at 0.15. The scores remain
  uncalibrated and no personal acoustic-model training is performed.

Desktop checks with the same synthetic fixtures passed fast and slower counting,
all seven commands, and five seconds of silence. At 0.15 selectivity for one,
the updated decoder and longer endpoint allowance emit all four occurrences in
the repeated-one fixture. At default selectivity, low-scoring words can still be
rejected. These synthetic checks do not establish reliability or latency for
the user's pronunciation, headset or live violin.

The build includes an Android instrumentation check using the shipped ARM64
libraries, model, bias tokenizer and Kotlin matcher. Its fixtures exercise both
counting rates, all commands, repeated one at 0.15 and silence; it never opens
the microphone or sends Bluetooth keys.

On the Moto g56 5G / Android 16, all five fixture cases passed in the native
instrumentation test (approximately 18 seconds total). The 48 JVM unit tests and
Android lint also passed. Asset preparation, APK signature verification, 16 KB
ZIP alignment and native ELF alignment checks passed. Version 0.2.0 is installed
on A as an update, and inspection confirmed the stored mappings/selectivity
were retained. The temporary instrumentation app was removed afterward.

Live speech, violin false detections, recording playback/export and sustained
microphone processing performance of 0.2.0 still require user testing. Automated
recognition fixtures do not use the Bluetooth microphone or measure voice-to-key
latency on B. No live recognition improvement is claimed from these tests.

## Trained sounds and 200 ms spacing in 0.3.0 (2026-11-06)

The user clarified that only the comma sound (BAP) must work in rapid succession;
the other three sounds will be separated by at least a second. The minimum
comma key-down spacing is now 200 ms in the shared queue, retaining FIFO order
and the limit across cancellation and intervening non-comma keys.

The new default recognition mode uses normalized 13-dimensional MFCCs (25 ms
windows, 10 ms steps) and streaming subsequence DTW against personally recorded
examples. It does not initialize the speech recognizer. Enrollment offers a
three-second headset capture, audible preview and manual trim of 100–1,200 ms.
Only acoustic features are persisted in an atomic private file. Up to 12 examples
are retained for each of four editable sound/key slots. At least five per slot
are needed for control. Practice can test a single trained slot.

Violin background collection captures ten seconds and retains bounded examples
of 100, 200, 400 and 800 ms, so short BAPs are not compared only with long music
windows. New passages are merged with earlier examples. Both Practice and
control require a background model. Candidate matches must be within the
learned distance limit, separated from competing sounds and better than violin
examples. Acoustic onsets rearm the detector; a broad elapsed-time cooldown is
not used. Match tolerance is independent of speech-mode selectivity. This is
template enrollment, not neural-network fine-tuning or a calibrated probability.

Synthetic enrollment fixtures use Microsoft David Desktop at five rates and
volumes; query files use a separate rate/volume. Individual held-out sounds,
one-second-separated commands, rapid repeated BAP, and a fast mixed sequence
pass on the desktop. The trial GO was weak in the mixed-sequence comparison;
GOKE (rhyming with coke) was selected as a more distinctive starting candidate.
It remains editable, and the fast mixed sequence exceeds the user's stated
timing requirement for non-comma commands.

Tests also cover time-warped examples, continuous repeated occurrences, a held
sound generating one event, ambiguity rejection, rejection of taught violin-like
patterns, silence, PCM chunk-boundary independence, background duration coverage,
bounded retention across passages and persistence/corruption handling. The
synthetic harmonic/vibrato negative is a sanity check, not real violin audio.

On the Moto / Android 16, the new instrumentation test passed eight cases:
individual BAP, DEET, GOKE and SHOOM; one-second-separated commands; five repeated
BAPs; silence; and harmonic background. About 22.96 seconds of audio were
processed in 540 ms, including repeated detector setup. This is computational
throughput, not voice-to-key latency. The legacy native ASR regression also
passed. Version 0.3.0 is installed as an update; the build, all 68 unit tests, lint,
signature and Android 16 alignment checks pass.

Actual training with the user's voice, Shokz channel, violin rejection, fast BAP
capture, enrollment trimming/playback, and end-to-end key latency remain to be
validated by the user. No personal training is shipped and no real violin
reliability is claimed. Bluetooth service/transport behavior is unchanged except
for the requested 200 ms comma interval.

Reproduce the synthetic sound fixtures on Windows with
`scripts/generate_sound_test_audio.ps1`. Android instrumented tests neither
open the microphone, change the user's saved training nor send keyboard keys.

## Required device checks

These checks remain open unless confirmed in the device record above:

- Installation and UI behavior on the Moto g56 5G running Android 16.
- B discovers A's keyboard service, pairs, reconnects and receives exact keys.
- The OpenRun Pro microphone stays active simultaneously with A's HID connection.
- Violin-only playing produces an acceptably low measured false-trigger rate.
- Trained BAP mixed with violin preserves each occurrence up to five per second.
- Other trained sounds work with at least one second between commands.
- Individual words and deliberate repetitions work with the user's pronunciation.
- Sustained recognition keeps up with live capture without thermal/CPU backlog.
- End-to-end latency and timing jitter measured at B, not inferred from model timestamps.
- Home, Back, screen lock, app switch, force stop, Bluetooth loss and headset loss
  stop transmissions; returning/reconnecting never replays pending keys.
- Comma/period mapping matches B's keyboard layout and the target app's shortcuts.

Start with Practice mode and a text field on B before enabling control in the
target application. The README gives the pairing and calibration sequence.
