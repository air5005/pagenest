# Task 5 — Foreground MediaSession playback and interruption handling

## Status

Implemented with the concerns listed below.  The intended source and test files are ready to commit as `feat: keep speech playback active in background`.

## Implemented behavior

- Added a private `mediaPlayback` `MediaSessionService` with the foreground-service permissions, a dedicated playback notification channel, and a Media3 `MediaSession`.
- Added a Media3 `SimpleBasePlayer` facade with play, pause, stop, next, and previous commands, plus now-playing metadata for book/chapter titles.
- Added a `SpeechController` adapter for `SpeechSession` commands and command routing from the media session.
- Added audio-focus handling for transient/permanent loss, ducking, noisy-output broadcasts, and focus abandonment.  Every interruption pauses playback; focus gain deliberately never resumes playback.
- Added a cancellable, bounded encoded-MP3 player backed by Media3/ExoPlayer.  It rejects empty or oversized inputs, copies accepted bytes, stops/releases on cancellation, and releases exactly once on normal completion/error/close.
- Service/player creation defaults to a paused state, so process recreation does not auto-start playback.
- Added Robolectric unit coverage for media command routing, focus/noisy interruption behavior, and encoded-audio completion/error/cancellation cleanup.
- Added Android instrumentation coverage for service manifest visibility, foreground type, notification channel creation, controller connection/play-pause calls, and initial paused state.  It assembled but could not execute because no device is connected.

## TDD evidence

The tests were written before the production playback package.

### RED

Command:

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'; .\gradlew.bat :app:testDebugUnitTest --tests '*SpeechMediaPlayerTest' --tests '*SpeechAudioFocusControllerTest' --tests '*Media3EncodedAudioPlayerTest' --console=plain
```

Relevant result: `BUILD FAILED in 1m 4s`, with expected unresolved references for the absent `SpeechController`, `SpeechMediaPlayer`, `BecomingNoisyReceiver`, and encoded-player types.

The Android test was also assembled before the service existed:

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'; .\gradlew.bat :app:assembleDebugAndroidTest --console=plain
```

Relevant result: expected unresolved reference to `SpeechPlaybackService` before its implementation.

### GREEN

Focused unit-test command:

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'; .\gradlew.bat :app:testDebugUnitTest --tests '*SpeechMediaPlayerTest' --tests '*SpeechAudioFocusControllerTest' --tests '*Media3EncodedAudioPlayerTest' --console=plain
```

Result: 7 tests passed, 0 failures, 0 errors:

- `SpeechMediaPlayerTest`: 2
- `SpeechAudioFocusControllerTest`: 2
- `Media3EncodedAudioPlayerTest`: 3

The JUnit XML results are under `app/build/test-results/testDebugUnitTest/` and show zero failures/errors for all three suites.

## Task 5 gates

Final gate command (with the required JDK 17 set in the PowerShell process):

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'; .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

The final artifacts were produced at:

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`

Final lint report: `app/build/reports/lint-results-debug.txt`.  A search for Task 5 playback errors and generic lint errors returned no matches; the report has warnings only.

`git diff --check` completed with no whitespace errors (only existing CRLF conversion notices for the two Android XML files).

## Device status

`adb devices` returned an empty device list.

**NOT RUN (no connected device)**

Carry-forward command:

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'; .\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.air5005.pagenest.speech.playback.SpeechPlaybackServiceTest
```

## Files changed

- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/main/java/com/air5005/pagenest/speech/playback/BecomingNoisyReceiver.kt`
- `app/src/main/java/com/air5005/pagenest/speech/playback/EncodedAudioPlayer.kt`
- `app/src/main/java/com/air5005/pagenest/speech/playback/Media3EncodedAudioPlayer.kt`
- `app/src/main/java/com/air5005/pagenest/speech/playback/SpeechAudioFocusController.kt`
- `app/src/main/java/com/air5005/pagenest/speech/playback/SpeechController.kt`
- `app/src/main/java/com/air5005/pagenest/speech/playback/SpeechMediaPlayer.kt`
- `app/src/main/java/com/air5005/pagenest/speech/playback/SpeechPlaybackService.kt`
- `app/src/test/java/com/air5005/pagenest/speech/playback/SpeechMediaPlayerTest.kt`
- `app/src/test/java/com/air5005/pagenest/speech/playback/SpeechAudioFocusControllerTest.kt`
- `app/src/test/java/com/air5005/pagenest/speech/playback/Media3EncodedAudioPlayerTest.kt`
- `app/src/androidTest/java/com/air5005/pagenest/speech/playback/SpeechPlaybackServiceTest.kt`

## Self-review and concerns

- Reviewed the staged scope and used `git diff --check`; only the Task 5 manifest/resources, playback implementation, tests, and this report are intended for the commit.
- The project does not yet expose a composition root that can construct the existing `SpeechSession` with all of its engine/progress/highlight dependencies.  `SessionSpeechController` is included for that contract, but `SpeechPlaybackService.createSpeechController()` currently supplies the explicit no-op placeholder until that owner wires the session.  Thus the foreground media controls are fully routed but do not yet drive a live reader session in this standalone task.
- Instrumentation assembly passed, but device execution remains NOT RUN as recorded above.
- An untracked `.tmp-media3/` directory was created only while inspecting dependency APIs.  It is deliberately excluded from the commit; its removal was rejected by the execution policy after its exact workspace path was verified.
