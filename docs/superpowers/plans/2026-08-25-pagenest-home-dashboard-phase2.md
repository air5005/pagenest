# PageNest Home Reading Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the empty-first bookshelf home with a real-data reading dashboard while preserving the existing shelf, search, sort, import, selection, and book-opening behavior.

**Architecture:** Add a pure dashboard calculator and immutable presentation models, then expose their result from `HomeViewModel` using the existing all-books and reading-activity flows. Render the result through stateless Compose components above the preserved all-books grid/list, with callbacks delegated to the existing home screen and ViewModel.

**Tech Stack:** Kotlin, Coroutines Flow, Java Time, Jetpack Compose Material 3, Paging Compose, Hilt, JUnit 4, Robolectric, AndroidX Compose UI Test, Gradle, GitHub Actions/Releases.

**Spec:** `docs/superpowers/specs/2026-08-25-pagenest-home-dashboard-design.md`

## Global Constraints

- Work directly on `master`; commit and push every completed task.
- Execute every behavior change in RED → GREEN → REFACTOR order.
- Reuse `ReadingActive`, `Book`, `GetAllBooksUseCase`, and `GetAllReadingActivitiesUseCase`; do not add a database or network dependency.
- The weekly target is exactly 150 minutes and weeks start on Monday.
- A reading day counts toward a streak only after its aggregated duration reaches 60,000 milliseconds.
- Recent reading contains at most three non-audiobook books with non-null `lastOpened`, sorted newest first.
- Preserve existing EPUB/TXT/MOBI/AZW3, PDF, and audiobook routing behavior.
- Preserve image-skin readability, import progress, search, sort, layout, shelf, and selection behavior.
- Only create a GitHub Release after the phase is installable and the full verification gate passes.

---

## File Structure

- Create `app/src/main/java/com/wxn/reader/presentation/home/dashboard/HomeDashboardModel.kt`: immutable dashboard and recent-book presentation types.
- Create `app/src/main/java/com/wxn/reader/presentation/home/dashboard/HomeDashboardCalculator.kt`: pure date, duration, streak, goal, and recent-book calculations.
- Create `app/src/main/java/com/wxn/reader/presentation/home/dashboard/HomeDashboardFlow.kt`: combines books and reading activities and applies a recoverable activity-error boundary.
- Create `app/src/main/java/com/wxn/reader/presentation/home/dashboard/HomeDashboardContent.kt`: stateless dashboard Compose UI and semantic test tags.
- Create `app/src/test/java/com/wxn/reader/presentation/home/dashboard/HomeDashboardCalculatorTest.kt`: deterministic unit coverage for calculation rules.
- Create `app/src/test/java/com/wxn/reader/presentation/home/dashboard/HomeDashboardFlowTest.kt`: Flow combination and failure-degradation coverage.
- Create `app/src/androidTest/java/com/wxn/reader/presentation/home/dashboard/HomeDashboardContentTest.kt`: rendered states and callback coverage.
- Modify `app/src/main/java/com/wxn/reader/presentation/home/HomeViewModel.kt`: inject existing dashboard sources and expose `dashboardState` plus open-by-id behavior.
- Modify `app/src/main/java/com/wxn/reader/presentation/home/HomeScreen.kt`: collect dashboard state, own dashboard/all-books expansion, launch directory import, and navigate on recent-book selection.
- Modify `app/src/main/java/com/wxn/reader/presentation/home/HomeShelfsPanel.kt`: place the dashboard before the preserved all-books panel without duplicating book routing.
- Modify `app/src/main/res/values/strings.xml`: Chinese-first localizable dashboard copy.
- Modify `app/build.gradle.kts`: increment the release version only after the phase passes.
- Modify `docs/TASK5_RESUME_MANUAL.md`: record the completed phase and next entry point.
- Create `docs/testing/ui-refresh-phase2.md`: verification, emulator, APK, release, and known-limit evidence.

---

### Task 1: Pure Dashboard Models and Calculator

**Files:**
- Create: `app/src/main/java/com/wxn/reader/presentation/home/dashboard/HomeDashboardModel.kt`
- Create: `app/src/main/java/com/wxn/reader/presentation/home/dashboard/HomeDashboardCalculator.kt`
- Test: `app/src/test/java/com/wxn/reader/presentation/home/dashboard/HomeDashboardCalculatorTest.kt`

**Interfaces:**
- Consumes: `List<Book>`, `List<ReadingActive>`, `Instant`, `ZoneId`, and `weeklyGoalMinutes: Int = 150`.
- Produces: `HomeDashboardCalculator.calculate(books, activities, now, zoneId, weeklyGoalMinutes): HomeDashboardModel`.
- Produces: `HomeDashboardModel(todayMinutes, streakDays, weekMinutes, weeklyGoalMinutes, weekProgress, totalBookCount, recentBooks)`.
- Produces: `RecentBookModel(id, title, author, coverImage, progressPercent, locationLabel, lastOpenedEpochMillis)`.

- [ ] **Step 1: Write the failing calculation tests**

Add JUnit tests using `ZoneId.of("Asia/Shanghai")` and fixed `Instant` values for: duplicate records today sum to minutes; yesterday keeps a streak alive; a two-day gap resets it; Monday excludes Sunday; progress caps at `1f`; recent books exclude entries for which `stringToFileType(book.fileType) == FileType.AUDIOBOOK`, omit null `lastOpened`, clamp progress, and keep only the newest three.

```kotlin
@Test
fun `today minutes aggregate duplicate records and week progress caps`() {
    val now = Instant.parse("2026-08-25T04:00:00Z")
    val activities = listOf(
        activityAt("2026-08-25T01:00:00Z", 90_000),
        activityAt("2026-08-25T02:00:00Z", 180_000),
        activityAt("2026-08-24T02:00:00Z", 20_000_000),
    )

    val result = calculator.calculate(emptyList(), activities, now, zoneId, 150)

    assertEquals(4, result.todayMinutes)
    assertEquals(150, result.weeklyGoalMinutes)
    assertEquals(1f, result.weekProgress)
}
```

- [ ] **Step 2: Run the test and verify RED**

Run: `./gradlew :app:testDebugUnitTest --tests "com.wxn.reader.presentation.home.dashboard.HomeDashboardCalculatorTest" --no-daemon`

Expected: FAIL because the dashboard model/calculator does not exist.

- [ ] **Step 3: Implement the smallest pure model and calculator**

Aggregate activities by `LocalDate`, calculate the Monday-to-now week total, calculate a streak from today or yesterday, and map/sort/filter recent books. Keep Android Context and localized strings out of the calculator.

```kotlin
class HomeDashboardCalculator {
    fun calculate(
        books: List<Book>,
        activities: List<ReadingActive>,
        now: Instant,
        zoneId: ZoneId,
        weeklyGoalMinutes: Int = DEFAULT_WEEKLY_GOAL_MINUTES,
    ): HomeDashboardModel

    companion object {
        const val DEFAULT_WEEKLY_GOAL_MINUTES = 150
        const val MINIMUM_STREAK_DAY_MILLIS = 60_000L
        const val RECENT_BOOK_LIMIT = 3
    }
}
```

- [ ] **Step 4: Run tests and refactor while GREEN**

Run the focused test command again. Expected: all `HomeDashboardCalculatorTest` tests PASS. Then run `git diff --check`.

- [ ] **Step 5: Commit and push**

```bash
git add app/src/main/java/com/wxn/reader/presentation/home/dashboard app/src/test/java/com/wxn/reader/presentation/home/dashboard
git commit -m "feat: calculate home reading dashboard"
git push origin master
```

---

### Task 2: Recoverable Dashboard Flow

**Files:**
- Create: `app/src/main/java/com/wxn/reader/presentation/home/dashboard/HomeDashboardFlow.kt`
- Test: `app/src/test/java/com/wxn/reader/presentation/home/dashboard/HomeDashboardFlowTest.kt`

**Interfaces:**
- Consumes: `Flow<List<Book>>`, `Flow<List<ReadingActive>>`, `Clock`, `ZoneId`, `HomeDashboardCalculator`.
- Produces: `observeHomeDashboard(books, activities, calculator, clock, zoneId): Flow<HomeDashboardModel>`.
- Error rule: an ordinary activity-flow exception emits an empty activity list; `CancellationException` is rethrown; book-flow failure is not swallowed.

- [ ] **Step 1: Write failing Flow tests**

Use `runTest`, `flowOf`, `flow { throw IOException("offline database read") }`, and a fixed `Clock` to verify normal combination and activity failure degradation. Add an explicit cancellation test.

```kotlin
@Test
fun `activity failure keeps books and zeroes reading metrics`() = runTest {
    val result = observeHomeDashboard(
        books = flowOf(listOf(book(id = 7, lastOpened = 10L))),
        activities = flow { throw IOException("read failed") },
        calculator = HomeDashboardCalculator(),
        clock = fixedClock,
        zoneId = zoneId,
    ).first()

    assertEquals(1, result.totalBookCount)
    assertEquals(0, result.todayMinutes)
}
```

- [ ] **Step 2: Run the test and verify RED**

Run: `./gradlew :app:testDebugUnitTest --tests "com.wxn.reader.presentation.home.dashboard.HomeDashboardFlowTest" --no-daemon`

Expected: FAIL because `observeHomeDashboard` does not exist.

- [ ] **Step 3: Implement the Flow boundary**

Apply `catch` only to the activity stream, rethrow `CancellationException`, emit `emptyList()` for ordinary exceptions, then `combine` with books and call the calculator using `clock.instant()`.

- [ ] **Step 4: Run focused and neighboring tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.wxn.reader.presentation.home.dashboard.*" --no-daemon`

Expected: calculator and Flow tests PASS. Run `git diff --check`.

- [ ] **Step 5: Commit and push**

```bash
git add app/src/main/java/com/wxn/reader/presentation/home/dashboard/HomeDashboardFlow.kt app/src/test/java/com/wxn/reader/presentation/home/dashboard/HomeDashboardFlowTest.kt
git commit -m "feat: observe dashboard data safely"
git push origin master
```

---

### Task 3: Stateless Dashboard UI

**Files:**
- Create: `app/src/main/java/com/wxn/reader/presentation/home/dashboard/HomeDashboardContent.kt`
- Create: `app/src/androidTest/java/com/wxn/reader/presentation/home/dashboard/HomeDashboardContentTest.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `HomeDashboardModel`, `expanded: Boolean`, `onRecentBookClick: (Long) -> Unit`, `onImportClick: () -> Unit`, `onAllBooksClick: () -> Unit`.
- Produces: `@Composable fun HomeDashboardContent(...)` with test tags `home_dashboard`, `reading_summary`, `recent_reading`, `empty_library`, `import_books`, and `all_books`.

- [ ] **Step 1: Write failing Compose UI tests**

Render the stateless content under `PageNestTheme`. Verify the real metric labels and one recent title, then click a recent book and assert its id. Render an empty model, assert the empty guidance, click `import_books`, and assert the callback. Click `all_books` and assert expansion callback.

```kotlin
composeRule.onNodeWithTag("reading_summary").assertIsDisplayed()
composeRule.onNodeWithText("12 分钟").assertIsDisplayed()
composeRule.onNodeWithText("继续阅读").performClick()
assertEquals(42L, openedBookId)
```

- [ ] **Step 2: Build the Android test APK and verify RED**

Run: `./gradlew :app:assembleDebugAndroidTest --no-daemon`

Expected: FAIL because `HomeDashboardContent` does not exist.

- [ ] **Step 3: Implement the minimal stateless UI**

Use `LazyColumn`, `PageNestGradientCard`, Material 3 cards, theme colors, 48dp minimum touch targets, Coil cover loading with a local placeholder, and `LinearProgressIndicator`. Put all user-facing copy in resources and expose semantics through stable tags.

- [ ] **Step 4: Verify compilation and device behavior**

Run: `./gradlew :app:assembleDebugAndroidTest :app:assembleDebug --no-daemon`.

Start the API 36 emulator, install both APKs, and run:

```text
adb shell am instrument -w -e class com.wxn.reader.presentation.home.dashboard.HomeDashboardContentTest com.air5005.pagenest.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: every dashboard content test reports `OK`. Run `git diff --check`.

- [ ] **Step 5: Commit and push**

```bash
git add app/src/main/java/com/wxn/reader/presentation/home/dashboard/HomeDashboardContent.kt app/src/androidTest/java/com/wxn/reader/presentation/home/dashboard/HomeDashboardContentTest.kt app/src/main/res/values/strings.xml
git commit -m "feat: add home dashboard interface"
git push origin master
```

---

### Task 4: Wire Dashboard into the Existing Home

**Files:**
- Modify: `app/src/main/java/com/wxn/reader/presentation/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/wxn/reader/presentation/home/HomeScreen.kt`
- Modify: `app/src/main/java/com/wxn/reader/presentation/home/HomeShelfsPanel.kt`
- Test: `app/src/test/java/com/wxn/reader/presentation/home/dashboard/HomeDashboardFlowTest.kt`

**Interfaces:**
- Consumes: `GetAllBooksUseCase`, `GetAllReadingActivitiesUseCase`, and `observeHomeDashboard(...)`.
- Produces: `val dashboardState: StateFlow<HomeDashboardModel>` on `HomeViewModel`.
- Produces: `fun openDashboardBook(bookId: Long)` that resolves the book with `GetBookByIdUseCase`, delegates to `openBook`, and publishes the route through the existing `openLastBookRoute` flow.
- `HomeShelfsPanel` receives dashboard state and callbacks instead of reaching into the calculator.

- [ ] **Step 1: Extend the failing Flow integration test**

Add a test proving a newly emitted book/activity pair updates the dashboard model and maintains newest-first order. Run it before production wiring so the expected state contract is fixed.

- [ ] **Step 2: Run focused tests and verify the new assertion RED**

Run: `./gradlew :app:testDebugUnitTest --tests "com.wxn.reader.presentation.home.dashboard.HomeDashboardFlowTest" --no-daemon`

Expected: FAIL until the Flow keeps observing both sources as specified.

- [ ] **Step 3: Implement ViewModel and screen wiring**

Inject both existing use cases, create an initially empty `dashboardState`, collect the combined Flow in `viewModelScope`, and route recent-book clicks through `openDashboardBook`. Keep `HomeDashboardContent` above the old all-books grid/list on the ebook tab. The dashboard import callback opens the existing directory confirmation dialog; the all-books callback expands or scrolls to the preserved shelf content.

- [ ] **Step 4: Run home regression and build checks**

Run:

```text
./gradlew :app:testDebugUnitTest --tests "com.wxn.reader.presentation.home.*" :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
```

Expected: home tests PASS and both APKs build. Install on the API 36 emulator and verify dashboard → recent book, dashboard → import dialog, dashboard → all books, search, sort, shelf switch, grid/list, and Back behavior.

- [ ] **Step 5: Commit and push**

```bash
git add app/src/main/java/com/wxn/reader/presentation/home/HomeViewModel.kt app/src/main/java/com/wxn/reader/presentation/home/HomeScreen.kt app/src/main/java/com/wxn/reader/presentation/home/HomeShelfsPanel.kt app/src/test/java/com/wxn/reader/presentation/home/dashboard/HomeDashboardFlowTest.kt
git commit -m "feat: make dashboard the home experience"
git push origin master
```

---

### Task 5: Phase Verification and Checkpoint Documentation

**Files:**
- Create: `docs/testing/ui-refresh-phase2.md`
- Modify: `docs/TASK5_RESUME_MANUAL.md`

**Interfaces:**
- Consumes: completed Phase 2 implementation and generated test/build reports.
- Produces: reproducible verification evidence, screenshot paths, known limitations, latest commit, and Phase 3 resume instructions.

- [ ] **Step 1: Run the complete fresh verification gate**

Run:

```text
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --no-daemon
```

Expected: `BUILD SUCCESSFUL`; record unit test counts and lint errors/warnings from generated reports. Do not reuse earlier output.

- [ ] **Step 2: Run API 36 emulator smoke tests**

Verify onboarding/relaunch, dashboard populated state, empty state, import dialog and picker return, all-books expansion, search/sort/layout, recent-book open, light theme, dark theme, force-stop/relaunch, and zero PageNest fatal exceptions. Save screenshots beneath ignored `captures/ui-refresh-phase2/`.

- [ ] **Step 3: Record evidence and next entry point**

Write exact commands/results, emulator identity, screenshot paths, APK path and SHA-256, known emulator-only issues, and any pending HyperOS 3 ARM64 checks in `docs/testing/ui-refresh-phase2.md`. Update `docs/TASK5_RESUME_MANUAL.md` so a fresh clone can resume at Phase 3 “沉浸式阅读器”.

- [ ] **Step 4: Review documentation and repository state**

Run `git diff --check`, verify no APK, screenshot, key, local settings, or temporary output is staged, and confirm the documented commit ids/paths exist.

- [ ] **Step 5: Commit and push**

```bash
git add docs/testing/ui-refresh-phase2.md docs/TASK5_RESUME_MANUAL.md
git commit -m "docs: record home dashboard checkpoint"
git push origin master
```

---

### Task 6: Versioned APK and GitHub Release

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `docs/testing/ui-refresh-phase2.md`
- Modify: `docs/TASK5_RESUME_MANUAL.md`

**Interfaces:**
- Consumes: a clean, fully verified Phase 2 checkpoint on `master`.
- Produces: incremented `versionCode`, date-based `versionName`, annotated tag, GitHub Release APK, `SHA256SUMS.txt`, and remote verification evidence.

- [ ] **Step 1: Increment version metadata**

Increase `versionCode` from 7 to 8 and set `versionName` to `1.7.260825`. Do not reuse the Phase 1 tag.

- [ ] **Step 2: Rebuild and verify the release candidate**

Run the complete verification gate again after the version change. Use Android build tools to verify the APK package is `com.air5005.pagenest`, `versionCode` is 8, and `versionName` is `1.7.260825`. Calculate SHA-256.

- [ ] **Step 3: Commit and push the version checkpoint**

```bash
git add app/build.gradle.kts
git commit -m "build: prepare pagenest 1.7.260825"
git push origin master
```

- [ ] **Step 4: Tag and publish the GitHub Release**

Create and push annotated tag `pagenest-v1.7.260825`. Let the existing release workflow publish `PageNest-pagenest-v1.7.260825-debug.apk` and `SHA256SUMS.txt`. Wait for the workflow to finish successfully.

- [ ] **Step 5: Verify remote assets and archive the result**

Download both release assets, verify the remote APK hash matches `SHA256SUMS.txt`, and inspect its embedded package/version metadata. Confirm the release is latest, non-draft, and non-prerelease. Add the release URL, workflow URL, asset size, SHA-256, and tag commit to both checkpoint documents.

- [ ] **Step 6: Commit and push final release evidence**

```bash
git add docs/testing/ui-refresh-phase2.md docs/TASK5_RESUME_MANUAL.md
git commit -m "docs: archive home dashboard release"
git push origin master
```

- [ ] **Step 7: Verify final synchronization**

Run `git status --short`, `git rev-parse HEAD`, and `git rev-parse origin/master`. Expected: clean worktree and identical local/remote commit ids.
