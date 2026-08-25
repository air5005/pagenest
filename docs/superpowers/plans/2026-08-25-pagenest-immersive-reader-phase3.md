# PageNest Immersive Reader Phase 3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade the reflowable book reader to a distraction-free interface with auto-hiding controls and a compact speech player while preserving the existing renderer and background playback.

**Architecture:** Add a pure Kotlin reducer for reader chrome visibility, overlay blocking, timeout generations, and speech-panel state. `MainReadViewModel` owns that reducer state, while focused stateless Compose components render the top chrome, action dock, progress panel, and speech mini-player around the unchanged `PageView` engine.

**Tech Stack:** Kotlin, Coroutines `StateFlow`, Jetpack Compose Material 3, Media3-backed speech state, JUnit 4, Compose UI tests, Gradle 8.11.1 with Microsoft JDK 17, Android API 36 emulator.

**Spec:** `docs/superpowers/specs/2026-08-25-pagenest-immersive-reader-design.md`

## Global Constraints

- Work directly on `master`, committing and pushing every completed task.
- Follow RED-GREEN-REFACTOR: no production behavior before a failing focused test.
- Keep `PageView`, `PageViewController`, book parsers, databases, saved reading positions, and the foreground speech service protocol unchanged.
- Limit this phase to the reflowable main reader; do not merge the PDF renderer into it.
- Preserve current font, layout, background, page-turn, notes, highlights, bookmarks, and book-details capabilities.
- Hide controls after 4,000 milliseconds only when no blocking overlay is open.
- Keep the speech mini-player visible for every non-idle speech session, including paused, preparing, completed, and error states where recovery remains available.
- Use localized string resources for every new visible label and content description.
- Run Gradle with `JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot`.

---

## File Map

- Create `app/src/main/java/com/wxn/reader/presentation/mainReader/chrome/ReaderChromeState.kt`: immutable state, events, and pure reducer.
- Create `app/src/test/java/com/wxn/reader/presentation/mainReader/chrome/ReaderChromeReducerTest.kt`: reducer and timeout-generation contract.
- Modify `app/src/main/java/com/wxn/reader/presentation/mainReader/MainReadViewModel.kt`: own chrome state and translate existing page callbacks into events.
- Create `app/src/main/java/com/wxn/reader/presentation/mainReader/chrome/ReaderChrome.kt`: stateless top bar, quick action dock, progress panel, and speech mini-player.
- Create `app/src/androidTest/java/com/wxn/reader/presentation/mainReader/chrome/ReaderChromeTest.kt`: semantics and callback tests.
- Modify `app/src/main/java/com/wxn/reader/presentation/mainReader/ReaderView.kt`: compose the new chrome around the existing renderer and connect the timeout.
- Modify `app/src/main/java/com/wxn/reader/presentation/mainReader/MainReadScreen.kt`: make system bars follow the actual chrome state.
- Modify `app/src/main/java/com/air5005/pagenest/speech/ui/SpeechControlSheet.kt`: allow the full sheet to be dismissed from the immersive reader.
- Modify every existing `app/src/main/res/values*/strings.xml`: localized reader labels.
- Create `docs/testing/ui-refresh-phase3.md`: verification and release evidence.
- Modify `docs/TASK5_RESUME_MANUAL.md`: record the next resumable checkpoint.

---

### Task 1: Pure reader chrome reducer

**Files:**
- Create: `app/src/main/java/com/wxn/reader/presentation/mainReader/chrome/ReaderChromeState.kt`
- Test: `app/src/test/java/com/wxn/reader/presentation/mainReader/chrome/ReaderChromeReducerTest.kt`

**Interfaces:**
- Consumes: no Android or Compose types.
- Produces: `ReaderChromeState`, `ReaderChromeEvent`, `ReaderChromeReducer.reduce(state, event)`, `ReaderChromeReducer.shouldScheduleAutoHide(state)`, and `ReaderChromeReducer.AUTO_HIDE_MILLIS`.

- [ ] **Step 1: Write failing reducer tests**

```kotlin
class ReaderChromeReducerTest {
    private val initial = ReaderChromeState()

    @Test fun `reader starts immersive and center tap toggles controls`() {
        val shown = ReaderChromeReducer.reduce(initial, ReaderChromeEvent.CenterTapped)
        assertTrue(shown.controlsVisible)
        assertEquals(1L, shown.interactionGeneration)
        assertFalse(ReaderChromeReducer.reduce(shown, ReaderChromeEvent.CenterTapped).controlsVisible)
    }

    @Test fun `stale timeout cannot hide newly interacted controls`() {
        val shown = ReaderChromeReducer.reduce(initial, ReaderChromeEvent.CenterTapped)
        val refreshed = ReaderChromeReducer.reduce(shown, ReaderChromeEvent.Interacted)
        assertTrue(ReaderChromeReducer.reduce(refreshed, ReaderChromeEvent.AutoHide(1L)).controlsVisible)
        assertFalse(ReaderChromeReducer.reduce(refreshed, ReaderChromeEvent.AutoHide(2L)).controlsVisible)
    }

    @Test fun `overlay blocks timeout until it closes`() {
        val shown = ReaderChromeReducer.reduce(initial, ReaderChromeEvent.CenterTapped)
        val blocked = ReaderChromeReducer.reduce(shown, ReaderChromeEvent.BlockingOverlayChanged(true))
        assertFalse(ReaderChromeReducer.shouldScheduleAutoHide(blocked))
        assertTrue(ReaderChromeReducer.reduce(blocked, ReaderChromeEvent.AutoHide(blocked.interactionGeneration)).controlsVisible)
        val unblocked = ReaderChromeReducer.reduce(blocked, ReaderChromeEvent.BlockingOverlayChanged(false))
        assertTrue(ReaderChromeReducer.shouldScheduleAutoHide(unblocked))
        assertTrue(unblocked.interactionGeneration > blocked.interactionGeneration)
    }

    @Test fun `speech session keeps mini player separate from expanded panel`() {
        val active = ReaderChromeReducer.reduce(initial, ReaderChromeEvent.SpeechSessionChanged(true))
        assertTrue(active.speechMiniPlayerVisible)
        val expanded = ReaderChromeReducer.reduce(active, ReaderChromeEvent.SpeechPanelChanged(true))
        assertTrue(expanded.speechPanelExpanded)
        assertFalse(expanded.speechMiniPlayerVisible)
        val stopped = ReaderChromeReducer.reduce(expanded, ReaderChromeEvent.SpeechSessionChanged(false))
        assertFalse(stopped.speechPanelExpanded)
        assertFalse(stopped.speechMiniPlayerVisible)
    }
}
```

- [ ] **Step 2: Run the focused test and confirm RED**

Run:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "com.wxn.reader.presentation.mainReader.chrome.ReaderChromeReducerTest" --no-daemon
```

Expected: compilation fails because the chrome state and reducer do not exist.

- [ ] **Step 3: Implement the minimal immutable reducer**

```kotlin
data class ReaderChromeState(
    val controlsVisible: Boolean = false,
    val blockingOverlayVisible: Boolean = false,
    val speechSessionActive: Boolean = false,
    val speechPanelExpanded: Boolean = false,
    val interactionGeneration: Long = 0L,
) {
    val speechMiniPlayerVisible: Boolean
        get() = speechSessionActive && !speechPanelExpanded
}

sealed interface ReaderChromeEvent {
    data object CenterTapped : ReaderChromeEvent
    data object Interacted : ReaderChromeEvent
    data class BlockingOverlayChanged(val visible: Boolean) : ReaderChromeEvent
    data class SpeechSessionChanged(val active: Boolean) : ReaderChromeEvent
    data class SpeechPanelChanged(val expanded: Boolean) : ReaderChromeEvent
    data class AutoHide(val generation: Long) : ReaderChromeEvent
}

object ReaderChromeReducer {
    const val AUTO_HIDE_MILLIS = 4_000L
    fun reduce(state: ReaderChromeState, event: ReaderChromeEvent): ReaderChromeState = when (event) {
        ReaderChromeEvent.CenterTapped -> state.copy(
            controlsVisible = !state.controlsVisible,
            interactionGeneration = state.interactionGeneration + 1,
        )
        ReaderChromeEvent.Interacted -> state.copy(interactionGeneration = state.interactionGeneration + 1)
        is ReaderChromeEvent.BlockingOverlayChanged -> state.copy(
            blockingOverlayVisible = event.visible,
            interactionGeneration = state.interactionGeneration + 1,
        )
        is ReaderChromeEvent.SpeechSessionChanged -> state.copy(
            speechSessionActive = event.active,
            speechPanelExpanded = state.speechPanelExpanded && event.active,
        )
        is ReaderChromeEvent.SpeechPanelChanged -> state.copy(
            speechPanelExpanded = event.expanded && state.speechSessionActive,
            interactionGeneration = state.interactionGeneration + 1,
        )
        is ReaderChromeEvent.AutoHide -> if (
            event.generation == state.interactionGeneration &&
            shouldScheduleAutoHide(state)
        ) state.copy(controlsVisible = false) else state
    }

    fun shouldScheduleAutoHide(state: ReaderChromeState): Boolean =
        state.controlsVisible && !state.blockingOverlayVisible && !state.speechPanelExpanded
}
```

- [ ] **Step 4: Run the focused tests and confirm GREEN**

Expected: all `ReaderChromeReducerTest` tests pass.

- [ ] **Step 5: Commit and push**

```powershell
git add app/src/main/java/com/wxn/reader/presentation/mainReader/chrome/ReaderChromeState.kt app/src/test/java/com/wxn/reader/presentation/mainReader/chrome/ReaderChromeReducerTest.kt
git commit -m "feat: model immersive reader chrome"
git push origin master
```

---

### Task 2: ViewModel chrome ownership

**Files:**
- Modify: `app/src/main/java/com/wxn/reader/presentation/mainReader/MainReadViewModel.kt:140-142, 296-304, 382-384, 849-891`
- Test: `app/src/test/java/com/wxn/reader/presentation/mainReader/chrome/ReaderChromeReducerTest.kt`

**Interfaces:**
- Consumes: Task 1 reducer.
- Produces: `readerChromeState: StateFlow<ReaderChromeState>`, `setBlockingOverlayVisible(Boolean)`, `setSpeechPanelExpanded(Boolean)`, `onReaderInteraction()`, and `onChromeAutoHide(Long)`.

- [ ] **Step 1: Add a failing reducer contract for explicit visibility synchronization**

Add `ControlsVisibilityChanged(val visible: Boolean)` and this test:

```kotlin
@Test fun `explicit visibility update is idempotent and refreshes a shown menu`() {
    val shown = ReaderChromeReducer.reduce(initial, ReaderChromeEvent.ControlsVisibilityChanged(true))
    val refreshed = ReaderChromeReducer.reduce(shown, ReaderChromeEvent.ControlsVisibilityChanged(true))
    assertTrue(refreshed.controlsVisible)
    assertTrue(refreshed.interactionGeneration > shown.interactionGeneration)
}
```

- [ ] **Step 2: Run the focused test and confirm RED**

Expected: `ControlsVisibilityChanged` is unresolved.

- [ ] **Step 3: Implement explicit visibility and wire ViewModel methods**

Replace the independent menu boolean with reducer-backed state:

```kotlin
private val _readerChromeState = MutableStateFlow(ReaderChromeState())
val readerChromeState: StateFlow<ReaderChromeState> = _readerChromeState.asStateFlow()
val showMenu: StateFlow<Boolean> = _readerChromeState
    .map { it.controlsVisible }
    .stateIn(viewModelScope, SharingStarted.Eagerly, false)

private fun dispatchChrome(event: ReaderChromeEvent) {
    _readerChromeState.update { ReaderChromeReducer.reduce(it, event) }
}

override fun onCenterClick() = dispatchChrome(ReaderChromeEvent.CenterTapped)
fun onReaderInteraction() = dispatchChrome(ReaderChromeEvent.Interacted)
fun setToolbarsVisible(visible: Boolean) = dispatchChrome(ReaderChromeEvent.ControlsVisibilityChanged(visible))
fun setBlockingOverlayVisible(visible: Boolean) = dispatchChrome(ReaderChromeEvent.BlockingOverlayChanged(visible))
fun setSpeechPanelExpanded(expanded: Boolean) = dispatchChrome(ReaderChromeEvent.SpeechPanelChanged(expanded))
fun onChromeAutoHide(generation: Long) = dispatchChrome(ReaderChromeEvent.AutoHide(generation))
```

In the existing speech snapshot collector, dispatch `SpeechSessionChanged(snapshot.playbackState !is SpeechPlaybackState.Idle)` after updating `_isTtsOn`. Keep `onToolbarsVisibilityChanged()` as a compatibility wrapper that dispatches `CenterTapped` until all callers migrate.

- [ ] **Step 4: Run reducer tests and compile app tests**

Run the focused reducer test, then:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "com.wxn.reader.presentation.mainReader.chrome.*" --no-daemon
```

Expected: reducer tests pass and `MainReadViewModel` compiles.

- [ ] **Step 5: Commit and push**

```powershell
git add app/src/main/java/com/wxn/reader/presentation/mainReader/chrome/ReaderChromeState.kt app/src/main/java/com/wxn/reader/presentation/mainReader/MainReadViewModel.kt app/src/test/java/com/wxn/reader/presentation/mainReader/chrome/ReaderChromeReducerTest.kt
git commit -m "feat: coordinate immersive reader state"
git push origin master
```

---

### Task 3: Stateless immersive controls

**Files:**
- Create: `app/src/main/java/com/wxn/reader/presentation/mainReader/chrome/ReaderChrome.kt`
- Create: `app/src/androidTest/java/com/wxn/reader/presentation/mainReader/chrome/ReaderChromeTest.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `ReaderChromeState`, `SpeechControlUiState`, book/chapter text, progress, and callbacks.
- Produces: `ReaderChrome(...)` and `SpeechMiniPlayer(...)` stateless composables with stable test tags `reader_top_chrome`, `reader_action_dock`, `reader_progress_panel`, and `speech_mini_player`.

- [ ] **Step 1: Write failing Compose tests**

```kotlin
@Test fun hiddenChromeDoesNotExposeControls() {
    rule.setContent { ReaderChromePreview(state = ReaderChromeState()) }
    rule.onNodeWithTag("reader_action_dock").assertDoesNotExist()
}

@Test fun visibleChromeShowsFourPrimaryActions() {
    rule.setContent { ReaderChromePreview(state = ReaderChromeState(controlsVisible = true)) }
    listOf("目录", "进度", "听书", "显示").forEach { label ->
        rule.onNodeWithText(label).assertIsDisplayed()
    }
}

@Test fun activeSpeechShowsMiniPlayerAndInvokesPause() {
    var paused = false
    rule.setContent {
        ReaderChromePreview(
            state = ReaderChromeState(speechSessionActive = true),
            playback = SpeechPlaybackState.Playing(segment()),
            onPauseSpeech = { paused = true },
        )
    }
    rule.onNodeWithTag("speech_mini_player").assertIsDisplayed()
    rule.onNodeWithContentDescription("暂停朗读").performClick()
    assertTrue(paused)
}
```

- [ ] **Step 2: Run only `ReaderChromeTest` and confirm RED**

Run:

```powershell
./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.wxn.reader.presentation.mainReader.chrome.ReaderChromeTest --no-daemon
```

Expected: test source compilation fails because `ReaderChrome` is missing.

- [ ] **Step 3: Implement Material 3 chrome components**

Implement a rounded translucent top surface, a four-action bottom dock, an optional progress card, and a compact speech card. The public composable accepts callbacks only; it does not access a ViewModel or navigation controller. Use `AnimatedVisibility`, `Surface`, `IconButton`, `Slider`, `Modifier.testTag`, `stringResource`, and theme colors. Route every tap through `onInteraction` before its action callback.

Required signature:

```kotlin
@Composable
fun ReaderChrome(
    state: ReaderChromeState,
    bookTitle: String,
    chapterTitle: String,
    progression: Double,
    isBookmarked: Boolean,
    speech: SpeechControlUiState,
    progressExpanded: Boolean,
    onBack: () -> Unit,
    onBookmark: () -> Unit,
    onMore: () -> Unit,
    onChapters: () -> Unit,
    onProgressToggle: () -> Unit,
    onProgressChange: (Double) -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onSpeech: () -> Unit,
    onDisplay: () -> Unit,
    onPlaySpeech: () -> Unit,
    onPauseSpeech: () -> Unit,
    onPreviousSpeech: () -> Unit,
    onNextSpeech: () -> Unit,
    onStopSpeech: () -> Unit,
    onExpandSpeech: () -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Add Chinese base strings for the four actions, more, current progress, previous/next page, expand speech, and stop speech. Translations for the other locale folders are completed in Task 5 before Lint.

- [ ] **Step 4: Run `ReaderChromeTest` and confirm GREEN**

Expected: all chrome semantics and callback tests pass on the API 36 emulator.

- [ ] **Step 5: Commit and push**

```powershell
git add app/src/main/java/com/wxn/reader/presentation/mainReader/chrome/ReaderChrome.kt app/src/androidTest/java/com/wxn/reader/presentation/mainReader/chrome/ReaderChromeTest.kt app/src/main/res/values/strings.xml
git commit -m "feat: add immersive reader controls"
git push origin master
```

---

### Task 4: Wire existing reader features into the new chrome

**Files:**
- Modify: `app/src/main/java/com/wxn/reader/presentation/mainReader/ReaderView.kt:86-310`
- Modify: `app/src/main/java/com/wxn/reader/presentation/mainReader/MainReadScreen.kt:35-80`
- Modify: `app/src/main/java/com/air5005/pagenest/speech/ui/SpeechControlSheet.kt:40-85`
- Create: `app/src/androidTest/java/com/air5005/pagenest/speech/ui/SpeechControlSheetDismissTest.kt`

**Interfaces:**
- Consumes: Tasks 1-3 state and composable APIs plus existing ViewModel reader actions.
- Produces: end-to-end immersive behavior for the existing reflowable reader.

- [ ] **Step 1: Add a failing Compose test for dismissing the full speech panel**

```kotlin
@Test fun closeButtonDismissesExpandedSpeechPanel() {
    var dismissed = false
    rule.setContent {
        SpeechControlSheet(
            state = speechState(),
            onPlay = {}, onPause = {}, onStop = {}, onPrevious = {}, onNext = {},
            onRateChange = {}, onPitchChange = {}, onTimerChange = {},
            onDismiss = { dismissed = true },
        )
    }
    rule.onNodeWithContentDescription("收起语音设置").performClick()
    assertTrue(dismissed)
}
```

- [ ] **Step 2: Run the focused Compose test and confirm RED**

Expected: test source compilation fails because `SpeechControlSheet` has no `onDismiss` parameter.

- [ ] **Step 3: Replace old toolbars and always-expanded speech sheet**

In `ReaderView`:

- collect `readerChromeState`, `readProgression`, current chapter, and speech snapshot;
- compute whether any existing drawer, dialog, settings sheet, text toolbar, external-link dialog, or expanded speech sheet blocks timeout;
- send that value through `setBlockingOverlayVisible` from `LaunchedEffect`;
- launch `delay(ReaderChromeReducer.AUTO_HIDE_MILLIS)` keyed by the interaction generation, then call `onChromeAutoHide(generation)`;
- remove calls to `TopToolbar` and `BottomToolbar` and render `ReaderChrome` instead;
- map directory, progress, speech, display, bookmark, previous/next page, and existing speech callbacks to their current implementation;
- keep notes, highlights, bookmarks, details, and the four legacy settings reachable through a compact overflow sheet;
- render `SpeechControlSheet` only when `speechPanelExpanded` is true and dismiss it through `setSpeechPanelExpanded(false)`.

In `MainReadScreen`, collect `readerChromeState` and call:

```kotlin
SetFullScreen(
    context,
    showSystemBars = chrome.controlsVisible || chrome.blockingOverlayVisible,
)
```

Delete the unused local toolbar visibility state.

Extend `SpeechControlSheet` with `onDismiss: () -> Unit` and a close icon with localized content description; update all call sites.

- [ ] **Step 4: Run JVM tests, compile Android tests, and manually exercise the reader**

Run:

```powershell
./gradlew.bat :app:testDebugUnitTest :app:assembleDebugAndroidTest --no-daemon
```

Then install the debug APK on API 36, open a sample book, and verify central tap, timeout, directory, progress, display, speech mini-player, full speech panel, background playback, and Back behavior without a fatal exception.

- [ ] **Step 5: Commit and push**

```powershell
git add app/src/main/java/com/wxn/reader/presentation/mainReader app/src/main/java/com/air5005/pagenest/speech/ui/SpeechControlSheet.kt app/src/androidTest/java/com/air5005/pagenest/speech/ui/SpeechControlSheetDismissTest.kt
git commit -m "feat: activate immersive reading experience"
git push origin master
```

---

### Task 5: Localization, visual QA, and full verification

**Files:**
- Modify: `app/src/main/res/values-ar/strings.xml`
- Modify: `app/src/main/res/values-de/strings.xml`
- Modify: `app/src/main/res/values-es/strings.xml`
- Modify: `app/src/main/res/values-fr/strings.xml`
- Modify: `app/src/main/res/values-hi/strings.xml`
- Modify: `app/src/main/res/values-ja/strings.xml`
- Modify: `app/src/main/res/values-pt/strings.xml`
- Modify: `app/src/main/res/values-ru/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Create: `docs/testing/ui-refresh-phase3.md`
- Modify: `docs/TASK5_RESUME_MANUAL.md`

**Interfaces:**
- Consumes: completed immersive reader.
- Produces: lint-clean localized copy, screenshots, reproducible verification evidence, and a restart checkpoint.

- [ ] **Step 1: Run Lint before translations and capture RED**

Run `./gradlew.bat :app:lintDebug --no-daemon`.

Expected: `MissingTranslation` errors identify every newly added base string.

- [ ] **Step 2: Add translations to every existing locale**

Translate every new reader label into all nine locale files. Preserve formatting placeholders exactly, keep action labels short enough for a four-item dock, and do not suppress `MissingTranslation` or add a Lint baseline.

- [ ] **Step 3: Run the complete verification gate**

```powershell
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --no-daemon
```

Expected: build success, zero unit-test failures, zero Lint errors, and debug APK plus test APK generated.

- [ ] **Step 4: Run API 36 visual and interaction acceptance**

Run only `ReaderChromeTest`, then install the current debug APK. Capture light and dark screenshots with controls hidden, controls visible, and speech mini-player visible. Confirm readable contrast, no clipped four-action labels, correct system-bar synchronization, 4-second auto-hide, preserved page turning, and no fatal exception after relaunch.

- [ ] **Step 5: Record evidence, commit, and push**

Document exact commands, test totals, Lint totals, emulator/API, screenshot paths, known non-blocking warnings, and the next task. Then:

```powershell
git add app/src/main/res docs/testing/ui-refresh-phase3.md docs/TASK5_RESUME_MANUAL.md
git commit -m "docs: record immersive reader checkpoint"
git push origin master
```

---

### Task 6: Version and GitHub Release

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `docs/testing/ui-refresh-phase3.md`
- Modify: `docs/TASK5_RESUME_MANUAL.md`

**Interfaces:**
- Consumes: verified Phase 3 build.
- Produces: incremented package version, annotated tag, GitHub Release, APK, checksum file, and final clean checkpoint.

- [ ] **Step 1: Set version 1.8.260825 and rerun the complete gate**

Set `versionCode = 9` and `versionName = "1.8.260825"`. Run the complete Task 5 gate again on the exact versioned tree.

- [ ] **Step 2: Commit and push the version**

```powershell
git add app/build.gradle.kts
git commit -m "build: prepare pagenest 1.8.260825"
git push origin master
```

- [ ] **Step 3: Tag and wait for release workflow success**

Create annotated tag `pagenest-v1.8.260825`, push it, monitor the matching GitHub Actions run to completion, and stop to diagnose any failed job rather than publishing an unverified local artifact.

- [ ] **Step 4: Verify the remote release artifact**

Download the APK and `SHA256SUMS.txt` from the GitHub Release into an ignored build verification directory. Confirm the checksum matches and inspect the downloaded APK for the expected package name, version code, version name, min SDK, and target SDK.

- [ ] **Step 5: Archive release evidence and finish cleanly**

Update both checkpoint documents with commit, tag, workflow URL, release URL, asset name, size, SHA-256, package metadata, and next phase. Commit and push the documentation, fetch remote state, and verify:

```powershell
git status --porcelain=v1
git rev-parse HEAD
git rev-parse origin/master
```

Expected: empty status and identical local/remote commit IDs.

---

## Self-Review

- Spec coverage: default immersion, central tap, four primary actions, progress, auto-hide generation safety, overlay blocking, system bars, mini speech controls, full speech settings, background playback preservation, legacy feature access, localization, emulator QA, and Release archival each map to Tasks 1-6.
- Scope: PDF rendering remains explicitly outside this phase; no database, parser, pagination, or service-protocol changes are planned.
- Completeness scan: every implementation step names the concrete behavior, command, interface, or release version it needs.
- Type consistency: `ReaderChromeState`, `ReaderChromeEvent`, reducer methods, ViewModel methods, composable callbacks, and test tags use the same names throughout.
