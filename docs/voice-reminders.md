# Offline Voice Tasks & Reminders

Optional feature within the existing InstallLock APK. It cannot authorize or change
InstallLock policies, password delays, app locks, or the VPN. Existing protection
storage and verse-alarm scheduling are separate.

## Sources and reuse

Inspected snapshots:

- [Dicio](https://github.com/DicioTeam/dicio-android) `fe675ede2bc06fbe9d226c775e7d020760215a90`: offline speech and wake-service design.
  Uses its underlying Vosk engine directly (Apache-2.0); no GPL application code copied.
- [SpeakAlert](https://github.com/ghostgramlabs/SpeakAlert) `8363caf5a99fcde6c4e35a8173f45aa5a5f648e5`: adapted alarm scheduling and
  stale-delivery validation (Apache-2.0). See bundled NOTICE and license.
- [Key Mapper](https://github.com/keymapperorg/KeyMapper) `55a41f9a083ba5445e33df86071d00d34c23cc2d`: evaluated input detection and
  privileged bridge. Implemented a narrow Android accessibility key hook instead of
  importing its whole GPL app/bridge into InstallLock's policy service.

This combines capabilities, not the three APKs. Vosk Android 0.3.75 and JNA 5.18.1
are runtime dependencies. The bundled Alpha Cephei English model is Apache-2.0:
`vosk-model-small-en-us-0.15`, SHA-256
`30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498`.

## Using it

Dashboard → Voice Tasks & Reminders. Tap Dictate offline, grant microphone access,
and speak. The first use unpacks the included model locally. There is no cloud
fallback, account, API key, or model download on the phone. Review and edit before
saving; optional fast save has Undo. If speech recognition substitutes "and five
minutes" for "in five minutes", the app offers a correction and always requires
review, including when fast save is enabled. Typed commands keep the strict grammar.
No Google Tasks sync is included because it requires internet access.

The default shake action is **record a voice memo**: shake three times, wait
for the two readiness pulses, speak, then tap **Finish**. The app asks **When should
I remind you?** using an installed offline English voice, then listens in Vosk.
Say **five minutes**, **five hours**, **in twenty five minutes**, or similar.
Review the proposed playback time and tap **Save task**. A duration can also be
typed (for example `5 mins`); **No reminder** saves an undated memo. Answers with
missing/ambiguous units are rejected and can be retried. If no offline speaking
voice is installed, the question remains visible and the answer still uses offline
recognition. The question's audio finishes before listening begins. Shake activation
resumes after saving or cancelling. In Activation & settings, turn off **Shake
records a voice memo (off = dictate)** to use speech-to-text for shaking instead.
The Dictate button, volume shortcut and wake phrase continue to use dictation.
Memo recording waits until the speech listener releases the microphone. It requires
unlocking the phone and is limited to two minutes; cancelled audio is removed.
Verified on the Vivo: default memo routing, the optional dictation route, microphone
handoff from an active listener, recording/saving an audio file, and resuming activation
all passed in `shakeDefaultsToMemoAndSavesAudioThenResumesActivation`. The temporary
test memo was removed. All 269 unit tests passed. Logs:
`build/voice-shake-memo-build.txt`, `build/voice-shake-memo-device-test.txt`.

Spoken memo timing verification: 271 unit tests passed. On the Vivo, both memo
timing tests passed: a typed five-minute answer saves the recorded audio and resumes
activation; the second test waits for the offline prompt/listening handoff, rejects
an answer missing its unit, recognizes a prerecorded "five hours" fixture in native
Vosk, and routes that result through the duration receiver to a confirmed five-hour
memo reminder. The latter supplies decoded test audio rather than a live user voice.
The installed prompt voice was `en-us-x-tpf-local`. Test memos were removed.
Logs: `build/voice-memo-timing-final-build.txt`,
`build/voice-memo-timing-final-test.txt`, and `build/voice-memo-timing-build.txt`.

**Practice my time phrases** opens a guided six-attempt session: five minutes,
ten hours, and six minutes, repeated twice. Tap Record phrase, speak after the
vibration, review what was heard, then Confirm sample. Skip and retry are available.
Confirmed samples persist locally; no raw practice recordings are stored. Practice
creates no reminders. It pauses activation while open and resumes previously active
shake/wake detection when finished. Reset learned time phrases removes this profile.

This is phrase personalization, not acoustic-model training or speaker verification.
After a sample is confirmed, reminder-duration capture uses a focused Vosk vocabulary
for numbers 1–99 with seconds/minutes/hours/days and the user's confirmed phrases.
Practice itself uses unrestricted recognition so it does not simply force the
displayed answer. Exact confirmed misheard phrases can be corrected; learned
corrections require another explicit confirmation before reminder review. Missing
units, conflicting samples, and remapping an already valid different time are rejected.
General dictation is unchanged. The practice profile stays in separate local storage
and is currently not included in reminder ZIP backups.

Practice verification: 276 unit tests passed. The Vivo UI test verified that live
practice capture starts, recognized text is not learned until Confirm sample is
pressed, confirmation advances the guided phrase, conflicting mappings are rejected,
and native Vosk with the personalized grammar recognizes the prerecorded five-hour
fixture. The test supplies a synthetic misheard result for the UI step and restores
the original profile afterward; it does not train on the user's voice. User samples
are collected only during the guided session. Logs:
`build/voice-time-practice-final-build.txt`, `build/voice-time-practice-device-test.txt`.

After dictation, tap **Save / review task**, check the title and reminder time,
then **Save task** in the review dialog. Recognized full commands open review
automatically. Incomplete speech stays in the text field until reviewed; fragments
such as "take out the trash in five minutes" can propose a reminder, while other
text opens as an undated task with an explanation that no time was understood.
These fallback drafts always require confirmation, including with quick-save enabled.
Verified on the Vivo: two UI tests clicked the actual Save task button and checked
persisted task storage, covering a dictated fragment with a five-minute reminder
and unrecognized dictation saved without an invented time. Both passed; their
temporary tasks were removed. Logs: `build/voice-save-build.txt` and
`build/voice-save-device-test.txt`.

Supported English examples:

- Add study CATIA to my tasks.
- Remind me to take out the trash in five minutes.
- Remind me in twenty five minutes to stretch.
- Remind me tomorrow at nine AM to call John.
- Remind me 2026-12-01 at 21:30 to call John.
- Remind me every Monday at seven PM to take out the bins.
- Show my tasks for today.
- Mark study CATIA as done.
- Snooze study CATIA for ten minutes.

Unknown commands and ambiguous times produce a clarification, not an invented time.
Voice management commands require an exact, unique title and explicit confirmation.
Dates may also be edited manually using `yyyy-MM-dd HH:mm[:ss]`.

## Features

- Local tasks, scheduled reminders, priorities, lists, notes, search, open/today/
  upcoming/overdue/completed/archived views, duplicate, edit, complete, delete, snooze.
- Daily/weekly/monthly repeat schedules. Completing an occurrence advances to the
  next future occurrence; an uncompleted occurrence stays overdue. Snooze preserves
  the underlying recurring date. Calendar repeats use the saved timezone.
- Quiet hours (22:00–07:00), private lock-screen notifications by default, optional
  bounded follow-ups (three, ten minutes apart), Done/Snooze notification actions.
- Optional offline TTS and recorded voice memos (up to two minutes). TTS explicitly
  selects a voice that does not require network; missing offline voices leave the
  ordinary notification available. A Stop action ends audio playback.
- ZIP backup/import includes tasks and referenced recordings. Imports validate
  task data first, merge by UUID, and reject unsafe paths and oversized archives.
  Voice preferences are included; permissions and the active-listening state are not.
- Quick Settings tile, privacy-preserving home widget, and optional assistant role.
- Volume Down hold (800 ms) through existing accessibility service. The full key
  stream passes through, so normal volume changes still happen during the hold.
- Configurable shake threshold, three peaks, ten-second cooldown; voice memo by
  default, with a dictation option.
- Optional experimental “Hey Reminder” via Vosk grammar recognition. Pause after
  the phrase, then speak after the vibration. This is not speaker verification,
  OpenWakeWord, or a dedicated low-power hardware hotword engine.
- Start/pause controls; background listening is opt-in and never auto-started after
  reboot. Task alarms are restored after reboot/unlock/update/time changes.
  Enabling either the shake or wake checkbox starts activation immediately (requests
  microphone permission if needed). The live status distinguishes starting, ready,
  and paused; Resume activation restarts saved modes after a pause. Turning off
  both modes stops activation. Shake-only mode does not record while waiting.

## Important boundaries

- English only in this build. Arbitrary wake phrases/model import, listening schedules,
  checked subtasks, and specialized hardware hotword detection are not implemented.
  Notes can hold a simple checklist but are not interactive subtasks.
- Volume detection with the screen completely off and shake delivery during deep
  sleep are not guaranteed. There is no bundled ADB/Shizuku/root bridge.
- The activity requires ordinary phone unlocking; assistant capture does not bypass
  the lock screen. Background wake recognition can run while locked when permitted.
- Exact-alarm access and notification access are checked in settings. Without exact
  access Android's inexact scheduler is used, which may deliver late.
- The reminder feature contains no networking code. The combined InstallLock package
  still has INTERNET permission for its existing unrelated features.
- Changes in system time do not reinterpret a saved reminder's text. Saved timestamps
  remain absolute; calendar recurrence advances using each task's saved timezone.
- User recordings and transcripts are local. Removing the last task referencing a
  recording deletes the audio file. Cancelling an unsaved recording also deletes it.

## Verification

Unit tests cover supported/ambiguous commands, relative times, recurrence across DST,
and stale/duplicate/cancelled alarm rejection. Device tests create uniquely identified
test reminders, check actual notification delivery and snooze/completion, exercise
backup validation and the activity. They do not clear InstallLock policy data.

Manual Vivo checklist: dictate the examples; test wake phrase with screen on/off;
test Volume Down while another app is foreground; shake while on/off; verify task
notification after sleep; inspect battery consumption over an hour; test offline TTS
and voice memos; export/import a backup; reboot and verify future reminder restoration.

Build: use existing Android SDK and JDK 21, then `gradlew :app:assembleDebug
:app:testDebugUnitTest`. If Windows Java reports Unix-domain socket path errors, set
`JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=<short-existing-directory>` for the
build process only. Do not reset device-owner provisioning or uninstall the existing
app to install an update; keep the existing package name and signing key.

### Verified on 24 October 2026

- `assembleDebug`, `assembleDebugAndroidTest`, and `testDebugUnitTest`: successful;
  269 unit tests, zero failures/errors.
- Updated the connected Vivo V2436 (Y39 5G), Android 16, using `adb install -r`;
  no uninstall, data reset, or device-owner reprovisioning.
- `VoiceReminderIntegrationTest`: four tests passed on the physical phone: bundled
  native Vosk recognition/interpretation, scheduled notification and snooze/completion,
  backup validation/merge, and task-screen controls. Test-created tasks were removed.
- The bundled synthetic English fixture was recognized as "remind me to take out
  the trash and five minutes". The explicit correction path proposes "in five
  minutes" and requires review. This is not a claim of perfect transcription.
- Opened the dashboard and Voice Tasks screen, verified microphone capture reaches
  the listening state, and confirmed device-owner and accessibility settings remain
  present. Instrumentation left accessibility disconnected; reconnected the existing
  service and verified BrowserKeywordService and LockService are running with no
  crashed accessibility services. Continuous listening is off after testing.
- The user reported intermittent recognition success and the same "and 5 mins"
  substitution during live dictation. The final build supports this correction but
  still requires review; accent/noise accuracy is not considered fully validated.
- Wake phrase, physical shake/volume triggers, assistant
  role/widget/tile, voice memo/TTS playback, reboot recovery and long-term battery
  behavior still require the manual checklist above.

Local verification logs: `build/voice-build-verified.txt` and
`build/voice-device-test-verified.txt`. APK: `app/build/outputs/apk/debug/app-debug.apk`.

### Shake activation correction

The first live shake report showed `shake=true` but `running=false`, with no voice
service or sensor registration: the checkbox only saved a preference. Enabling
shake/wake now starts activation, with permission handling, live status, and an
explicit Resume control. Pausing keeps the chosen modes but stops their service.
The microphone is not started while waiting for a shake. The fix passed all 269
unit tests; build logs are `build/voice-shake-build.txt` and
`build/voice-shake-package.txt`.
On the Vivo, toggling shake on now reached "Shake ready" without pressing Resume.
Verified the foreground VoiceListenerService and its active accelerometer
registration with sensor access granted. Left shake enabled, wake phrase disabled,
for a physical screen-on shake test; sensor registration alone does not establish
gesture reliability or screen-off operation.
The user confirmed physical shake detection. Vivo vibration diagnostics showed the
original 60 ms readiness tap completed but was not felt; readiness now uses two
160 ms pulses separated by 80 ms, respecting system touch-feedback settings.
Failure to vibrate cannot terminate dictation.
Built and installed the feedback update; Vivo diagnostics confirmed the complete
160/80/160 ms pattern finished (410 ms total measured) when dictation started.
Shake activation and the existing protection/accessibility services remained running.
Build log: `build/voice-haptic-build.txt`; device result: `build/voice-vibration-after.txt`.
