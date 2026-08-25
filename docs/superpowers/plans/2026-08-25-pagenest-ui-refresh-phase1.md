# PageNest UI Refresh Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish the PageNest visual foundation, replace visible HandyReader branding, ship the selected “阅读窗口” launcher icon and Chinese onboarding, and keep the home screen alive when the bundled sample-book parser is unavailable.

**Architecture:** Add a small PageNest design-token layer below the existing `ReadTheme`, then extract onboarding content into a stateless Compose surface so it can be tested independently. Keep the existing parser and HomeViewModel architecture, but place a narrow failure boundary around optional sample-book seeding so a single native parser failure cannot cancel home initialization.

**Tech Stack:** Kotlin 2.x, Jetpack Compose Material 3, Android resources/adaptive icons, Robolectric, Compose UI Test, MockK, kotlinx-coroutines-test, Gradle, Android API 29-36.

**Spec:** `docs/superpowers/specs/2026-08-25-pagenest-ui-refresh-design.md`

## Global Constraints

- Work directly on `master`; do not create a feature branch or worktree.
- Use the brand gradient from `#18A69D` to `#397DE4`.
- The reading surface remains warm and low-saturation; brand gradients do not sit behind long-form body text.
- Preserve import, reading, skin, speech and preference behavior.
- Do not copy WeRead trademarks, illustrations or proprietary icons.
- Rethrow coroutine cancellation; convert optional sample-book parser failures into recoverable UI state.
- Write the failing test before each implementation change.
- Finish the phase with unit tests, Android test APK assembly, Lint and Debug APK assembly.
- Commit and push every completed task to `origin/master`.

## File Structure

- `app/src/main/java/com/wxn/reader/ui/theme/PageNestTokens.kt`: PageNest palette, gradients, spacing, shapes and light/dark Material color schemes.
- `app/src/main/java/com/wxn/reader/ui/theme/PageNestTheme.kt`: stateless Material theme wrapper used by previews and isolated UI tests.
- `app/src/main/java/com/wxn/reader/ui/components/PageNestGradientCard.kt`: reusable branded gradient card.
- `app/src/main/java/com/wxn/reader/presentation/gettingStarted/GettingStartedContent.kt`: stateless onboarding UI.
- `app/src/main/java/com/wxn/reader/presentation/home/PublicDomainBookSeedPolicy.kt`: failure boundary for optional bundled-book seeding.
- `app/src/main/res/drawable/ic_pagenest_launcher_background.xml`: blue-green adaptive-icon background.
- `app/src/main/res/drawable/ic_pagenest_launcher_foreground.xml`: selected white page, teal bookmark and blue completion mark.
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`: adaptive icon declaration.
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`: round adaptive icon declaration.
- `app/src/test/java/com/wxn/reader/ui/theme/PageNestTokensTest.kt`: design-token contract.
- `app/src/test/java/com/wxn/reader/branding/PageNestBrandResourcesTest.kt`: localized brand resource contract.
- `app/src/test/java/com/wxn/reader/presentation/home/PublicDomainBookSeedPolicyTest.kt`: sample-book failure and cancellation contract.
- `app/src/androidTest/java/com/wxn/reader/presentation/gettingStarted/GettingStartedContentTest.kt`: onboarding semantics and actions.

---

### Task 1: PageNest Design Tokens and Theme

**Files:**
- Create: `app/src/main/java/com/wxn/reader/ui/theme/PageNestTokens.kt`
- Create: `app/src/main/java/com/wxn/reader/ui/theme/PageNestTheme.kt`
- Create: `app/src/main/java/com/wxn/reader/ui/components/PageNestGradientCard.kt`
- Modify: `app/src/main/java/com/wxn/reader/ui/theme/ColorSchemes.kt`
- Modify: `app/src/main/java/com/wxn/reader/ui/theme/Theme.kt`
- Test: `app/src/test/java/com/wxn/reader/ui/theme/PageNestTokensTest.kt`

**Interfaces:**
- Produces: `PageNestPalette`, `PageNestSpacing`, `PageNestShapes`, `PageNestLightColorScheme`, `PageNestDarkColorScheme`.
- Produces: `@Composable fun PageNestTheme(darkTheme: Boolean, content: @Composable () -> Unit)`.
- Produces: `@Composable fun PageNestGradientCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit)`.
- Existing `ReadTheme` continues to select stored user preferences and delegates final rendering to the PageNest theme wrapper.

- [ ] **Step 1: Write the failing token contract**

```kotlin
package com.wxn.reader.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test
import androidx.compose.ui.graphics.toArgb

class PageNestTokensTest {
    @Test fun brandPaletteUsesApprovedBlueGreenValues() {
        assertEquals(0xFF18A69D.toInt(), PageNestPalette.Teal.toArgb())
        assertEquals(0xFF397DE4.toInt(), PageNestPalette.Blue.toArgb())
        assertEquals(0xFFF5F8F9.toInt(), PageNestPalette.LightBackground.toArgb())
        assertEquals(0xFFF5F1E8.toInt(), PageNestPalette.ReadingPaper.toArgb())
    }

    @Test fun touchTargetsAndCardRadiusMeetTheUiContract() {
        assertEquals(48, PageNestSpacing.MinimumTouchTarget.value.toInt())
        assertEquals(22, PageNestSpacing.LargeCardRadius.value.toInt())
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.wxn.reader.ui.theme.PageNestTokensTest --no-daemon
```

Expected: compilation fails because `PageNestPalette`, `PageNestSpacing` and `PageNestShapes` do not exist.

- [ ] **Step 3: Implement the token layer**

Create `PageNestTokens.kt` with these exact public contracts:

```kotlin
object PageNestPalette {
    val Teal = Color(0xFF18A69D)
    val Blue = Color(0xFF397DE4)
    val LightBackground = Color(0xFFF5F8F9)
    val DarkBackground = Color(0xFF101719)
    val ReadingPaper = Color(0xFFF5F1E8)
    val ReadingInk = Color(0xFF2D312E)
    val LightSurface = Color(0xFFFFFFFF)
    val DarkSurface = Color(0xFF182124)
}

object PageNestSpacing {
    val ScreenHorizontal = 16.dp
    val CardGap = 12.dp
    val MinimumTouchTarget = 48.dp
    val LargeCardRadius = 22.dp
}

object PageNestShapes {
    val LargeCard = RoundedCornerShape(PageNestSpacing.LargeCardRadius)
    val MediumCard = RoundedCornerShape(16.dp)
    val SmallControl = RoundedCornerShape(12.dp)
}

val PageNestBrandGradient = Brush.linearGradient(
    colors = listOf(PageNestPalette.Teal, PageNestPalette.Blue),
)
```

Define `PageNestLightColorScheme` and `PageNestDarkColorScheme` with Material semantic roles. The light scheme uses `LightBackground`; the dark scheme uses `DarkBackground`; both use `Teal` as primary and `Blue` as tertiary.

- [ ] **Step 4: Add the stateless theme and gradient card**

`PageNestTheme.kt`:

```kotlin
@Composable
fun PageNestTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) PageNestDarkColorScheme else PageNestLightColorScheme,
        typography = Typography,
        shapes = Shapes(
            extraLarge = PageNestShapes.LargeCard,
            large = PageNestShapes.MediumCard,
            medium = PageNestShapes.SmallControl,
        ),
        content = content,
    )
}
```

`PageNestGradientCard.kt` uses `PageNestBrandGradient`, `PageNestShapes.LargeCard`, `PageNestSpacing.ScreenHorizontal` and a `Column` content slot. It must not read a ViewModel.

- [ ] **Step 5: Delegate the default app theme**

Make `LightColorScheme` and `DarkColorScheme` in `ColorSchemes.kt` aliases for the new schemes so existing stored theme names remain valid:

```kotlin
val LightColorScheme = PageNestLightColorScheme
val DarkColorScheme = PageNestDarkColorScheme
```

Keep the optional teal, sepia, parchment and other user-selected reading schemes. In `ReadTheme`, preserve preference selection and pass the selected `colorScheme`, existing `Typography` and PageNest shapes to `MaterialTheme`.

- [ ] **Step 6: Verify GREEN and run theme regression tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.wxn.reader.ui.theme.PageNestTokensTest --no-daemon
.\gradlew.bat :app:testDebugUnitTest --tests com.air5005.pagenest.skin.ReaderBackgroundResolverTest --no-daemon
```

Expected: both commands pass with zero failures.

- [ ] **Step 7: Commit and push**

```powershell
git add app/src/main/java/com/wxn/reader/ui app/src/test/java/com/wxn/reader/ui
git commit -m "feat: add PageNest design system"
git push origin master
```

---

### Task 2: PageNest Brand Resources and Launcher Icon

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Create: `app/src/main/res/drawable/ic_pagenest_launcher_background.xml`
- Create: `app/src/main/res/drawable/ic_pagenest_launcher_foreground.xml`
- Create: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Create: `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
- Modify: `app/src/main/res/drawable/splash_icon.xml`
- Test: `app/src/test/java/com/wxn/reader/branding/PageNestBrandResourcesTest.kt`

**Interfaces:**
- Produces localized `R.string.app_name`, `R.string.welcome_to_uread`, onboarding directory copy and skip copy.
- Produces adaptive icons at `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round`.
- Existing manifest and onboarding resource references remain stable.

- [ ] **Step 1: Write the failing localized resource test**

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PageNestBrandResourcesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun text(@StringRes id: Int, language: String): String {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language))
        return context.createConfigurationContext(configuration).getString(id)
    }

    @Test fun appNameAndWelcomeCopyUsePageNestBrand() {
        assertEquals("页栖", text(R.string.app_name, "zh-CN"))
        assertEquals("欢迎来到页栖", text(R.string.welcome_to_uread, "zh-CN"))
        assertEquals("Welcome to PageNest", text(R.string.welcome_to_uread, "en-US"))
    }

    @Test fun launcherIconIsResolvableOnSupportedAndroid() {
        assertNotNull(context.getDrawable(R.mipmap.ic_launcher))
    }
}
```

- [ ] **Step 2: Run the brand test and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.wxn.reader.branding.PageNestBrandResourcesTest --no-daemon
```

Expected: the Chinese strings still contain `随阅`, the English welcome still contains `HandyReader`, or the new test does not compile until imports are added.

- [ ] **Step 3: Replace visible brand strings**

Use these exact values:

```xml
<!-- values/strings.xml -->
<string name="app_name">页栖</string>
<string name="welcome_to_uread">Welcome to PageNest</string>

<!-- values-zh/strings.xml -->
<string name="app_name">页栖</string>
<string name="welcome_to_uread">欢迎来到页栖</string>
<string name="to_get_started_please_select_a_directory_where_you_would_like_to_load_your_books">选择一个书籍目录，页栖会在本机整理你的阅读空间。</string>
<string name="you_can_edit_this_later">稍后可在“我的”中修改</string>
<string name="select_directory">选择书籍目录</string>
<string name="skip">暂时跳过</string>
```

Search all user-visible resources and semantics for `HandyReader`, `Handy Reader`, `随阅` and `URead`; replace only branding references, not historical upstream documentation.

- [ ] **Step 4: Implement the selected “阅读窗口” adaptive icon**

Create a rounded blue-green gradient background drawable. Create a foreground vector with:

- a centered white page/window shape;
- three teal reading lines;
- a teal bookmark descending from the top edge;
- a blue circular completion mark with a white check;
- no text and no details thinner than `4dp` in the 108dp viewport.

Wire both adaptive-icon XML files to the new background and foreground. Point `splash_icon.xml` at the same foreground motif without the completion mark leaving the safe zone.

- [ ] **Step 5: Verify resources and packaged icon**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.wxn.reader.branding.PageNestBrandResourcesTest --no-daemon
.\gradlew.bat :app:processDebugResources :app:assembleDebug --no-daemon
```

Expected: tests pass and Android resource packaging completes without adaptive-icon or vector errors.

- [ ] **Step 6: Commit and push**

```powershell
git add app/src/main/res app/src/test/java/com/wxn/reader/branding
git commit -m "feat: apply PageNest brand and launcher icon"
git push origin master
```

---

### Task 3: Stateless Chinese Onboarding Surface

**Files:**
- Create: `app/src/main/java/com/wxn/reader/presentation/gettingStarted/GettingStartedContent.kt`
- Modify: `app/src/main/java/com/wxn/reader/presentation/gettingStarted/GettingStarted.kt`
- Modify: `app/src/main/java/com/wxn/reader/presentation/gettingStarted/components/ActionButtons.kt`
- Modify: `app/build.gradle.kts`
- Test: `app/src/androidTest/java/com/wxn/reader/presentation/gettingStarted/GettingStartedContentTest.kt`

**Interfaces:**
- Produces: `@Composable fun GettingStartedContent(buttonsEnabled: Boolean, onSelectDirectory: () -> Unit, onSkip: () -> Unit)`.
- `GettingStartedScreen` retains ViewModel, launcher, dialog and navigation responsibilities.
- Test semantics: `pagenest_onboarding`, `pagenest_select_directory`, `pagenest_skip`.

- [ ] **Step 1: Add Compose test dependencies and write the failing UI test**

Add:

```kotlin
androidTestImplementation(platform(libs.androidx.compose.bom))
androidTestImplementation("androidx.compose.ui:ui-test-junit4")
debugImplementation("androidx.compose.ui:ui-test-manifest")
```

Write:

```kotlin
class GettingStartedContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showsPageNestBrandAndExposesBothActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var selected = false
        var skipped = false
        compose.setContent {
            PageNestTheme(darkTheme = false) {
                GettingStartedContent(
                    buttonsEnabled = true,
                    onSelectDirectory = { selected = true },
                    onSkip = { skipped = true },
                )
            }
        }

        compose.onNodeWithTag("pagenest_onboarding").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.welcome_to_uread)).assertIsDisplayed()
        compose.onNodeWithTag("pagenest_select_directory").performClick()
        compose.onNodeWithTag("pagenest_skip").performClick()
        assertTrue(selected)
        assertTrue(skipped)
    }
}
```

- [ ] **Step 2: Assemble the Android test and verify RED**

```powershell
.\gradlew.bat :app:assembleDebugAndroidTest --no-daemon
```

Expected: compilation fails because `GettingStartedContent` does not exist.

- [ ] **Step 3: Extract and implement the stateless onboarding content**

The content uses:

- the new launcher foreground at 112dp;
- `MaterialTheme.typography.headlineMedium` for the localized welcome;
- centered localized directory explanation;
- one full-width primary “选择书籍目录” button;
- one bottom text action “暂时跳过”;
- PageNest spacing and large-card shape;
- the three exact test tags above.

`GettingStartedScreen` keeps `rememberLauncherForActivityResult`, `StorageAccessDialog`, preference updates and navigation. It passes callbacks into the stateless content; no ViewModel is read from `GettingStartedContent`.

- [ ] **Step 4: Run the UI test on the API 36 emulator**

```powershell
adb devices -l
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.wxn.reader.presentation.gettingStarted.GettingStartedContentTest --no-daemon
```

Expected: one test passes. If no emulator is connected, start `pagenest_api36` first; do not mark this step complete from assembly alone.

- [ ] **Step 5: Verify onboarding resource and unit regressions**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.wxn.reader.branding.PageNestBrandResourcesTest --no-daemon
.\gradlew.bat :app:assembleDebugAndroidTest --no-daemon
```

Expected: both commands pass.

- [ ] **Step 6: Commit and push**

```powershell
git add app/build.gradle.kts app/src/main/java/com/wxn/reader/presentation/gettingStarted app/src/androidTest/java/com/wxn/reader/presentation/gettingStarted
git commit -m "feat: redesign PageNest onboarding"
git push origin master
```

---

### Task 4: Recoverable Optional Sample-Book Seeding

**Files:**
- Create: `app/src/main/java/com/wxn/reader/presentation/home/PublicDomainBookSeedPolicy.kt`
- Modify: `app/src/main/java/com/wxn/reader/presentation/home/HomeViewModel.kt`
- Test: `app/src/test/java/com/wxn/reader/presentation/home/PublicDomainBookSeedPolicyTest.kt`
- Test: `app/src/test/java/com/wxn/reader/presentation/home/HomeViewModelImportFlowTest.kt`

**Interfaces:**
- Produces: `sealed interface PublicDomainBookSeedResult { data object Seeded; data object Failed }`.
- Produces: `suspend fun seedPublicDomainBookSafely(seed: suspend () -> Unit): PublicDomainBookSeedResult`.
- Cancellation is rethrown. `LinkageError` and ordinary non-cancellation failures return `Failed`.

- [ ] **Step 1: Write failure-boundary tests**

```kotlin
class PublicDomainBookSeedPolicyTest {
    @Test fun nativeLibraryFailureIsRecoverable() = runTest {
        val result = seedPublicDomainBookSafely {
            throw UnsatisfiedLinkError("libappmobi.so not found")
        }
        assertEquals(PublicDomainBookSeedResult.Failed, result)
    }

    @Test fun ordinaryParserFailureIsRecoverable() = runTest {
        val result = seedPublicDomainBookSafely { error("broken sample") }
        assertEquals(PublicDomainBookSeedResult.Failed, result)
    }

    @Test(expected = CancellationException::class)
    fun cancellationStillPropagates() = runTest {
        seedPublicDomainBookSafely { throw CancellationException("stop") }
    }

    @Test fun successfulSeedReportsSeeded() = runTest {
        assertEquals(
            PublicDomainBookSeedResult.Seeded,
            seedPublicDomainBookSafely { },
        )
    }
}
```

- [ ] **Step 2: Run and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.wxn.reader.presentation.home.PublicDomainBookSeedPolicyTest --no-daemon
```

Expected: compilation fails because the result type and function do not exist.

- [ ] **Step 3: Implement the minimal failure boundary**

```kotlin
sealed interface PublicDomainBookSeedResult {
    data object Seeded : PublicDomainBookSeedResult
    data object Failed : PublicDomainBookSeedResult
}

suspend fun seedPublicDomainBookSafely(
    seed: suspend () -> Unit,
): PublicDomainBookSeedResult = try {
    seed()
    PublicDomainBookSeedResult.Seeded
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: LinkageError) {
    PublicDomainBookSeedResult.Failed
} catch (failure: Exception) {
    PublicDomainBookSeedResult.Failed
}
```

Do not catch arbitrary `Throwable`; VM-fatal errors remain fatal.

- [ ] **Step 4: Apply the boundary inside HomeViewModel**

Replace the direct optional seed launch with:

```kotlin
launch {
    when (seedPublicDomainBookSafely { addPublicDomainBooksIfNeeded() }) {
        PublicDomainBookSeedResult.Seeded -> Unit
        PublicDomainBookSeedResult.Failed -> Logger.e(
            "HomeViewModel: optional public-domain sample could not be loaded",
        )
    }
}
```

The failure is logged without the native path and does not show a blocking dialog. The empty bookshelf remains usable and its normal import CTA explains the next action.

- [ ] **Step 5: Add a HomeViewModel regression test**

Extend the existing Robolectric fixture with a test preference flow whose `scanDirectories` is empty, a parser that throws `UnsatisfiedLinkError`, and relaxed book/shelf dependencies. Advance the test dispatcher and assert:

```kotlin
assertTrue(viewModel.viewModelScope.coroutineContext[Job]?.isActive == true)
assertEquals(ImportProgressState.Idle, viewModel.importProgressState.value)
```

The test must fail against the pre-boundary HomeViewModel and pass after Step 4.

- [ ] **Step 6: Run focused and neighboring tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.wxn.reader.presentation.home.PublicDomainBookSeedPolicyTest --tests com.wxn.reader.presentation.home.HomeViewModelImportFlowTest --no-daemon
```

Expected: all focused tests pass with zero failures.

- [ ] **Step 7: Verify the original x86_64 symptom**

On `emulator-5554`:

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm clear com.air5005.pagenest
adb shell monkey -p com.air5005.pagenest -c android.intent.category.LAUNCHER 1
```

Tap “暂时跳过”, launch the app again, and verify `dumpsys window windows` keeps `com.air5005.pagenest` focused. `adb logcat -d AndroidRuntime:E *:S` must contain no new `FATAL EXCEPTION` for PageNest.

- [ ] **Step 8: Commit and push**

```powershell
git add app/src/main/java/com/wxn/reader/presentation/home app/src/test/java/com/wxn/reader/presentation/home
git commit -m "fix: isolate optional sample book failures"
git push origin master
```

---

### Task 5: Phase 1 Verification and Checkpoint

**Files:**
- Modify: `docs/TASK5_RESUME_MANUAL.md`
- Create: `docs/testing/ui-refresh-phase1.md`

**Interfaces:**
- Produces a restart-safe checkpoint with commit IDs, commands, emulator evidence, known limitations and the next phase entry point.

- [ ] **Step 1: Run the full phase gate**

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --no-daemon
```

Expected: Gradle exits 0, unit tests have zero failures/errors, Lint has zero errors, and both Debug APKs exist.

- [ ] **Step 2: Run API 36 emulator smoke coverage**

Verify in order:

1. Fresh install displays PageNest icon and localized onboarding.
2. Directory button opens the system picker and Back returns safely.
3. Skip enters the empty/home UI without a crash.
4. Relaunch returns to home instead of onboarding.
5. Light and dark system modes keep text readable.

Capture screenshots to `captures/ui-refresh-phase1/`; the directory remains ignored and evidence paths are recorded in the test document.

- [ ] **Step 3: Write the checkpoint documents**

`docs/testing/ui-refresh-phase1.md` records:

- exact commit under test;
- device/API/ABI;
- unit-test total and failure count;
- Lint error count;
- APK path and SHA-256;
- smoke-test results;
- ARM64-only parsing limitation and pending HyperOS 3 evidence.

Update `docs/TASK5_RESUME_MANUAL.md` so the next entry point is “UI Refresh Phase 2: 阅读仪表盘首页”.

- [ ] **Step 4: Verify clean documentation and commit**

```powershell
git diff --check
git status --short
git add docs/TASK5_RESUME_MANUAL.md docs/testing/ui-refresh-phase1.md
git commit -m "docs: checkpoint PageNest UI refresh phase 1"
git push origin master
git fetch origin master
git rev-parse HEAD
git rev-parse origin/master
```

Expected: local and remote commit IDs match and `git status --porcelain` is empty.

- [ ] **Step 5: Decide release eligibility**

Do not publish a new GitHub Release solely for theme scaffolding. Publish only if the installed APK contains the new icon/onboarding and the x86/API 36 home crash regression is fixed. If eligible, bump `versionCode` and `versionName`, push a `pagenest-v*` tag, wait for the release workflow, download the remote APK and verify it against `SHA256SUMS.txt` before reporting completion.
