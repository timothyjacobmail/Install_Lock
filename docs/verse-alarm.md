# Verse wake-up alarm

Open **Verse wake-up alarm** from the Install Lock dashboard or dashboard search.

1. While the alarm is off, add ESV passages with their references in the separate reference field. Paste plain passage text without verse numbers. No Bible text is bundled or fetched.
2. Grant alarms/reminders, notifications and full-screen alarm access. Raise the alarm volume, test the sound, and try dictation in Practice.
3. Choose a daily local time and the number of successful completions before memory mode (default 3; range 2–20). Enable and save.

The daily alarm begins a three-check routine. The next two checks become available five minutes after the previous check is completed. They cannot be submitted early. New passages are visible. Learned passages display only their reference and must be entered from memory. Selection alternates between learning and memory passages when both are available, rotating by least recently completed within each group.

Each successful alarm challenge earns one completion for its passage. Practice does not earn completions. Incorrect or duplicate submissions earn no credit. Memory mode is fixed when a challenge is selected; it cannot change partway through entry.

Alarm volume is held at its level when the wake-up routine starts, through all three checks, quiet minutes and follow-up waits. If that level is zero, the minimum audible level is used. The held level survives process restarts and reboot, and is released after the final check. Volume keys are consumed on the alarm screen. Elsewhere, a settings observer and 250 ms polling restore attempts to change the alarm stream; a slider may briefly move before restoration. Call and media volume are not changed. Android's `DISALLOW_ADJUST_VOLUME` restriction is deliberately avoided because it also mutes the device. Actual timing and manufacturer behavior require phone testing.

Typing ignores case, whitespace and punctuation. Dictation uses Android's speech-recognition activity; short passages still require every word, while longer dictated passages allow one word error per 20 words, capped at three. Dictated text can be reviewed and corrected before submission. Keyboard dictation is also usable, with the same exact word matching as typing. The configured speech provider may use the network. A missing speech provider does not prevent typing.

Each check has one quiet minute, shared by the Quiet and Dictate buttons. Closing the screen does not dismiss the alarm. After first activation, all alarm settings changes wait 15 minutes using the same monotonic countdown rules as scheduled tasks. This is a separate alarm change queue, not a saved-password request. Reboot restarts the settings wait. Pending changes cannot terminate an active wake-up routine. Library edits require the alarm to be off.

Alarm configuration, passages, progress and the current challenge are committed together to device-protected preferences. Exact AlarmManager events and a foreground audio service support screen-off operation; boot broadcasts restore scheduling before first unlock. Follow-ups use elapsed time within a boot and wall time across reboot. Daily alarms follow the phone's local time zone. Settings changes and answer submission reschedule the next event. A ringing challenge has a one-minute recovery alarm in case its process is lost.

## Validation

Build validation on 2026-10-23: debug app and instrumentation APKs assembled; all 246 JVM tests passed, including six verse-alarm rule tests; Android lint completed with zero errors (146 warnings across the project). The six alarm-specific instrumentation tests compiled but could not be completed: the isolated Pixel emulator became unresponsive during APK installation. No APK was installed on the connected physical phone. Actual alarm delivery, speech recognition and reboot behavior still need device acceptance testing.

- `VerseAlarmRulesTest`: normalization, bounded speech matching, passage selection, follow-up deadlines, time zones and DST.
- `VerseAlarmStoreTest`: durable advancement, duplicate submissions, early follow-up rejection, quiet limits, memory promotion, active-routine protection and reboot recovery. Run on an isolated test device; instrumentation temporarily replaces this feature's preferences and restores them afterward.
- On-device acceptance: a real timed alarm with screen locked, all three checks, dictation round-trip, process recreation, reboot before first unlock, silent/DND settings and permission revocation. A build or unit-test pass does not establish OEM alarm delivery reliability.

The alarm cannot establish that someone is physically out of bed or awake, and cannot ring while the phone is powered off. Device-owner protection makes normal force-stop, data clearing and uninstall harder, but does not guarantee delivery if system alarm permissions, sound settings, or device behavior interfere.
