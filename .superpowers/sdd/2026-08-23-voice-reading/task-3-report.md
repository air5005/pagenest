# Task 3 report: cancellable Android system TTS engine

## Summary and files

Implemented the offline `SpeechEngine` boundary and a one-utterance `SystemTtsEngine` with Android system TTS. The engine owns a single progress listener, maps availability and playback failures to Task 1 `SpeechError` values, clamps rate and pitch, uses generation-qualified utterance IDs and `QUEUE_FLUSH`, propagates caller cancellation, rejects stale callbacks, and releases the platform engine idempotently. The production Android factory initializes on `Dispatchers.Main.immediate` and isolates all framework objects from JVM tests.

Files:

- Created `app/src/main/java/com/air5005/pagenest/speech/engine/SpeechEngine.kt`.
- Created `app/src/main/java/com/air5005/pagenest/speech/engine/SystemTtsEngine.kt`.
- Created `app/src/main/java/com/air5005/pagenest/speech/engine/AndroidTextToSpeechFactory.kt`.
- Created `app/src/test/java/com/air5005/pagenest/speech/engine/SystemTtsEngineTest.kt`.
- Modified `app/src/main/java/com/wxn/reader/di/AppModule.kt` to provide the singleton system engine without calling the legacy global `Speech` path.

## RED/GREEN evidence

Environment for every Gradle run:

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
$env:ANDROID_HOME='C:\Users\Administrator\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
```

Initial RED:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests '*SystemTtsEngineTest'
```

Expected result observed: `compileDebugUnitTestKotlin FAILED` with unresolved references for `SystemTtsEngine`, `SpeechEngineResult`, `SpeechRequest`, and the platform seam. `BUILD FAILED in 50s`.

Incremental RED/GREEN cycles using the same focused command:

- Lifecycle completion/caller cancellation: minimal implementation GREEN, `BUILD SUCCESSFUL in 54s`.
- Voice enumeration: RED assertion because the engine returned an empty list; locale filter/map implementation GREEN, `BUILD SUCCESSFUL in 58s`.
- Request configuration: RED assertion because locale, voice, rate, and pitch were not applied; bounded configuration implementation GREEN, `BUILD SUCCESSFUL in 57s`.
- Availability categories: RED virtual timeout on missing language data; `setLanguage` result mapping GREEN, `BUILD SUCCESSFUL in 50s`.
- Platform errors: RED virtual timeout because callbacks/start errors were not terminal; callback and immediate error handling implemented. A subsequent timeout was traced to the fake wiring `speakResult` into `setRate` rather than `speak`; correcting that test seam produced GREEN, `BUILD SUCCESSFUL in 52s`.
- Explicit stop/stale callbacks: RED virtual timeout because `stop()` was a no-op; atomic cancellation plus platform stop GREEN, `BUILD SUCCESSFUL in 56s`.
- Idempotent close: RED virtual timeout because close did not terminate playback; atomic resource release GREEN, `BUILD SUCCESSFUL in 55s`.
- Final focused suite after Android adapter and mutation hardening: 8/8 tests GREEN, `BUILD SUCCESSFUL in 1m 3s`.

## Lifecycle, cancellation, and stress evidence

- `suspendCancellableCoroutine` holds one `ActiveUtterance`; both the active reference and its completion flag use compare-and-set, so only one terminal path can resume it.
- Caller cancellation removes only the matching active utterance, calls platform `stop()`, and leaves coroutine cancellation intact (`cancelAndJoin` is used rather than converting cancellation into a normal result).
- Explicit `stop()` returns `SpeechEngineResult.Cancelled` to the active call, invokes the platform stop side effect once, and removes the active identity before any late callback can act.
- The listener is installed once. Utterance IDs are `<generationId>:<segmentId>`, so old-generation completion and error callbacks cannot complete a new request.
- The deterministic stress test performs 20 iterations alternating duplicate `done/done/error/onStop` callbacks and caller cancellation followed by late callbacks. It asserts all completed iterations return `Completed`, cancellation jobs join normally as cancelled, cancellation produces exactly 10 platform stops, and the listener is installed exactly once. No sleeps, real voice engine, leaked callback, or double-resume occurred.
- `close()` uses a closed-state CAS and atomic resource handoff. Calling it twice stops and shuts down the initialized platform exactly once and completes active playback as `Cancelled`. Factory cancellation and constructor-return interleavings also guard shutdown with a release CAS.

## Full regression, build, and lint

```powershell
./gradlew.bat :app:testDebugUnitTest
```

Result: `BUILD SUCCESSFUL in 1m 17s`; XML totals: 195 tests, 0 failures, 0 errors, 0 skipped. The task also ran the app's private-store native validation dependency.

```powershell
./gradlew.bat :app:assembleDebug :app:lintDebug
```

Result: `BUILD SUCCESSFUL in 3m 42s`; `assembleDebug` and `lintDebug` both completed. The debug APK and lint HTML report were generated.

## Mutation self-review

- Returning an empty/default voice list, failing to filter the requested locale, or mapping fields incorrectly fails the voice test.
- Omitting locale/voice application, changing `QUEUE_FLUSH`, dropping the generation from the utterance ID, or using wrong clamp endpoints fails request configuration assertions. Both rate and pitch independently prove lower `0.25` and upper `2.0` bounds.
- Mapping null initialization, missing data, unsupported locale, callback error, or immediate start error to the wrong result fails category assertions.
- Removing either active-identity CAS or completion CAS is exercised by duplicate/stale/cancellation stress and risks a deterministic wrong result or double resume.
- Removing stop/shutdown side effects, allowing repeat close, reinstalling listeners, or letting stale callbacks target the next request fails exact resource/count/state assertions.
- No test asserts only that a mock exists; the fake is the external Android engine boundary, while tests assert returned engine results, active-job state, exact platform inputs, and owned resource side effects.

## Concerns

- JVM tests intentionally do not instantiate a real device TTS engine; the production Android adapter is compiler-, build-, and lint-verified behind the tested platform seam. Device-specific installed voices remain an integration characteristic.
- Gradle reports the repository's existing SDK XML tool-version warning and existing deprecation warnings in unrelated legacy files; neither failed tests, assembly, or lint.

## Fix Round 1

### Summary and covering tests

Addressed all eight review findings. `SystemTtsEngine` now uses one owner `Channel` actor for initialization state, configuration, start, callback completion, stop, and release. Production gives that owner `Dispatchers.Main.immediate`, so every Android TTS adapter call is Main-confined while initialization runs in a sibling owner job and does not block lifecycle commands. Per-call state synchronizes caller cancellation with the final platform-start decision, and a monotonic nonce distinguishes identical request replays.

Additional coverage is in:

- `SystemTtsEngineTest.kt`: replay nonce/stale callback, stable initialization/start/playback errors, offline-only voices, no-offline-voice error, and `SpeechEngine` DI alias.
- `SystemTtsEngineConcurrencyTest.kt`: latch-gated cancellation before start, A/B overlap ordering, stop during initialization, close during initialization, and owner-thread identity including stop/shutdown.

Production changes also carry Android `Voice.isNetworkConnectionRequired` through the seam, expose/select only matching offline voices, add four stable engine error values, and provide the singleton concrete engine through the `SpeechEngine` abstraction.

### RED/GREEN evidence

All commands used the Java/Android environment documented above.

Replay identity group:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests '*SystemTtsEngineTest'
```

- RED: identical replay IDs compared equal at `SystemTtsEngineTest.kt:170`; 9 tests, 1 failed, `BUILD FAILED in 50s`.
- GREEN after adding the invocation nonce: 9/9, `BUILD SUCCESSFUL in 54s`.

Error taxonomy and offline voice group, same focused command:

- RED: test compilation failed on missing `SystemTtsInitializationFailed`, `SystemTtsStartFailed`, `SystemTtsPlaybackFailed`, `NoOfflineVoiceAvailable`, and the missing platform voice network flag; `BUILD FAILED in 43s`.
- GREEN after model/seam/filter/result mapping: 10/10, `BUILD SUCCESSFUL in 1m 5s`.

Owner serialization and pending lifecycle group:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests '*SystemTtsEngineConcurrencyTest'
```

- RED: 4/4 failed. A/B ordering and cancellation-before-start raised assertion failures, stop during initialization raised `UncompletedCoroutinesError`, and close during initialization leaked `JobCancellationException`; `BUILD FAILED in 1m 46s`.
- The first actor compile identified the old AppModule named argument (`scope` versus `ownerScope`); after correcting that integration call, the gated suite was GREEN 4/4, `BUILD SUCCESSFUL in 1m 39s`.

DI abstraction group:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests '*SystemTtsEngineTest'
```

- RED: `provideSpeechEngine` was unresolved at test compilation; `BUILD FAILED in 43s`.
- GREEN after adding the singleton alias provider, followed by the final combined focused run:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests '*SystemTtsEngineTest' --tests '*SystemTtsEngineConcurrencyTest'
```

Result: 15/15 tests passed, `BUILD SUCCESSFUL in 47s`. A temporary thread-name assertion failure during hardening was traced to kotlinx-coroutines debug suffixes (`@coroutine#8` versus `#9`) on the same executor thread; the assertion now compares the stable owner thread name and also waits through close/release.

### Linearization and lifecycle evidence

- The owner actor alone calls the platform factory and every platform method. The real app owner scope is Main-immediate; callbacks only enqueue progress commands.
- Initialization runs in a sibling job on the same owner dispatcher. The actor remains responsive, so stop/close can cancel pending speech normally before initialization finishes.
- Caller cancellation marks the call under the same lock used for the final `speak()` liveness check. If cancellation wins, no later platform start occurs; if start holds the lock first, cancellation linearizes afterward and the actor stops that active call.
- A/B configure/start/stop transitions are serialized. The gated test proves starts remain `[A, B]`, A receives engine `Cancelled`, and old A cannot flush a newer B.
- Explicit stop/close complete pending callers with `SpeechEngineResult.Cancelled`; caller cancellation remains coroutine cancellation. Close cancels initialization without exposing its internal job cancellation.
- IDs retain generation and segment identity and append a monotonic invocation nonce. An old callback for an identical replay cannot target the new active token.
- Thread recording covers listener installation, locale/voice/rate/pitch, speak, cancellation stop, close stop, and shutdown; every call records the owner executor thread.

### Final gates

```powershell
./gradlew.bat :app:testDebugUnitTest
```

Result: `BUILD SUCCESSFUL in 1m 6s`; XML totals after the run: 202 tests, 0 failures, 0 errors.

```powershell
./gradlew.bat :app:assembleDebug :app:lintDebug
```

Result: `BUILD SUCCESSFUL in 3m 39s`; assembly and lint completed.

### Mutation self-review and concerns

- Removing actor ownership or directly invoking stop from a cancellation handler breaks the recorded owner-thread set.
- Removing the pre-start lock/liveness check makes the cancellation gate record a forbidden start; allowing concurrent configure/start reverses the expected A/B order.
- Awaiting initialization inside stop, or cancelling the shared initialization deferred from close, restores the two gated lifecycle failures.
- Removing the nonce makes replay IDs equal and lets the old callback complete the replay.
- Collapsing any of the three failure stages breaks exact result assertions; allowing a network-required voice breaks both voice enumeration and the no-start assertion.
- Removing the Hilt alias fails the compile-level provider test.
- The real device voice catalog remains platform-dependent. JVM tests intentionally validate the full owner and adapter boundary with deterministic fakes and latches rather than instantiating a voice engine.

## Fix Round 2

### Summary and files

Round 2 closes the actor admission hole and gives `AutoCloseable.close()` a deterministic acknowledgment contract. Public `speak`, `voices`, and `stop` admissions now share one fair lock with close, the command channel is closed and drained during release, and every admitted or rejected caller receives a terminal result. Close performs release directly when already on the owner thread; off-owner and concurrent callers wait on the same release latch. Platform stop and shutdown remain owner-confined and exact-once.

Synchronous platform exceptions are contained at the owning boundary: configuration, voice selection, rate/pitch, and `speak` failures map to `SystemTtsStartFailed`; factory/listener failures map to initialization failure; voice enumeration returns an empty availability result; stop/shutdown failures cannot kill the actor or strand close. Release attempts stop and shutdown independently.

Files:

- `app/src/main/java/com/air5005/pagenest/speech/engine/SystemTtsEngine.kt`
- `app/src/test/java/com/air5005/pagenest/speech/engine/SystemTtsEngineCloseAdmissionTest.kt`
- `app/src/test/java/com/air5005/pagenest/speech/engine/SystemTtsEngineExceptionTest.kt`

### RED/GREEN evidence

Atomic admission and deterministic close group:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests '*SystemTtsEngineCloseAdmissionTest'
```

- RED: test compilation failed because the wished-for admission-lock seam did not exist: `Too many arguments for constructor(factory, ownerScope): SystemTtsEngine`; `BUILD FAILED in 44s`.
- The first implementation run had 2/3 passing; the sole assertion expected one stop even for the explicit-stop case, while the correct observable total was two (one admitted explicit stop plus one close release). After correcting that independently derived expectation, GREEN was 3/3, `BUILD SUCCESSFUL in 47s`.
- The tests deterministically queue `speak`, `voices`, and `stop` ahead of close on a fair lock, with bounded futures and no sleeps. They also gate the owner thread to prove off-owner close does not return before release, and prove owner-thread and repeated/concurrent close observe exact-once stop/shutdown immediately at return.

Platform synchronous exception group:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests '*SystemTtsEngineExceptionTest'
```

- RED: 6 tests, 5 failed, `BUILD FAILED in 55s`. Start/configuration, voice access, explicit stop, and release exceptions produced deterministic `TimeoutException`s; listener installation produced the wrong terminal result. Factory-throw behavior was the passing baseline.
- GREEN: 6/6, `BUILD SUCCESSFUL in 55s`. The table-driven start test covers `setLanguage`, `voices`, `selectVoice`, `setRate`, `setPitch`, and `speak`; a successful second request plus stop/close proves the actor remains usable. Separate tests cover listener/factory initialization, voice-query recovery, explicit-stop recovery, and stop/shutdown release exceptions.

Stable rejected-call result self-review group, same close-focused command:

- RED: self-review found close-rejected speech had regressed from `SystemTtsUnavailable` to initialization failure; 3 tests, 1 failed, `BUILD FAILED in 45s`.
- GREEN: close-rejected speech again returns `SystemTtsUnavailable`, while voices returns empty and stop returns normally; 3/3, `BUILD SUCCESSFUL in 58s`.

### Lifecycle, cancellation, and race evidence

- Admission linearizes the closed-state check with the command send. Close sets the terminal state and sends its command under the same lock, so no public caller can enqueue behind close.
- Owner close closes and drains the channel, completing queued speech as engine `Cancelled`, voice queries as empty, and stop acknowledgments normally. Off-owner callers wait for the same `CountDownLatch`; repeated/concurrent close cannot repeat release.
- Platform calls remain in the owner actor or its direct owner-thread close path. The tests record listener, stop, and shutdown thread identities for both owner and off-owner close.
- Current/queued callers are terminal before stop is attempted. A thrown stop therefore cannot undo cancellation, and stop/shutdown are attempted independently during release.
- The throwing-start fake raises before active-speech assignment and then permits a second complete request. This proves the original continuation is not stranded and a platform exception does not kill the actor.
- No test uses shell sleeps; races use fair-lock queue lengths, latches, futures, and bounded timeouts.

### Final gates

```powershell
./gradlew.bat :app:testDebugUnitTest --tests '*SystemTtsEngine*Test'
```

Result: 24/24, `BUILD SUCCESSFUL in 45s`.

```powershell
./gradlew.bat :app:testDebugUnitTest
```

Result: 211/211, 0 failures/errors, `BUILD SUCCESSFUL in 1m 7s`.

```powershell
./gradlew.bat :app:assembleDebug
```

Result: `BUILD SUCCESSFUL in 54s`.

```powershell
./gradlew.bat :app:lintDebug
```

Result: `BUILD SUCCESSFUL in 3m 23s`.

### Mutation self-review and concerns

- Removing the shared admission lock, moving the closed-state check outside it, or leaving the channel open lets a gated caller enqueue behind close and makes its bounded future fail.
- Making close fire-and-forget fails both immediate release counts and the gated off-owner return assertion; removing exact-once state makes concurrent close increment stop/shutdown counts.
- Removing per-call exception containment restores timeouts or wrong results; catching stop before terminalizing callers breaks cancellation assertions; combining stop/shutdown in one try prevents the second release call after the first throws.
- Mapping close rejection to initialization failure fails the explicit stable-error regression test.
- JVM tests use a deterministic platform seam rather than a device voice engine. The existing SDK XML version warning remains non-fatal and unrelated to these changes.
