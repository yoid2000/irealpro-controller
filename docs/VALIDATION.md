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

Defaults use acoustic selectivity 0.50, except pause at 0.40. Scores are not
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
devices. Voice recognition mixed with violin, sustained voice-command operation
and end-to-end voice latency remain unverified.

The suite now passes 36 tests. BLE reads are bounded by the negotiated ATT MTU,
including multi-part report-map reads; handshake tracing records attribute IDs,
lengths and notification status without recording audio or report contents.

## Required device checks

These checks remain open unless confirmed in the device record above:

- Installation and UI behavior on the Moto g56 5G running Android 16.
- B discovers A's keyboard service, pairs, reconnects and receives exact keys.
- The OpenRun Pro microphone stays active simultaneously with A's HID connection.
- Violin-only playing produces an acceptably low measured false-trigger rate.
- Speech mixed with violin preserves each counting word up to four per second.
- Individual words and deliberate repetitions work with the user's pronunciation.
- Sustained recognition keeps up with live capture without thermal/CPU backlog.
- End-to-end latency and timing jitter measured at B, not inferred from model timestamps.
- Home, Back, screen lock, app switch, force stop, Bluetooth loss and headset loss
  stop transmissions; returning/reconnecting never replays pending keys.
- Comma/period mapping matches B's keyboard layout and the target app's shortcuts.

Start with Practice mode and a text field on B before enabling control in the
target application. The README gives the pairing and calibration sequence.
