# VoiceKeys — iReal Pro voice controller

Android app for a Moto g56 5G running Android 16: listen through a Shokz OpenRun
Pro and send individual Bluetooth keyboard presses to a second Android device.
Device B needs no companion app. This is an initial device-test build, not a
claim of measured reliability with the headset and live violin.

## Install and connect

The built APK is `app/build/outputs/apk/debug/app-debug.apk`. Install
**VoiceKeys** on device A only. The default build is ARM64, suitable for the Moto;
Android 12/API 31 is the minimum and Android 16/API 36 is the target.

**Moto g56 / Android 16:** use the **BLE keyboard** mode described below. On the
tested Moto firmware, permissions are granted but Android does not expose the
classic HID Device service. BLE publishes the standard keyboard service through
GATT instead. No extra microphone or Bluetooth permission is needed.

The current build is **0.3.0**, with personalized **Trained sounds** recognition
and **200 ms** comma spacing. Install it as an update to retain your speech mappings, selectivity and
Bluetooth settings. Version **0.1.4** was tested on the Moto with a Samsung Tab S10 FE as B:
BLE pairing and a Comma test key succeeded. Keep VoiceKeys visible on A; its
screen stays awake while the BLE keyboard is active. See
[the validation record](docs/VALIDATION.md) for the remaining voice/violin tests.

### Copy, install and add to the home screen (Windows + USB)

1. On your PC, open this project's `app\build\outputs\apk\debug` folder in
   File Explorer and locate **app-debug.apk**.
2. Unlock device A and connect it to the PC with a USB cable that supports data
   transfer. On the phone, swipe down to the USB notification, tap it, and select
   **File transfer** under **Use USB for**.
3. On the PC, open **This PC → moto g56 5G → Internal shared storage → Download**
   (the phone/storage names may vary). Copy **app-debug.apk** into that folder.
   Copy just the APK; no special app folder is needed. Wait for copying to finish
   before disconnecting the cable.
4. On the phone, open **Files** (or **Files by Google**), then **Downloads**. If
   necessary, use **Browse → Internal storage → Download**. Tap **app-debug.apk**.
5. If installation is blocked, tap **Settings** in the prompt and enable
   **Allow from this source** for the Files app. Go back, tap the APK again if
   necessary, and tap **Install**.
6. When installation finishes, tap **Open** to launch **VoiceKeys**. Grant
   **Microphone** and **Nearby devices** when requested.
7. To add a shortcut, return to the phone's home screen and swipe up to show all
   apps. Find **VoiceKeys**, touch and hold its icon, then drag it onto an empty
   spot on the home screen and release. If its icon is already there, skip this
   step. Use the **VoiceKeys** icon to launch the app in future.

After installation, you can delete `app-debug.apk` from Downloads; the installed
app and home-screen shortcut remain. For a later build, copy and open the newer
APK the same way and choose **Update** rather than uninstalling first.

For illustrated help, see [Android's USB transfer guide](https://support.google.com/android/answer/9064445?hl=en-GB)
and [Motorola's app/home-screen guide](https://help.motorola.com/hc/5514/14/global/en-us/CG1387700073.html).

### Connect the headset and device B using BLE (recommended for this Moto)

1. Pair the OpenRun Pro to A in Android Bluetooth settings and enable its **Phone
   calls** audio. In VoiceKeys, grant **Microphone** and **Nearby devices**.
2. Open **Connections** and tap **Use BLE keyboard**. If an old classic-service
   timeout dialog is visible, dismiss it first. The choice is remembered.
3. Wait for **BLE keyboard ready**. Keep VoiceKeys visible and A unlocked. On B,
   open **Bluetooth → Pair new device** and select A's phone name (normally
   **moto g56 5G**). Accept any pairing prompts on both phones.
4. Wait for **BLE keyboard connected and ready** on A. B must enable keyboard
   input and subscribe to keyboard reports before the app permits key sending.
   If B only remembers the old pairing and offers no keyboard option, forget A
   on B and pair again while VoiceKeys is advertising in BLE mode.
5. Focus a text field on B and use **Send a test key** on A. Verify comma, Space
   and period before opening the target app on B and enabling voice control.

In BLE mode, connections are initiated from B. Selecting a paired B on A allows
pairing/reconnection but cannot force B to connect. **Allow BLE pairing** opens
pairing again for a new host; after a bonded host enables keyboard input, the app
restricts reconnections to that host. Leaving the app stops its BLE server and
advertising, as well as voice control. Return to VoiceKeys before reconnecting B.
The app does not change A's global Bluetooth name.

### Classic Bluetooth connection (for devices exposing the HID Device service)

1. Pair the OpenRun Pro to A in Android Bluetooth settings. Enable **Phone calls**
   for the headset; microphone capture uses its communication audio connection.
2. Open VoiceKeys, grant **Microphone** and **Nearby devices**, and open
   **Connections**. Refresh and select the OpenRun Pro.
3. Keep VoiceKeys visible on A. When **Keyboard service: Registered** appears,
   tap **Make A discoverable**. On B, pair with A's phone name. Enable the
   **Input device / Keyboard** option if Android asks. Tethering is unnecessary.
4. On A, refresh the devices, select B from the paired list, and connect it.
   If pairing immediately connects and disconnects once, select B on A and
   connect again: the app accepts only the explicitly selected keyboard host.
   You can select B while registration is pending; the app connects once
   registration succeeds.
5. Focus a text field on B. Use **Send a test key** to verify comma, space and
   period. Check B's physical keyboard layout if punctuation differs. Then open
   the app to be controlled on B and verify its shortcuts.
6. Train the sounds as described below, then use **Practice**. On A's **Control**
   tab, turn voice control on only when ready. Wait for **Listening** before
   speaking. Speech recognition remains available as a separate mode.

If B does not show keyboard capability, close other remote-keyboard apps on A,
return to VoiceKeys, and check registration. If necessary, forget/re-pair A on B
while VoiceKeys is open and its keyboard service is registered. Only one app
can own Android's HID device registration.

If **Keyboard service: Not registered** persists in classic mode, tap **Retry keyboard setup**.
The status below it shows whether Android is opening the keyboard service,
registering, or connecting. Setup now times out with an explanation instead of
waiting indefinitely. Close other Bluetooth keyboard apps on A, including the
one used for earlier keyboard testing. If retry still fails, turn Bluetooth off
and on on A, reopen VoiceKeys, and retry. Device B's selection button remains
usable while registration is pending; a check mark confirms your selection.
If setup still fails, report the full status/error message shown in this panel.
An 8-second classic-service timeout on the tested Moto is addressed by selecting
**Use BLE keyboard**, rather than repeatedly restarting Bluetooth.

For USB diagnostics on device A:

1. In **Settings → About phone**, find **Build number** (possibly under Android
   version or Device details), tap it seven times, and enter the phone PIN if
   prompted. Android confirms that Developer options are enabled.
2. Open **Settings → System → Developer options** (or search Settings for it).
   Enable **USB debugging** and confirm.
3. Connect A to the PC by USB, keep A unlocked, and accept **Allow USB debugging?**
   on the phone. Open VoiceKeys and reproduce the setup error once.
4. On the PC, run `.\scripts\diagnose_bluetooth.ps1`. It reads VoiceKeys'
   permissions and targeted Bluetooth service logs without changing phone
   settings. The report is saved under `.downloads/device-diagnostics/`.

See [Android's Developer options guide](https://developer.android.com/studio/debug/dev-options)
for the USB debugging setup. Diagnostics let us distinguish permission problems
from the HID service being unavailable; the 8-second timeout itself only means
the profile connection callback did not arrive.

The app must remain visible. Home, Back, another app, screen lock, or closing
VoiceKeys stops microphone capture and clears pending keypresses. Returning
never automatically enables listening. It keeps A's screen awake only while
listening or while its BLE keyboard is active and visible, including pairing
and test-key use. If B disconnects, or the actual microphone route stops being the
selected headset, voice control stops. No commands are replayed on reconnect.

## Trained sounds: first setup (0.3.0)

**Trained sounds** is selected by default after this update. It compares your
acoustic examples directly; it does not transcribe words, use a dictionary or
wait for a following word. The old speech mode and its settings are retained.

The four slots start with the following trial sounds; labels and output keys
are editable under **Commands → Edit**:

| Sound label | How to say it | Default key |
| --- | --- | --- |
| BAP | Rhymes with cap | comma `,` |
| DEET | Rhymes with feet | Space |
| GOKE | Rhymes with coke, starting with a hard G | Space |
| SHOOM | Rhymes with boom | period `.` |

DEET and GOKE serve the original start/stop actions, both of which send Space.
GOKE replaces the earlier GO suggestion because its ending gives a more
distinctive pattern in the synthetic comparisons. These are starting candidates,
not a claim that they are optimal for your voice or violin.

1. Connect and select the Shokz headset under **Connections**, then open
   **Commands** with **Trained sounds** selected. Start with BAP if you want to
   try one sound before completing all four.
2. Without violin, tap **Record one example**. Wait for **Make BAP ONCE now**
   (or the chosen label), then make the sound once. Recording ends automatically
   after three seconds; microphone setup time is excluded.
3. In **Review sound example**, use **Play selection** and the two slider handles
   to keep the complete utterance, including its consonants, while excluding
   surrounding silence. Select 100–1,200 ms, then tap **Use example**. Discard and
   retry if the sound is obscured or its beginning is missing.
4. Save at least **five examples per sound**; aim for 8–12 with natural changes in
   pitch, loudness and speed. Include short, quick BAPs for rapid use. A label is
   only a name: you can teach a nonsense sound. Changing the label does not
   replace its training; use **Clear this sound's training** before changing the
   actual utterance. **Remove last** removes the latest example.
5. Under **Violin-only background**, tap **Record violin · 10 seconds**. Play
   throughout without speaking, including bow changes and different registers.
   Add another passage for more variety. The bounded feature bank retains
   examples from earlier passages as well as the new one.
6. Open **Control → Start practice**. One sound with five examples plus violin
   background is enough for Practice. Test fresh utterances both without and
   with violin. Test BAP repetitions up to **five per second**; leave at least a
   second between the other commands, matching the intended use. Also test a
   held sound: it should count once. Compare the exact occurrence counts.
7. Play violin alone for several minutes in Practice and watch for false
   detections. Practice sends no keys. Optionally record the first 30 seconds
   for playback/export using **Record next Practice**.
8. When all four slots have at least five examples and the violin background is
   recorded, voice control can be enabled. First test into a blank text field
   on B. Each accepted BAP produces one comma, with at least **200 ms** between
   comma key-downs. Other detected occurrences are also separate keypresses.

**Sound matches** shows the closest plausible match and why it was accepted or
rejected. Smaller distance is a closer match; the learned limit is based on
variation among your examples. Matches too similar to another sound or recorded
violin are rejected. Ordinary nonmatches do not generate log rows. Distances
are not probabilities. **Export diagnostics** includes distance, limit, competing
sound distance and violin distance; infinity means no duration-compatible
comparison was available. Its lookback is not measured microphone-to-key latency.

In **Commands → Edit**, **Match tolerance** is separate from speech selectivity:
**higher tolerance accepts more variation; lower tolerance is stricter**. Start
at 1.00×. If a command is missed, try better examples (including the speed you
actually use) before increasing tolerance. Recheck violin-only rejection after
every change. If two sounds are confused, choose a different utterance rather
than relying only on tolerance.

Training stores acoustic features privately on A and survives app restarts.
Use the same headset for training and control; a different microphone may
require retraining.
Enrollment audio is held only for review and discarded after saving/discarding;
playback uses the same disposable private cache as Practice playback. No sound
model or enrollment audio is uploaded. Uninstalling the app removes its training.
This is an experimental template matcher; your real headset/violin results,
including rapid BAP accuracy and delay, still need validation.

## Speech commands (previous recognition mode)

Select **Speech recognition (previous mode)** under Control or Commands to use
the original mappings and diagnostics described below.

| Spoken command | One keypress |
| --- | --- |
| one | comma `,` |
| two | comma `,` |
| three | comma `,` |
| four | comma `,` |
| start | Space |
| stop | Space |
| pause | period `.` |

Every detected occurrence is handled separately. Continuous “one two three four”
can produce four commas without silence between words. Comma key-down reports
are spaced at least **200 ms apart**. A short FIFO preserves batched detections;
it does not discard them through a cooldown or invent missing counts. Each key
is released after 25 ms. Start and stop both send Space, regardless of B's
playback state.

Use **Commands → Add word** or **Speak new word** to change the mappings. Spoken
entry transcribes a three-second sample, then lets you confirm/correct the word
and select a key. Up to nine English single-word commands are supported.
Mappings and selectivity settings persist locally. Supported keys include
punctuation, Space, letters, digits, arrows, Enter, Escape, Tab and Backspace;
letters refer to unshifted physical key positions.

## Speech recognition diagnostics (introduced in 0.2.0)

**Favour command words** is on by default and applies to Practice and voice
control. It gives your configured words extra weight during recognition; it
does not train a model to your voice or force every sound into a command. Stop
listening before changing it. Turn it off to compare the original recognizer
using the same words and selectivity values. Your existing values are preserved;
new installations default to 0.20, with four and pause at 0.15.

To investigate missed words:

1. Under **Control → Practice with your violin**, enable **Record next Practice**
   if you want to listen to the actual headset input. Recording is optional and
   applies only to the next Practice session, never voice control.
2. Start Practice and wait for the ready message. In the first 30 seconds, say
   **four**, **pause** and **stop** several times with short gaps, then say
   **one two three four** twice. Use a separate session for speech with violin,
   and another for violin alone. Note what you intended to say for each session.
3. Watch **What recognition heard**. The transcript is a changing hypothesis,
   not a verbatim recording. Word decisions show **Accepted**, **Below
   selectivity**, **Not a command**, or unavailable/invalid scores. For example,
   a recognized “for” is shown as **Not a command** rather than silently lost.
   Missing transcript words alone do not prove that microphone audio was missing.
4. Stop Practice. If you recorded it, tap **Play** and listen for missing word
   beginnings. This is the exact 16 kHz PCM input supplied to the recognizer
   after verifying the Bluetooth microphone route. It includes any processing
   already applied by the headset and Android.
5. Use **Export diagnostics** for transcript changes and word decisions, and
   **Export WAV** for the optional recording. Diagnostics include the Practice
   session number, decoder mode, audio offsets, acoustic score and selectivity.
   The log holds the latest 1,000 entries. Only the first 30 seconds are recorded;
   Practice continues normally after the recording limit.
6. Repeat with **Favour command words** off, keeping selectivity unchanged. Use
   fresh examples, including continuous counting, to compare missed commands,
   unwanted commands and response time. Biased decoding can still miss words.

Practice recordings stay in memory until discarded, replaced by another recording, or
the activity is recreated. Playback temporarily writes a private cache WAV,
which is removed when playback stops or VoiceKeys next opens. Only explicit
export saves a recording to your chosen location. Starting Practice or leaving
the app stops playback. Practice audio is recorded only when the switch is enabled;
sound-enrollment and violin-training buttons explicitly start their own short captures.

## Speech mode violin calibration

1. Select **Practice**, which uses the same recognition path but sends no keys.
2. Clear the log and play violin without speaking for several minutes, including
   loud passages, all registers, bow changes, and normal head movement. Look for
   unwanted detections.
3. Speak each command while playing, then try repeated counting sequences at up
   to five words per second. Compare the exact number of detections with what
   you said. Test individual repeated commands too; speech models can omit words.
4. Under **Commands → Edit**, raise a word's selectivity to reject more uncertain
   matches; lower it if commands are missed. Repeat steps 2–3 on a different
   passage. Adjust the headset fit if violin is overwhelming your voice.
5. The **Recent detections → Export CSV** log contains accepted detections and approximate
   decoder lookback, **not measured microphone-to-key latency**. Clearing the log
   resets counters; recreating the activity loses the in-memory log.

Selectivity is the geometric mean of emitted token acoustic scores. It is not a
calibrated probability that a command is correct, and does not guarantee violin
rejection. There is no forced nearest-command fallback. Unknown words, missing
scores and low-scoring words send nothing. The headset's own noise cancellation
is used as delivered by Android; the app does not assume it removes violin.

No internet permission, background microphone service or cloud recognizer is
used. Normal voice control retains no audio. Training retains acoustic features;
optional Practice recordings and exports behave as described above. Mappings
and settings are saved locally.

## Build

Requirements: JDK 17, Python 3.10+, Android SDK platform 36, and network access for
the initial dependency downloads. Open the project in Android Studio or run:

```powershell
python -m pip install -r scripts/requirements-assets.txt
python scripts/prepare_assets.py
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

On this checkout, `scripts/build.ps1 -SkipAssetDownload` also finds the already
downloaded toolchain under `.tools`. On other machines, set `JAVA_HOME` and
`ANDROID_HOME`; Android Studio can create `local.properties` automatically.
On Linux/macOS, use `bash gradlew` in place of `gradlew.bat`.

The asset script verifies pinned SHA256 checksums before installing the upstream
AAR and model. Binaries, SDKs, caches and APKs are ignored by Git. A clean clone
must run asset preparation before building. CI runs unit tests, lint, an APK
build and a native-library alignment check. Add `-PincludeEmulator=true` to also
include x86_64 native libraries for emulator development.

With a USB-debugging Android device connected, run
`.\gradlew.bat :app:connectedDebugAndroidTest` to exercise the bundled native
recognizer and Kotlin matcher using synthetic test audio. This test does not
open the microphone or send keyboard input; it does not replace headset testing.

## Implementation and validation

- Kotlin / Jetpack Compose; Android's Bluetooth HID device API with a keyboard
  SDP record, report descriptor, press/release reports, and boot/report handling.
- Android communication-device selection plus verification of the actual
  `AudioRecord` input route. Audio capture is 16 kHz mono PCM in 20 ms blocks.
- Trained sounds: normalized 13-dimensional MFCC features from 25 ms windows at
  10 ms steps, matched by streaming subsequence dynamic time warping. A 30 ms
  settling interval commits local matches. Acoustic onsets rearm recognition;
  elapsed time alone does not retrigger a held sound. Negative examples span
  100, 200, 400 and 800 ms, including short violin transients for BAP comparison.
  Fixed memory bounds and per-session model snapshots keep capture bounded.
  This path does not initialize the ASR model. It uses no neural-network training.
- Speech mode: offline sherpa-onnx with the quantized X-ASR 160 ms streaming model. This
  model is larger than the tiny keyword model because that model failed adjacent
  short-command tests. Modified beam search with four active paths favours the
  configured commands (hotword score 0.5); a switch retains the original greedy
  decoder for comparison. The bias tokenizer vocabulary is derived from the
  pinned model archive. Matching uses whole words, per-word acoustic rejection
  and 120 ms of stable prefix output, including adjacent words in beam mode.
  A revision of already committed output restarts the decoder segment without
  replaying keys. Unknown words are never assigned to the nearest command.
- A 1.6-second nonempty-segment silence allowance avoids resetting before
  delayed words emerge. Accepted words do not wait for this silence timeout.
- The model's **160 ms chunk size is not an end-to-end latency guarantee**.
  Recognition can return multiple words per chunk. Bluetooth transport,
  stabilization, CPU speed and comma pacing add delay. A processing lag over
  one second stops the session instead of continuing with stale audio.
- Native model objects live on a serial worker. Lifecycle cancellation revokes
  key transmission before stopping capture; queued callbacks have a session
  cancellation token. Neither recognition nor key queues survive a stop.
- Unit tests cover exact counts, repeated token occurrences, rejection, partial
  words, decoder revision failures, pacing, queue clearing, overflow and HID
  report contents. They do not establish acoustic recognition accuracy.

Optional offline model test (not an APK dependency):

```powershell
python -m pip install -r scripts/requirements-test.txt
python scripts/recognition_smoke_test.py counting.wav --expect one two three four
python scripts/recognition_smoke_test.py violin-only.wav --expect
python scripts/verify_apk.py app/build/outputs/apk/debug/app-debug.apk
```

WAV input must be 16 kHz mono PCM16. A silence smoke test always runs first.
Synthetic speech is useful for integration checks; actual OpenRun Pro audio,
simultaneous violin, Bluetooth HID/microphone coexistence, Android lifecycle
behavior, sustained CPU throughput and real latency must still be measured on
the target devices. Passing a brief music-only test cannot prove a zero false
trigger rate. In particular, successive identical spoken words can be omitted by
the underlying speech model even though separate emitted occurrences are never
deduplicated by the app.

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for model and library sources.
See [docs/VALIDATION.md](docs/VALIDATION.md) for test results, known limitations,
and the remaining device checks.
