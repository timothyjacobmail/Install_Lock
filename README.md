# Install Lock and Arise O Sleeper!

This Android project builds two independent apps:

| Module | App | Application ID |
| --- | --- | --- |
| `app` | Install Lock | `com.example.installlock` |
| `ariseosleeper` | Arise O Sleeper! | `com.example.ariseosleeper` |

`secretvault` is the Delayed Secrets library embedded in Install Lock. Arise O Sleeper! has no dependency on either module and opens directly to its verse alarm screen, with its own launcher icon and private storage.

Build both debug APKs on Windows with Java 21 and the Android SDK configured in `local.properties`:

```powershell
.\gradlew.bat :app:assembleDebug :ariseosleeper:assembleDebug
```

Outputs:

- `app/build/outputs/apk/debug/app-debug.apk`
- `ariseosleeper/build/outputs/apk/debug/ariseosleeper-debug.apk`

Run JVM tests and compile device tests:

```powershell
.\gradlew.bat :app:testDebugUnitTest :ariseosleeper:testDebugUnitTest :app:assembleDebugAndroidTest :ariseosleeper:assembleDebugAndroidTest
```

See [the alarm guide](docs/verse-alarm.md) for setup, retained behavior, and device acceptance checks. Install Lock's scheduled unlocks, curfew timers, and Delayed Secrets timers remain part of Install Lock.

## Moving from the integrated alarm

The original alarm code was saved to GitHub before extraction in commit `ed0dee4`. Install Lock 1.3 removes the verse alarm feature. Install Arise O Sleeper! separately, re-enter passages and alarm times, and grant its alarm, notification, and full-screen permissions in Android Settings. Private alarm settings and progress from Install Lock are not automatically transferred between application IDs. Turn off the old app's alarms before updating; complete any active routine first.

Arise O Sleeper! runs as a normal alarm app and uses Android's full-screen notifications. It does not inherit Install Lock's device-owner privileges. If Install Lock manages the same phone, its configured installation waits and app restrictions also apply to this separately installed app.

The alarm app includes the Inter font and its license in `src/main/assets/licenses/Inter-OFL.txt`.
