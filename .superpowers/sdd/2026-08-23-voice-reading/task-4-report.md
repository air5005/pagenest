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
