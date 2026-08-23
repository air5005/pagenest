# Task 4 report: speech session, progress, and highlighting

## Scope delivered

- Added a single-owner `SpeechSession` command actor with serialized start, pause, resume, next, previous, seek, sleep-timer, stop, and close handling.
- Fenced every playback callback by generation and admitted public commands independently of caller cancellation.
- Committed progress only for `SpeechEngineResult.Completed`.
- Added `RoomSpeechProgressCommitter`: reflowable books delegate `Locator.toJsonString()` through the real `SetReadingProgressUseCase`; PDF books use the existing `GetBookByIdUseCase`/`UpdateBookUseCase` page path.
- Added main-thread `PageViewController` highlighting via a `SpeechHighlightSink` adapter. It changes only `TextLine.isReadAloud`, preserves selection state, and clears on stop, error, natural completion, and close.
- Added an injected monotonic `SpeechClock` and observable timer deadline. Timer expiry performs a normal stop, clears the timer, and never commits the unfinished segment.

## Strict TDD evidence

### Initial RED

Exact command:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests '*SpeechSessionTest' --tests '*SpeechProgressCommitterTest'
```

Result: `BUILD FAILED` after 50 seconds in `:app:compileDebugUnitTestKotlin`. Expected unresolved production contracts included `SpeechSession`, `SpeechProgressCommitter`, `RoomSpeechProgressCommitter`, `SpeechHighlightSink`, `SpeechClock`, and `SpeechOptions`.

### Focused GREEN

Exact command:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests '*SpeechSessionTest' --tests '*SpeechProgressCommitterTest' --tests '*PageViewControllerSpeechHighlightTest'
```

Result: `BUILD SUCCESSFUL` in 52 seconds; 18 Task 4 tests passed.

During GREEN, the first production compile exposed two integration facts: the installed coroutine API has no `Channel.sendCatching`, and `PageViewController` already has a non-suspending `clear()`. The final implementation uses `trySend` under an admission lock and exposes a `SpeechHighlightSink` adapter instead of overloading reader `clear()`.

A lifecycle refinement also followed RED -> GREEN: after removing the untested close API, `SpeechSessionTest` failed compilation on unresolved `closeAndJoin` (`BUILD FAILED` in 48 seconds). The reimplemented close path atomically fences command admission, joins the actor, stops playback, closes the source/channel, cancels timer/playback jobs, and clears highlight/state. The focused session suite then passed 14/14 (`BUILD SUCCESSFUL` in 1 minute 9 seconds).

## Named Task 4 tests

`SpeechSessionTest`:

1. `progress commits only fully completed segments`
2. `seek fences a late completion from the old generation`
3. `pause and resume replay the same unfinished segment without committing pause`
4. `next and previous replace playback without committing skipped segments`
5. `a completed chapter tail advances to the next chapter`
6. `natural completion commits then clears highlight and exposes Completed`
7. `engine failure clears highlight but a concurrent stop has cancellation priority`
8. `an active generation failure is exposed and never committed`
9. `concurrent public commands are serialized with one active generation`
10. `highlight is shown before every engine request and progress follows completion`
11. `stop is crash safe and retains only the last completed locator`
12. `monotonic sleep timer expires without real sleep and commits only prior completion`
13. `caller cancellation cannot cancel an already admitted command`
14. `close joins the command actor and releases playback source and highlight`

`SpeechProgressCommitterTest`:

15. `reflowable completion delegates locator JSON through the actual reading progress use case`
16. `PDF completion delegates the completed page through the existing update book path`
17. `PDF completion saves the last fully completed page rather than the next page`

`PageViewControllerSpeechHighlightTest`:

18. `speech highlight marks only overlapping reader lines and clear preserves selection state`

All session tests use deterministic external fakes. The engine fake can deliberately return a completion after cancellation to prove stale-generation fencing. The clock fake advances monotonic time explicitly and performs no real sleep.

## Full gates

Environment for every Gradle command:

- `JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot`
- `ANDROID_HOME=C:\Users\Administrator\AppData\Local\Android\Sdk`
- `ANDROID_SDK_ROOT=C:\Users\Administrator\AppData\Local\Android\Sdk`

Exact command:

```powershell
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Final post-report run: `BUILD SUCCESSFUL` in 3 minutes 45 seconds (335 tasks: 41 executed, 294 up-to-date). Unit result XML reports 233 tests, 0 failures, 0 errors, and 0 skipped across 20 suites. `assembleDebug` and `lintDebug` both completed.

## Mutation self-review

- Returning `Cancelled` or `Failed` through the completed branch is caught by completed-only and failure/cancellation tests.
- Removing the generation equality check is caught by the late old completion seek test.
- Reversing `next`/`previous`, failing to cross a chapter, or replaying a skipped segment is caught by navigation tests.
- Sending the engine request before highlight, committing before completion, or omitting terminal clear is caught by the shared ordered event test and terminal-state tests.
- Saving the newly started PDF page instead of the completed page is caught by the PDF page assertion.
- Using wall-clock delay or leaving the timer armed is caught by the injected-clock deadline/expiry test.
- Dropping admitted commands on caller cancellation, accepting commands after close, or failing to release actor/source/highlight state is caught by admission and close lifecycle tests.
- Highlighting unrelated lines or clearing user selection is caught against real `TextLine`/`PageViewController` behavior.

## Concerns and follow-up boundaries

- The mandated reflowable path is intentionally exact: `Locator.toJsonString()` delegates to the existing `SetReadingProgressUseCase`. That use case currently derives numeric progression from `locations.totalProgression`, while the base `Locator` JSON stores flat `progression`; the test records the existing resulting repository progression value (`0f`) rather than inventing a new schema. The completed locator itself is persisted correctly. Any schema unification should be a separately specified migration.
- PDF persistence remains page-granular, matching the existing PDF reader path. A process restart resumes at the last completed page, which is conservative when a page contains multiple speech segments.
- Device/HyperOS verification is outside Task 4 and was not run here.

---

## Fix Round 1 (base `ec52c7e5b29bd527f77ae23bb27085d0a80f8bbb`)

This round closes the four Important review findings. It supersedes the two progress concerns
above: flat reflowable progression and PDF multi-part page completion are now handled explicitly.
No Room schema migration was introduced.

### Production corrections

- Reflowable completion now accepts both flat `progression` and nested
  `locations.totalProgression`. A targeted Room `UPDATE` atomically writes locator, numeric
  progression, chapter, and page without overwriting a stale whole `Book`.
- Speech navigation uses a main-thread page-change path that preserves reader callbacks but never
  calls legacy `saveRead`; ordinary manual `setPageIndex` still saves normally.
- Session admission now closes atomically on explicit close, owner cancellation, pre-cancelled
  construction, and actor failure. Close cancels blocked source work, drains queued acknowledgements,
  and performs engine/source/highlight cleanup in `NonCancellable` context.
- Public command contract is documented on `SpeechSession`: applied commands return normally;
  handler failures are returned to their command; closed rejection is `IllegalStateException`;
  admitted work aborted by closure is `CancellationException`; admitted acknowledgements never
  remain pending.
- Invalid `previous`/`seek` resolves before playback invalidation and preserves the current segment.
  Only forward exhaustion reached after a `Completed` engine result produces natural `Completed`.
- Late engine results are delivered beyond playback-job cancellation and are rejected by the
  independent generation fence.
- Real PDF segments carry an explicit final-part/page-boundary marker. The existing PDF persistence
  path ignores non-final parts and commits the page exactly once after its final segment.

### Sequential behavioral RED / GREEN evidence

Every Gradle invocation used the JDK 17 and Android SDK environment recorded in the original report.

1. Reflowable cold-restart loop

   RED command:

   ```powershell
   ./gradlew.bat :app:testDebugUnitTest --tests '*SpeechProgressColdRestartTest'
   ```

   `BUILD FAILED` in 54 seconds: 1 test, 1 failure. The real in-memory Room / real
   `BooksRepositoryImpl` / real `PageViewController` / real `ReflowableSpeechContentSource` test
   expected numeric progress `40f` but restored `0f` from the flat locator.

   GREEN command:

   ```powershell
   ./gradlew.bat :app:testDebugUnitTest --tests '*SpeechProgressColdRestartTest' --tests '*SpeechProgressCommitterTest'
   ```

   `BUILD SUCCESSFUL` in 1 minute 19 seconds: 4 tests passed. The cold-restart test also verifies
   chapter/page restoration and that starting the next speech page never invokes legacy `saveRead`.

2. Actor lifecycle loop

   RED command:

   ```powershell
   ./gradlew.bat :app:testDebugUnitTest --tests '*SpeechSessionTest'
   ```

   `BUILD FAILED` in 57 seconds: 20 tests, 5 failures. Failures were the pre-cancelled owner,
   owner cancellation with blocked/queued acknowledgements, close behind gated next, close behind
   gated seek, and actor failure after a progress exception. The synchronous source exception
   control already returned to its command.

   GREEN command:

   ```powershell
   ./gradlew.bat :app:testDebugUnitTest --tests '*SpeechSessionTest'
   ```

   `BUILD SUCCESSFUL` in 56 seconds: 20 tests passed.

3. Navigation exhaustion loop

   RED command:

   ```powershell
   ./gradlew.bat :app:testDebugUnitTest --tests '*SpeechSessionNavigationTest'
   ```

   `BUILD FAILED` in 47 seconds: 3 tests, 2 failures. A first-segment `previous` and invalid seek
   against a real `ReflowableSpeechContentSource` both stopped playback, cleared highlight, and
   incorrectly exposed `Completed`; the forward-exhaustion control passed.

   GREEN command:

   ```powershell
   ./gradlew.bat :app:testDebugUnitTest --tests '*SpeechSessionNavigationTest' --tests '*SpeechSessionTest'
   ```

   `BUILD SUCCESSFUL` in 1 minute: 23 tests passed.

4. PDF page-boundary loop

   RED command:

   ```powershell
   ./gradlew.bat :app:testDebugUnitTest --tests '*SpeechProgressCommitterTest'
   ```

   `BUILD FAILED` in 53 seconds: 5 tests, 2 failures. A real `PdfSpeechContentSource` extracted a
   501-code-point page into two parts; part 0 persisted prematurely and the complete page wrote twice.

   GREEN focused command:

   ```powershell
   ./gradlew.bat :app:testDebugUnitTest --tests '*SpeechProgressCommitterTest' --tests '*SpeechProgressColdRestartTest' --tests '*SpeechSessionTest' --tests '*SpeechSessionNavigationTest'
   ```

   `BUILD SUCCESSFUL` in 1 minute 18 seconds: 29 tests passed.

### Added named tests

`SpeechProgressColdRestartTest`:

1. `cold restart restores only the last completed speech locator progression and page`

`SpeechSessionTest` additions:

2. `a session whose owner is already cancelled rejects commands without hanging`
3. `owner cancellation drains a blocked command and every queued acknowledgement`
4. `close cancels a gated next and terminates its admitted acknowledgement`
5. `close cancels a gated seek and terminates its admitted acknowledgement`
6. `a progress exception closes the session and later commands fail instead of hanging`
7. `a synchronous source exception fails only that command and actor remains usable`

`SpeechSessionNavigationTest`:

8. `previous at the first real reflowable segment preserves active playback`
9. `invalid seek in a real reflowable source preserves active playback`
10. `only forward exhaustion after completion completes a real reflowable session`

`SpeechProgressCommitterTest` additions:

11. `first completed part of a real multi-segment PDF page does not persist the page`
12. `real multi-segment PDF page persists exactly once after its final part`

### Mutation self-review

- Actual temporary mutation: removed the generation equality check. Exact focused command
  `./gradlew.bat :app:testDebugUnitTest --tests '*SpeechSessionTest.seek fences*'` failed 1/1 at
  the late-completion assertion. The generation check was restored before final gates.
- Actual temporary mutation: changed speech next-page navigation back to legacy `setPageIndex`.
  Exact command `./gradlew.bat :app:testDebugUnitTest --tests '*SpeechProgressColdRestartTest'`
  failed 1/1 because the real controller test observed `saveRead`. The speech-only setter was restored.
- Removing PDF final-part filtering is independently killed by the two-test PDF RED (2/2 failures).
- Removing close cancellation/drain or actor completion admission closure is independently killed by
  the five-test lifecycle RED (5/5 failures).
- Removing flat-locator parsing or targeted coordinate persistence is killed by the real Room
  cold-restart assertions; removing invalid-navigation preservation is killed by both real-source tests.

### Final gates

Fresh speech-focused command:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests 'com.air5005.pagenest.speech.*'
```

Result: `BUILD SUCCESSFUL` in 1 minute 20 seconds. XML totals: 14 suites, 90 tests,
0 failures, 0 errors, 0 skipped.

Fresh complete command:

```powershell
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Result: `BUILD SUCCESSFUL` in 4 minutes 26 seconds (335 tasks: 41 executed,
294 up-to-date). XML totals: 22 suites, 245 tests, 0 failures, 0 errors, 0 skipped.
`assembleDebug` and `lintDebug` both completed.

### Remaining boundary

- Device/HyperOS verification remains outside Task 4 and was not run. No Task 5 work was started.

## Fix Round 2/5 — admission shutdown and transactional reflowable seek

Base: `d307f39075b74343af2c18fd5f0cd0ce35e23656`

### Behavioral TDD evidence

1. Admission closes before slow cleanup

   Added deterministic, no-sleep cleanup gates to the external engine fake and these tests:

   - `owner cancellation closes admission and drains acknowledgements before slow cleanup`
   - `actor failure closes admission before slow cleanup`

   RED command:

   ```powershell
   ./gradlew.bat :app:testDebugUnitTest --tests '*SpeechSessionTest'
   ```

   Result: `BUILD FAILED` in 58 seconds: 22 tests, 2 failures. While `engine.stop()` was gated,
   queued acknowledgements and commands racing cleanup were not terminal because admission was only
   closed by the owner's completion callback after cleanup.

   GREEN command:

   ```powershell
   ./gradlew.bat :app:testDebugUnitTest --tests '*SpeechSessionTest'
   ```

   Result: `BUILD SUCCESSFUL` in 57 seconds: 22 tests passed. The actor now captures its terminal
   failure and atomically closes/drains admission at the start of `finally`, before entering
   `NonCancellable` cleanup. The owner completion callback remains an exact-once fallback.

2. Reflowable seek validates before controller mutation

   Added real `PageViewController` plus real `ReflowableSpeechContentSource` tests:

   - `invalid locator on an existing page leaves real controller and source position unchanged`
   - `valid locator on an existing page commits controller and source position once`

   RED command:

   ```powershell
   ./gradlew.bat :app:testDebugUnitTest --tests '*PageViewControllerSpeechSnapshotTest'
   ```

   Result: `BUILD FAILED` in 59 seconds: 5 tests, 1 failure. The invalid locator returned `null`,
   but `seekSpeechPage` had already moved the real controller from page 0 to page 1.

   GREEN regression command:

   ```powershell
   ./gradlew.bat :app:testDebugUnitTest --tests '*PageViewControllerSpeechSnapshotTest' --tests '*ReflowableSpeechContentSourceTest' --tests '*SpeechSessionTest' --tests '*SpeechSessionNavigationTest'
   ```

   Result: `BUILD SUCCESSFUL` in 1 minute 17 seconds: 34 tests passed. `previewSpeechPage` loads an
   immutable target snapshot through the existing async-safe controller path without activating it;
   the source resolves the exact segment against that snapshot and mutates the controller/source only
   after successful validation. Valid seeks and the existing unloaded-page async controller case remain
   covered.

### Mutation self-review

- Moving `closeAdmission` back below gated cleanup is killed by both new lifecycle tests: all three
  cancellation acknowledgements and the actor-failure race remain pending while the gate is held.
- Removing the preview validation or calling mutating `seekSpeechPage` first is killed by the real
  invalid-locator test's controller page/snapshot assertions. Its later `next()` assertions also detect
  replay or misadvance after a failed seek.
- The valid-locator control prevents an implementation that merely rejects every seek.
- Review confirmed admission closure and drain share one lock/CAS winner, while Android-facing snapshot
  construction and activation remain on `Dispatchers.Main.immediate`; chapter loading remains on IO.

### Round 2 gates

The broad speech-package command was run twice:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests 'com.air5005.pagenest.speech.*'
```

Each broad run reported the same unrelated 5-second fixture timeout in
`SystemTtsEngineCloseAdmissionTest > off owner concurrent close waits for exact once release before returning`.
The exact isolated command passed 1/1 (`BUILD SUCCESSFUL` in 52 seconds), and no TTS production code was
changed in this round. The required relevant regression command above passed 34/34.

Fresh complete command:

```powershell
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Result: `BUILD SUCCESSFUL` in 4 minutes 12 seconds (335 tasks: 41 executed,
294 up-to-date). XML totals: 22 suites, 249 tests, 0 failures, 0 errors, 0 skipped.
`assembleDebug` and `lintDebug` both completed.

### Remaining boundary

- Device/HyperOS verification remains outside Task 4 and was not run. Per user direction, Task 4 is
  archived after this fix round and no Task 5 work was started.



## Fix Round 3/5 — single-load transactional seek candidate

Base: `19b85b8e3b71c4f95b9b0e242dd85980f2e47cd9`

### Behavioral TDD evidence

1. Unloaded seek uses one exact parsed candidate

   Added real `PageViewController` plus real `ReflowableSpeechContentSource` tests using the existing
   parser/layout seam. The seam returns layout A (matching the locator) on its first call and layout B
   (different paragraph, missing the locator) on its second call:

   - `valid unloaded source seek loads once and commits the validated candidate`
   - `invalid unloaded source seek loads once without mutating controller or source`

   RED command:

   ```powershell
   ./gradlew.bat :app:testDebugUnitTest --tests '*PageViewControllerSpeechSnapshotTest'
   ```

   Result: `BUILD FAILED` in 1 minute 15 seconds: 7 tests, 1 failure. The valid unloaded seek expected
   one layout load but observed two. The first layout validated, the second drifted layout was activated,
   and the source then rejected the locator. The invalid-candidate control loaded once and preserved both
   controller and source.

   GREEN command:

   ```powershell
   ./gradlew.bat :app:testDebugUnitTest --tests '*PageViewControllerSpeechSnapshotTest' --tests '*ReflowableSpeechContentSourceTest' --tests '*SpeechSessionNavigationTest'
   ```

   Result: `BUILD SUCCESSFUL` in 1 minute 16 seconds: 14 tests passed. `SpeechPageNavigator` now returns
   an opaque `LoadedSpeechPage`; the controller candidate owns the exact parsed `TextChapter`, page index,
   immutable snapshot, and controller identity. The source validates segments against that snapshot and
   activates that same candidate, so no coordinate-based reload occurs between validation and commit.

2. Layout generation fences stale candidates

   Added:

   - `layout refresh invalidates a loaded speech candidate before activation`

   RED command:

   ```powershell
   ./gradlew.bat :app:testDebugUnitTest --tests '*PageViewControllerSpeechSnapshotTest'
   ```

   Result: `BUILD FAILED` in 1 minute: 8 tests, 1 failure. A candidate loaded before a layout refresh
   still activated and moved the real controller.

   GREEN regression command:

   ```powershell
   ./gradlew.bat :app:testDebugUnitTest --tests '*PageViewControllerSpeechSnapshotTest' --tests '*ReflowableSpeechContentSourceTest' --tests '*SpeechSessionNavigationTest' --tests '*SpeechSessionTest'
   ```

   Result: `BUILD SUCCESSFUL` in 1 minute 14 seconds: 4 suites, 37 tests, 0 failures, 0 errors,
   0 skipped. Candidate creation fences the atomic layout generation before and after async chapter
   loading; activation checks the same generation on `Dispatchers.Main.immediate` before any reader
   mutation. Content/layout reload, book reset, style update, and controller clear invalidate old
   candidates. Private chapter preload and ordinary manual page navigation retain existing behavior.

### Mutation self-review

- Actual temporary mutation: removed the activation-time generation comparison. Exact command
  `./gradlew.bat :app:testDebugUnitTest --tests '*PageViewControllerSpeechSnapshotTest.layout refresh invalidates*'`
  failed 1/1 (`BUILD FAILED` in 1 minute 12 seconds). The fence was restored and the same exact test
  passed 1/1 (`BUILD SUCCESSFUL` in 1 minute 14 seconds) before final gates.
- The original A-then-B RED kills any restoration of preview plus coordinate reload: it observes two
  parser/layout calls and the desired exact-candidate result is absent.
- The invalid unloaded test kills activation before locator validation and checks both real controller
  coordinates/object identity and source segment identity.
- Candidate ownership rejects tokens from another controller. Candidate activation is Main-safe; the
  source performs the small controller/source commit under `NonCancellable` only after all cancellable
  loading and locator validation have completed. No parser instance or resource lifecycle was added.
- Self-review removed the old concrete `seekSpeechPage` compatibility method after it became test-only;
  the controller regression now exercises the production candidate API directly.

### Round 3 gates

The first complete command reached 252 tests but hit the existing load-sensitive 5-second fixture
timeout in
`SystemTtsEngineCloseAdmissionTest > off owner concurrent close waits for exact once release before returning`
(1 failure, `BUILD FAILED` in 4 minutes 24 seconds). No TTS production or test code changed. The exact
isolated test immediately passed 1/1 (`BUILD SUCCESSFUL` in 50 seconds).

Fresh successful complete command:

```powershell
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Result: `BUILD SUCCESSFUL` in 4 minutes 2 seconds (335 tasks: 41 executed,
294 up-to-date). XML totals: 22 suites, 252 tests, 0 failures, 0 errors, 0 skipped.
`assembleDebug` and `lintDebug` both completed.

### Remaining boundary

- Device/HyperOS verification remains outside Task 4 and was not run. Per user direction, Task 4 is
  archived after this fix round and no Task 5 work was started.

## Fix Round 4/5 — fence speech candidates across asynchronous layout reloads

Base: `9140f3373cb6c459ae3cb6aca17099a1b64c1327`

### Root cause and correction

`PageViewController.loadContent` advanced the speech layout generation before launching its
asynchronous three-chapter reload, but retained the old current/previous/next chapter caches until
each replacement was parsed. A speech seek in that window therefore captured an old cached chapter
under the new generation. Because reload completion did not publish another generation, that old
candidate could still activate and leave the controller and source on different layouts.

The controller now atomically publishes a layout state containing both the generation and the active
reload generation. Candidate creation and activation reject the entire reload window. Parsed reader
state is installed on `Dispatchers.Main.immediate` only while its reload generation is still the
latest; an older overlapping reload may finish parsing but cannot overwrite a newer layout. Matching
reload completion is published from `NonCancellable` `finally`, so success, parser failure, and
cancellation all release the fence without blocking Main. The documented failure policy retains the
previous valid caches and makes them available again under the completed generation.

Manual navigation is unchanged. The reload still makes the same single current/next/previous load
attempts, and reflowable seek still validates and activates one exact loaded candidate; no second
coordinate load or parser/resource owner was added.

### Behavioral RED / GREEN evidence

All commands used JDK 17 and the Android SDK environment recorded in the original report.

Initial RED command, before production edits:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests '*PageViewControllerSpeechSnapshotTest'
```

Result: `BUILD FAILED`. The reload-window test observed the exact stale `old` `SpeechSegment` where it
expected rejection. The first overlap fixture also exposed an unsynchronized test counter; after
correcting that fixture only, the exact overlap RED was rerun against unchanged production code:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests '*PageViewControllerSpeechSnapshotTest.older overlapping reload*'
```

Result: `BUILD FAILED` in 1 minute: 1 test, 1 expected `ComparisonFailure`; the delayed older reload
overwrote `latest` with `older`.

Focused GREEN, including failure and cancellation policy controls:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests '*PageViewControllerSpeechSnapshotTest'
```

Result: `BUILD SUCCESSFUL` in 1 minute 2 seconds: 12 tests, 0 failures, 0 errors, 0 skipped.

Relevant regression command:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests '*PageViewControllerSpeechSnapshotTest' --tests '*ReflowableSpeechContentSourceTest' --tests '*SpeechSessionNavigationTest' --tests '*SpeechSessionTest'
```

Result: `BUILD SUCCESSFUL` in 1 minute: 4 suites, 41 tests, 0 failures, 0 errors, 0 skipped.

### Added named tests

1. `source seek during layout reload never activates old cache and recovers on new layout`
2. `older overlapping reload cannot overwrite the latest layout`
3. `failed reload clears its fence and keeps the current valid layout available`
4. `cancelled reload clears its fence and keeps the current valid layout available`

The tests use `CompletableDeferred` parser and reload-tail gates and contain no real sleep. The primary
test checks both halves of the vulnerable window: before the current replacement is installed and
after it is installed while the overall reload remains active. It then proves a fresh source seek
activates the new layout after reload completion.

### Mutation and self-review

- Actual temporary mutation: removed the three reload-in-flight checks from candidate creation and
  activation. The exact primary test failed 1/1 with the old cached segment (`BUILD FAILED` in
  1 minute 14 seconds). After restoring the fence, the same exact test passed 1/1 (`BUILD SUCCESSFUL`
  in 1 minute 14 seconds).
- Removing the install-time latest-generation check is killed by the overlapping reload test: the
  delayed older parser result replaces `latest` with `older`.
- Omitting matching completion from `finally` is killed by both failure/cancellation recovery tests;
  the old valid source can never seek again.
- Removing either creation-time check permits a candidate from the new generation to carry an old
  cache; removing activation-time validation permits a candidate created before reload to mutate the
  controller afterward.
- Review confirmed controller cache mutation and callbacks now occur on Main, parsing remains off
  Main, stale completion cannot clear a newer fence, speech/manual page persistence behavior is
  unchanged, and no chapter/parser lifecycle was added.

### Round 4 full gates

Fresh complete command:

```powershell
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Result: `BUILD SUCCESSFUL` in 4 minutes 29 seconds (335 tasks: 42 executed,
293 up-to-date). Unit XML totals: 22 suites, 256 tests, 0 failures, 0 errors, 0 skipped.
`assembleDebug` and `lintDebug` both completed.

### Remaining boundary

- Device/HyperOS verification remains outside Task 4 and was not run. Per user direction, Task 4 is
  archived after this fix round and no Task 5 work was started.
