# PageNest Diagnostics Logging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a privacy-safe, bounded on-device diagnostics log and a system-settings screen for viewing, filtering, refreshing, and clearing recent running, warning, and error entries.

**Architecture:** Extend the existing `base` Logger with pure file-store, sanitizer, throttling, and crash-delegation units, configured by `BookApplication` against app-private storage. Expose a small app-layer repository and ViewModel to a Compose diagnostics screen, then add explicit low-volume lifecycle events to import, online acquisition, reader routing, and speech boundaries.

**Tech Stack:** Kotlin, Timber 5, Java file I/O, single-thread executor, Android Application, Hilt, StateFlow, Jetpack Compose Material 3, JUnit 4, Robolectric/Compose Android tests.

**Spec:** `docs/superpowers/specs/2026-08-26-pagenest-diagnostics-logging-design.md`

## Global Constraints

- Work directly on `master`; every completed task is committed and pushed to `origin/master`.
- Use strict red-green-refactor TDD: each production behavior must first be observed failing for the intended reason.
- Store files only under app-private `filesDir/diagnostics/`; never request storage permission or upload logs.
- Persist only explicit `RUNNING` events plus `WARNING` and `ERROR`; existing `DEBUG`, `VERBOSE`, and ordinary `INFO` remain console-only.
- Use 512 KiB per file, four files maximum, and read at most the latest 500 entries.
- Apply sanitization before throttling and storage; messages are at most 2,000 characters and stack traces at most 20 lines.
- Suppress identical sanitized level/category/message entries for 10 seconds and report the suppressed count on the next stored entry.
- The release gate must finish with zero Lint errors and must not claim HyperOS 3 ARM64 device success without that device.

---

### Task 1: Define safe diagnostics records and sanitization

**Files:**
- Modify: `base/build.gradle.kts`
- Create: `base/src/main/java/com/wxn/base/diagnostics/DiagnosticLogEntry.kt`
- Create: `base/src/main/java/com/wxn/base/diagnostics/DiagnosticLogCodec.kt`
- Create: `base/src/main/java/com/wxn/base/diagnostics/DiagnosticSanitizer.kt`
- Create: `base/src/test/java/com/wxn/base/diagnostics/DiagnosticLogCodecTest.kt`
- Create: `base/src/test/java/com/wxn/base/diagnostics/DiagnosticSanitizerTest.kt`

**Interfaces:**
- Produces: `enum class DiagnosticLevel { RUNNING, WARNING, ERROR }`.
- Produces: `data class DiagnosticLogEntry(val timestampEpochMillis: Long, val level: DiagnosticLevel, val category: String, val message: String)`.
- Produces: `DiagnosticLogCodec.encode(entry): String` and `decode(line): DiagnosticLogEntry?`; corrupt lines return `null`.
- Produces: `DiagnosticSanitizer.sanitize(message: String): String` and `sanitize(throwable: Throwable): String`.

- [x] Write codec tests proving round-trip, escaped newlines, descending timestamp comparison, and corrupt-line rejection.
- [x] Run `:base:testDebugUnitTest --tests '*DiagnosticLogCodecTest'` and observe unresolved production types.
- [x] Implement the minimal record and one-line tab-separated codec; rerun until green.
- [x] Write sanitizer tests proving API keys, bearer tokens, URL query/fragment, Windows/Unix/Android private paths, 2,000-character limit, and 20-stack-line limit are removed or truncated.
- [x] Run `:base:testDebugUnitTest --tests '*DiagnosticSanitizerTest'` and observe missing behavior.
- [x] Implement ordered sanitization and rerun both focused suites until green.
- [ ] Commit and push with `feat: define privacy-safe diagnostic entries`.

### Task 2: Implement bounded rotating storage and duplicate throttling

**Files:**
- Create: `base/src/main/java/com/wxn/base/diagnostics/DiagnosticLogStore.kt`
- Create: `base/src/main/java/com/wxn/base/diagnostics/RotatingDiagnosticLogStore.kt`
- Create: `base/src/main/java/com/wxn/base/diagnostics/DiagnosticLogThrottle.kt`
- Create: `base/src/test/java/com/wxn/base/diagnostics/RotatingDiagnosticLogStoreTest.kt`
- Create: `base/src/test/java/com/wxn/base/diagnostics/DiagnosticLogThrottleTest.kt`

**Interfaces:**
- Produces: `DiagnosticLogStore.append(entry)`, `readRecent(limit): List<DiagnosticLogEntry>`, `clear()`, `totalBytes(): Long`, and `flush()`.
- Produces: `RotatingDiagnosticLogStore(directory, maxFileBytes = 524288, maxFiles = 4)`.
- Produces: `DiagnosticLogThrottle(windowMillis = 10000).accept(entry, nowMillis): ThrottleDecision`, including suppressed duplicate count.

- [ ] Write file-store tests for newest-first reads, 500-entry limit, pre-append rotation, four-file retention, total size boundary, clear, invalid-file cleanup, corrupt-line tolerance, and concurrent appends.
- [ ] Run `:base:testDebugUnitTest --tests '*RotatingDiagnosticLogStoreTest'` and observe missing types.
- [ ] Implement synchronized append/rotate/read/clear/flush and rerun until green.
- [ ] Write throttle tests for first acceptance, duplicate suppression inside 10 seconds, distinct level/category/message acceptance, and next-entry suppression summary.
- [ ] Run `:base:testDebugUnitTest --tests '*DiagnosticLogThrottleTest'` and observe missing behavior.
- [ ] Implement bounded fingerprint state and rerun both focused suites until green.
- [ ] Commit and push with `feat: bound and throttle diagnostic files`.

### Task 3: Integrate Logger and crash delegation

**Files:**
- Create: `base/src/main/java/com/wxn/base/diagnostics/DiagnosticLogWriter.kt`
- Create: `base/src/main/java/com/wxn/base/diagnostics/DiagnosticCrashHandler.kt`
- Modify: `base/src/main/java/com/wxn/base/util/Logger.kt`
- Create: `base/src/test/java/com/wxn/base/diagnostics/DiagnosticLogWriterTest.kt`
- Create: `base/src/test/java/com/wxn/base/diagnostics/DiagnosticCrashHandlerTest.kt`
- Modify: `app/src/main/java/com/wxn/reader/BookApplication.kt`

**Interfaces:**
- Produces: `Logger.init(isDebug: Boolean, diagnosticsDirectory: File)`.
- Produces: `Logger.running(category: String, message: String)` and persists existing warning/error calls through a Timber diagnostics tree.
- Produces: `Logger.readDiagnostics(limit: Int = 500)`, `clearDiagnostics()`, `diagnosticsBytes()`, and `flushDiagnostics()`.
- Produces: `DiagnosticCrashHandler(writer, delegate)` that synchronously logs category `CRASH` before always invoking `delegate`.

- [ ] Write writer tests proving background serialization, severity mapping, sanitization-before-throttling, suppression summary, flush, and write-failure isolation.
- [ ] Run `:base:testDebugUnitTest --tests '*DiagnosticLogWriterTest'` and observe missing behavior.
- [ ] Implement a single-thread writer with a synchronous crash path; rerun until green.
- [ ] Write crash-handler tests proving log-before-delegate and delegate invocation even when logging fails.
- [ ] Run `:base:testDebugUnitTest --tests '*DiagnosticCrashHandlerTest'` and observe missing behavior.
- [ ] Implement the handler and Logger wiring; configure it in `BookApplication` and record one version-only `APP_START` event.
- [ ] Run `:base:testDebugUnitTest --tests 'com.wxn.base.diagnostics.*' :app:compileDebugKotlin` to verify the complete diagnostics core and application wiring.
- [ ] Commit and push with `feat: persist warnings errors and crash context`.

### Task 4: Add diagnostics repository, ViewModel, screen, and navigation

**Files:**
- Create: `app/src/main/java/com/air5005/pagenest/diagnostics/DiagnosticsRepository.kt`
- Create: `app/src/main/java/com/air5005/pagenest/diagnostics/DiagnosticsViewModel.kt`
- Create: `app/src/main/java/com/air5005/pagenest/diagnostics/DiagnosticsScreen.kt`
- Create: `app/src/test/java/com/air5005/pagenest/diagnostics/DiagnosticsViewModelTest.kt`
- Create: `app/src/androidTest/java/com/air5005/pagenest/diagnostics/DiagnosticsScreenTest.kt`
- Modify: `app/src/main/java/com/wxn/reader/navigation/Screens.kt`
- Modify: `app/src/main/java/com/wxn/reader/navigation/SetupNavGraph.kt`
- Modify: `app/src/main/java/com/wxn/reader/presentation/home/HomeMinePanel.kt`
- Modify: `app/src/main/res/values*/strings.xml`

**Interfaces:**
- Produces: `enum class DiagnosticsFilter { ALL, RUNNING, WARNING, ERROR }`.
- Produces: `DiagnosticsUiState(entries, filter, totalBytes, isLoading, error)` and `visibleEntries`.
- Produces: `DiagnosticsViewModel.refresh()`, `selectFilter(filter)`, and `clear()`.
- Produces: route `Screens.DiagnosticsScreen.route == "diagnostics_screen"`.

- [ ] Write ViewModel tests for initial load, newest-first state, each filter, refresh, clear, and safe load/clear failure states.
- [ ] Run `:app:testDebugUnitTest --tests '*DiagnosticsViewModelTest'` and observe missing types.
- [ ] Implement the repository and ViewModel using `Dispatchers.IO`; rerun until green.
- [ ] Write Compose tests for title, level chips, entries, empty state, storage summary, refresh, and clear confirmation.
- [ ] Run `:app:assembleDebugAndroidTest` and observe missing screen/navigation behavior.
- [ ] Implement the large-font-safe LazyColumn screen, route, and “我的” system-configuration entry; add every string key to all maintained locales.
- [ ] Rerun the focused JVM suite and Android-test APK compilation until green, then run `:app:lintDebug`.
- [ ] Commit and push with `feat: add on-device diagnostics log viewer`.

### Task 5: Add low-volume operational events at critical boundaries

**Files:**
- Modify: `app/src/main/java/com/air5005/pagenest/library/importing/BookImportService.kt`
- Modify: `app/src/main/java/com/air5005/pagenest/discovery/importing/OnlineBookImportCoordinator.kt`
- Modify: `app/src/main/java/com/air5005/pagenest/discovery/ui/DiscoveryViewModel.kt`
- Modify: `app/src/main/java/com/wxn/reader/presentation/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/air5005/pagenest/speech/settings/ReaderSpeechManager.kt`
- Modify: `app/src/main/java/com/air5005/pagenest/speech/playback/SpeechPlaybackService.kt`
- Extend the closest existing tests for each changed component.

**Interfaces:**
- Consumes: `Logger.running(category, message)` plus existing `Logger.w/e`.
- Produces stable categories: `BOOK_IMPORT`, `ONLINE_IMPORT`, `READER_ROUTE`, `SPEECH_SESSION`, and `SPEECH_SERVICE`.

- [ ] Extend existing tests with an injectable/capturable diagnostics sink where needed, asserting only state transitions and failure enums are logged and no URI, URL, book text, API key, or audio bytes are supplied.
- [ ] Run focused import/discovery/home/speech tests and observe the expected missing events.
- [ ] Add one event per lifecycle boundary, never inside byte, page, progress, recomposition, or audio-frame loops.
- [ ] Rerun focused tests; scan changed messages for paths, URLs, content, credentials, and unbounded exception text.
- [ ] Run `:app:testDebugUnitTest :base:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug`.
- [ ] Commit and push with `chore: add bounded operational diagnostics`.

### Task 6: Release gate, documentation, and GitHub APK archive

**Files:**
- Modify: `app/build.gradle.kts` to versionCode 13 and versionName `1.12.260826`.
- Create: `docs/testing/diagnostics-logging.md`.
- Modify: `docs/TASK5_RESUME_MANUAL.md`.
- Update this plan's checkboxes.

**Interfaces:**
- Produces tag `pagenest-v1.12.260826` and a GitHub Release containing `PageNest-pagenest-v1.12.260826-debug.apk` plus `SHA256SUMS.txt`.

- [ ] Bump the version and run `:base:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain`.
- [ ] Record exact test counts, Lint totals, APK sizes/hashes, package metadata, log capacity rules, privacy limits, and device-test status.
- [ ] If API 36 emulator is connected, execute diagnostics Compose tests and verify rotation/clear through the app; if HyperOS 3 ARM64 is connected, install and verify the real settings screen. Otherwise record each gate as not run/pending.
- [ ] Commit and push `release: prepare PageNest 1.12.260826`; fetch and prove local HEAD equals `origin/master`.
- [ ] Create and push annotated tag `pagenest-v1.12.260826`, wait for `.github/workflows/release-apk.yml`, then download and verify APK against `SHA256SUMS.txt` and package metadata.
- [ ] Archive remote release evidence in both checkpoint documents, commit/push it, and prove a clean `master` synchronized with `origin/master`.

## Completion boundary

The feature is complete when file logging is private, sanitized, throttled, bounded to four 512 KiB files, crash-safe, readable and clearable from the system-configuration UI, supplied with low-volume critical events, covered by red-green tests, fully gated with zero Lint errors, pushed to `master`, and archived in a checksum-verified PageNest 1.12.260826 GitHub Release. Device-only claims remain pending unless the relevant device is connected.
