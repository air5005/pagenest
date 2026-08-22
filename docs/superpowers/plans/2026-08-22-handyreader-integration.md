# HandyReader Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Import the pinned HandyReader source into PageNest and produce a tested, offline Debug APK that can import and read DRM-free EPUB, TXT, PDF, MOBI, and AZW3 files on the target HyperOS 3 / Android 16 phone.

**Architecture:** Keep PageNest's Git history and import HandyReader commit `48dcb3f8b8e1b27f8e228af0eed26a5311308170` as a traceable source snapshot. Establish a reproducible Android 36 build first, then add a small import boundary that validates format and protection status, copies accepted files into app-private storage, hashes them for deduplication, and delegates metadata parsing to the imported parser modules.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Room, Hilt, Coroutines, Android Storage Access Framework, C++/JNI, libmobi, PDFBox Android, Gradle, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-08-22-handyreader-integration-design.md`

## Global Constraints

- Source baseline is exactly HandyReader commit `48dcb3f8b8e1b27f8e228af0eed26a5311308170`.
- PageNest remains GPLv3 and preserves all upstream and third-party notices.
- Application ID is `com.air5005.pagenest`; display name is `页栖`.
- Android baseline is `minSdk 29`, `targetSdk 36`, and `compileSdk 36`.
- The first release is offline and must not require Firebase, Google Services, analytics, a backend, or release signing secrets.
- Imported books are copied into app-private storage and addressed by that private copy.
- Supported milestone formats are DRM-free EPUB, TXT, PDF, MOBI, and AZW3.
- Protected or encrypted publications are rejected; no DRM key entry, removal, or bypass path is compiled into the application.
- Every PageNest behavior change follows RED → GREEN → REFACTOR.
- Every commit message is written as a focused Conventional Commit (`chore:`, `build:`, `feat:`, `test:`, or `docs:`) describing the completed stage; generated or vague messages are not used.
- Every task ends with its listed commit followed immediately by `git push origin main`; do not start the next task until the push succeeds and `git status -sb` shows the local branch synchronized with `origin/main`.
- Final verification requires `test`, `lint`, `assembleDebug`, APK inspection, and HyperOS 3 device smoke testing.

After every task's commit step, run this mandatory stage gate:

```powershell
git push origin main
git status -sb
```

Expected: push exits 0 and status does not report that `main` is ahead of `origin/main`. A failed push blocks the next task.

## File Structure

The snapshot introduces the upstream modules `app/`, `base/`, `bookparser/`, `bookread/`, `mobi/`, `jp2forandroid/`, and `text2speech/`. PageNest-specific integration code is kept under `app/src/main/java/com/air5005/pagenest/library/importing/`:

- `SupportedBookFormat.kt`: canonical five-format allowlist and extension normalization.
- `BookProtectionInspector.kt`: format-independent protection verdict interface.
- `DefaultBookProtectionInspector.kt`: EPUB/PDF/MOBI protection checks.
- `PrivateBookStore.kt`: atomic copy, SHA-256 naming, duplicate detection, and cleanup.
- `BookImportService.kt`: validate → inspect → copy → parse → persist orchestration.
- `HandyReaderImportAdapters.kt`: adapters from the new boundary to `FileParser` and `InsertBookUseCase`.
- `ImportResult.kt`: stable success and rejection results used by UI.

Tests mirror these files under `app/src/test/java/com/air5005/pagenest/library/importing/`. Build/provenance files stay at the root: `LICENSE`, `UPSTREAM.md`, `.gitignore`, `settings.gradle.kts`, `build.gradle.kts`, and `gradle/libs.versions.toml`.

---

### Task 1: Import the pinned source snapshot with provenance

**Files:**
- Create: `UPSTREAM.md`
- Create: `docs/upstream/HandyReader-README.md`
- Create: `LICENSE`
- Create: `app/**`, `base/**`, `bookparser/**`, `bookread/**`, `mobi/**`, `jp2forandroid/**`, `text2speech/**`
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradlew`, `gradlew.bat`, `gradle/**`
- Preserve: `README.md`, `docs/DEVELOPMENT.md`, `docs/superpowers/**`

**Interfaces:**
- Consumes: HandyReader Git commit `48dcb3f8b8e1b27f8e228af0eed26a5311308170`.
- Produces: a traceable source tree with the imported Gradle modules and an `handyreader-upstream` Git remote.

- [ ] **Step 1: Verify both repositories before copying**

Run:

```powershell
git -C D:\pagenest status --short
git -C C:\Users\Administrator\Documents\Codex\2026-08-22\oxiang-aaaa\work\HandyReader-audit rev-parse HEAD
```

Expected: PageNest is clean and upstream prints `48dcb3f8b8e1b27f8e228af0eed26a5311308170`.

- [ ] **Step 2: Copy only the approved snapshot paths**

Copy the seven modules, Gradle wrapper, root Gradle files, and upstream `LICENSE`. Do not copy `.git`, upstream build outputs, signing files, local properties, or upstream root README over PageNest's README. Save the upstream README as `docs/upstream/HandyReader-README.md`.

- [ ] **Step 3: Add exact provenance text**

Create `UPSTREAM.md` with:

```markdown
# Upstream source

PageNest incorporates source from HandyReader:

- Repository: https://github.com/EucWang/HandyReader
- Imported commit: 48dcb3f8b8e1b27f8e228af0eed26a5311308170
- Import date: 2026-08-22
- Upstream license: GNU General Public License v3.0

PageNest preserves upstream and third-party notices and records later
upstream synchronizations as separate commits. PageNest does not implement
or enable DRM circumvention.
```

- [ ] **Step 4: Add the upstream remote and verify exclusions**

Run:

```powershell
git remote add handyreader-upstream https://github.com/EucWang/HandyReader.git
git fetch handyreader-upstream --tags
git remote -v
rg --files -g 'key.properties' -g 'google-services.json' -g 'local.properties' -g '*.jks' -g '*.keystore'
```

Expected: the remote is listed twice; the final search has no output.

- [ ] **Step 5: Commit the immutable import snapshot**

```powershell
git add LICENSE UPSTREAM.md docs/upstream app base bookparser bookread mobi jp2forandroid text2speech settings.gradle.kts build.gradle.kts gradle.properties gradlew gradlew.bat gradle
git commit -m "chore: import HandyReader source baseline"
```

### Task 2: Establish a reproducible offline Android 36 build

**Files:**
- Create: `.gitignore`
- Modify: `app/build.gradle.kts`
- Modify: `build.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Modify: `mobi/build.gradle.kts`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `docs/DEVELOPMENT.md`

**Interfaces:**
- Consumes: imported Gradle modules from Task 1.
- Produces: `:app:assembleDebug` with application ID `com.air5005.pagenest` and no private config dependency.

- [ ] **Step 1: Capture the expected failing build**

Run:

```powershell
.\gradlew.bat help --stacktrace
```

Expected: FAIL during configuration because root `key.properties` is absent, or at the next unresolved Firebase/SDK blocker after that file check is removed.

- [ ] **Step 2: Add repository exclusions**

Create `.gitignore` containing:

```gitignore
.gradle/
.idea/
**/build/
local.properties
key.properties
*.jks
*.keystore
google-services.json
captures/
.externalNativeBuild/
.cxx/
```

- [ ] **Step 3: Remove offline build blockers**

In `app/build.gradle.kts`:

- remove `google-services` and Crashlytics plugins;
- remove unconditional `FileInputStream(key.properties)` configuration;
- remove Firebase BOM, Analytics, Crashlytics NDK, and Play Review dependencies;
- set `applicationId = "com.air5005.pagenest"`;
- set `minSdk = 29`, `targetSdk = 36`, and keep `compileSdk = 36`;
- keep Debug on the default Android debug keystore;
- make Release signing conditional on a local, ignored signing file.

Remove the unused Firebase plugin declarations and library aliases from the root build and version catalog. Set the app name resource to `页栖`.

- [ ] **Step 4: Lock native tools and install them**

Set `ndkVersion = "29.0.13599879"` in `mobi/build.gradle.kts` and keep CMake `3.22.1`. Install both through Android Studio SDK Manager or:

```powershell
$sdkRoot = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$sdkManager = Join-Path $sdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'
& $sdkManager --sdk_root=$sdkRoot 'ndk;29.0.13599879' 'cmake;3.22.1'
```

Record these exact versions in `docs/DEVELOPMENT.md`.

- [ ] **Step 5: Verify the build turns green**

```powershell
.\gradlew.bat help
.\gradlew.bat :app:assembleDebug
```

Expected: both commands exit 0 and create `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 6: Verify identity and offline configuration**

```powershell
rg -n 'com\.google\.firebase|google-services|crashlytics|play-review' --glob '*.kts' --glob '*.toml'
rg -n 'applicationId = "com\.air5005\.pagenest"|targetSdk = 36|minSdk = 29|compileSdk = 36' app/build.gradle.kts
```

Expected: first search has no active dependency declarations; second search reports all four values.

- [ ] **Step 7: Commit the build baseline**

```powershell
git add .gitignore app/build.gradle.kts build.gradle.kts gradle/libs.versions.toml mobi/build.gradle.kts app/src/main/res/values/strings.xml docs/DEVELOPMENT.md
git commit -m "build: establish offline Android 36 baseline"
```

### Task 3: Define and test the five-format allowlist

**Files:**
- Create: `app/src/main/java/com/air5005/pagenest/library/importing/SupportedBookFormat.kt`
- Create: `app/src/test/java/com/air5005/pagenest/library/importing/SupportedBookFormatTest.kt`

**Interfaces:**
- Produces: `SupportedBookFormat.fromFileName(fileName: String): SupportedBookFormat?`.

- [ ] **Step 1: Write the failing format tests**

```kotlin
class SupportedBookFormatTest {
    @Test fun recognizesMilestoneFormatsCaseInsensitively() {
        assertEquals(SupportedBookFormat.EPUB, SupportedBookFormat.fromFileName("Book.EPUB"))
        assertEquals(SupportedBookFormat.TXT, SupportedBookFormat.fromFileName("notes.txt"))
        assertEquals(SupportedBookFormat.PDF, SupportedBookFormat.fromFileName("paper.Pdf"))
        assertEquals(SupportedBookFormat.MOBI, SupportedBookFormat.fromFileName("novel.mobi"))
        assertEquals(SupportedBookFormat.AZW3, SupportedBookFormat.fromFileName("kindle.azw3"))
    }

    @Test fun rejectsMissingOrUnsupportedExtensions() {
        assertNull(SupportedBookFormat.fromFileName("README"))
        assertNull(SupportedBookFormat.fromFileName("archive.zip"))
    }
}
```

- [ ] **Step 2: Run the test and confirm RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*SupportedBookFormatTest'
```

Expected: FAIL because `SupportedBookFormat` does not exist.

- [ ] **Step 3: Add the minimal format enum**

```kotlin
enum class SupportedBookFormat(val extension: String) {
    EPUB("epub"), TXT("txt"), PDF("pdf"), MOBI("mobi"), AZW3("azw3");

    companion object {
        fun fromFileName(fileName: String): SupportedBookFormat? {
            val extension = fileName.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { it.extension == extension }
        }
    }
}
```

- [ ] **Step 4: Run GREEN and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*SupportedBookFormatTest'
git add app/src/main/java/com/air5005/pagenest/library/importing/SupportedBookFormat.kt app/src/test/java/com/air5005/pagenest/library/importing/SupportedBookFormatTest.kt
git commit -m "feat: define supported local book formats"
```

### Task 4: Reject protected publications and remove compiled DRM tooling

**Files:**
- Create: `app/src/main/java/com/air5005/pagenest/library/importing/BookProtectionInspector.kt`
- Create: `app/src/main/java/com/air5005/pagenest/library/importing/DefaultBookProtectionInspector.kt`
- Create: `app/src/test/java/com/air5005/pagenest/library/importing/DefaultBookProtectionInspectorTest.kt`
- Modify: `mobi/src/main/cpp/CMakeLists.txt`
- Modify: `mobi/src/main/cpp/libmobi/CMakeLists.txt`
- Modify: `mobi/src/main/cpp/libmobi/src/CMakeLists.txt`

**Interfaces:**
- Consumes: `SupportedBookFormat`.
- Produces: `BookProtectionInspector.inspect(file: File, format: SupportedBookFormat): ProtectionVerdict`.

- [ ] **Step 1: Write failing policy tests with injected probes**

```kotlin
class DefaultBookProtectionInspectorTest {
    @Test fun rejectsEncryptedMobiAndPdf() {
        val inspector = DefaultBookProtectionInspector(
            mobiEncrypted = { true },
            pdfEncrypted = { true },
            epubProtected = { false }
        )
        assertEquals(ProtectionVerdict.PROTECTED, inspector.inspect(File("a.mobi"), SupportedBookFormat.MOBI))
        assertEquals(ProtectionVerdict.PROTECTED, inspector.inspect(File("a.pdf"), SupportedBookFormat.PDF))
    }

    @Test fun allowsPlainTextAndUnprotectedEpub() {
        val inspector = DefaultBookProtectionInspector(
            mobiEncrypted = { false },
            pdfEncrypted = { false },
            epubProtected = { false }
        )
        assertEquals(ProtectionVerdict.CLEAR, inspector.inspect(File("a.txt"), SupportedBookFormat.TXT))
        assertEquals(ProtectionVerdict.CLEAR, inspector.inspect(File("a.epub"), SupportedBookFormat.EPUB))
    }
}
```

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*DefaultBookProtectionInspectorTest'
```

Expected: FAIL because the inspector types do not exist.

- [ ] **Step 3: Implement the policy boundary**

```kotlin
enum class ProtectionVerdict { CLEAR, PROTECTED, UNREADABLE }

fun interface BookProtectionInspector {
    fun inspect(file: File, format: SupportedBookFormat): ProtectionVerdict
}

class DefaultBookProtectionInspector(
    private val mobiEncrypted: (File) -> Boolean,
    private val pdfEncrypted: (File) -> Boolean,
    private val epubProtected: (File) -> Boolean,
) : BookProtectionInspector {
    override fun inspect(file: File, format: SupportedBookFormat): ProtectionVerdict = try {
        val protected = when (format) {
            SupportedBookFormat.MOBI, SupportedBookFormat.AZW3 -> mobiEncrypted(file)
            SupportedBookFormat.PDF -> pdfEncrypted(file)
            SupportedBookFormat.EPUB -> epubProtected(file)
            SupportedBookFormat.TXT -> false
        }
        if (protected) ProtectionVerdict.PROTECTED else ProtectionVerdict.CLEAR
    } catch (_: Exception) {
        ProtectionVerdict.UNREADABLE
    }
}
```

Production probes use `MetaInfo.isEncrypted` for MOBI/AZW3, `PDDocument.isEncrypted` for PDF, and reject EPUB encryption except IDPF/Adobe font-obfuscation algorithms. Presence of `META-INF/license.lcpl` is protected.

- [ ] **Step 4: Disable DRM manipulation in native builds**

Remove `util/mobi/mobidrm.c`, `util/mobi/mobidrm.h`, `util/mobi/mobitool.c`, and `util/mobi/mobitool.h` from the application library source list. Set `USE_ENCRYPTION` default to `OFF` and restore the `if(USE_ENCRYPTION)` guard around libmobi encryption source files. Keep `mobi_is_encrypted()` metadata detection available.

- [ ] **Step 5: Run tests and native build**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*DefaultBookProtectionInspectorTest'
.\gradlew.bat :mobi:assembleDebug :app:assembleDebug
```

Expected: PASS; the CMake command line does not compile `mobidrm.c`, `mobitool.c`, or libmobi encryption sources.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main app/src/test mobi/src/main/cpp/CMakeLists.txt mobi/src/main/cpp/libmobi/CMakeLists.txt mobi/src/main/cpp/libmobi/src/CMakeLists.txt
git commit -m "feat: reject protected books without DRM tooling"
```

### Task 5: Copy books atomically into private storage and deduplicate by SHA-256

**Files:**
- Create: `app/src/main/java/com/air5005/pagenest/library/importing/PrivateBookStore.kt`
- Create: `app/src/test/java/com/air5005/pagenest/library/importing/PrivateBookStoreTest.kt`

**Interfaces:**
- Produces: `PrivateBookStore.store(input: InputStream, originalName: String): StoredBook` and `StoredBook(file: File, sha256: String, wasExisting: Boolean)`.

- [ ] **Step 1: Write failing atomic-copy tests**

Test that identical bytes imported under two names resolve to one SHA-256-named file, the second result has `wasExisting == true`, and a throwing input stream leaves neither `.part` files nor final files.

```kotlin
@Test fun duplicateContentUsesOnePrivateCopy() {
    val root = temporaryFolder.newFolder("books")
    val store = PrivateBookStore(root)
    val first = store.store("hello".byteInputStream(), "one.epub")
    val second = store.store("hello".byteInputStream(), "two.epub")
    assertEquals(first.file, second.file)
    assertFalse(first.wasExisting)
    assertTrue(second.wasExisting)
    assertEquals(1, root.listFiles()!!.count { !it.name.endsWith(".part") })
}
```

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*PrivateBookStoreTest'
```

Expected: FAIL because `PrivateBookStore` does not exist.

- [ ] **Step 3: Implement streaming hash and atomic rename**

Write to `<uuid>.part` while updating `MessageDigest.getInstance("SHA-256")`, then rename to `<sha256>.<normalized-extension>`. Delete the partial file in `catch` and `finally`; if the final file already exists, delete the partial file and return `wasExisting = true`.

- [ ] **Step 4: Run GREEN and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*PrivateBookStoreTest'
git add app/src/main/java/com/air5005/pagenest/library/importing/PrivateBookStore.kt app/src/test/java/com/air5005/pagenest/library/importing/PrivateBookStoreTest.kt
git commit -m "feat: add atomic private book storage"
```

### Task 6: Orchestrate validated imports with stable results

**Files:**
- Create: `app/src/main/java/com/air5005/pagenest/library/importing/ImportResult.kt`
- Create: `app/src/main/java/com/air5005/pagenest/library/importing/BookImportService.kt`
- Create: `app/src/test/java/com/air5005/pagenest/library/importing/BookImportServiceTest.kt`

**Interfaces:**
- Consumes: `SupportedBookFormat`, `BookProtectionInspector`, `PrivateBookStore`.
- Produces: `BookImportService.execute(request: ImportRequest): ImportResult`.
- Produces: `ImportRequest(displayName: String, openInput: () -> InputStream)`.
- Produces: `BookMetadataParser.parse(file: File, format: SupportedBookFormat): Book?`.
- Produces: `BookImportCatalog.findBySha256(sha256: String): Long?` and `insert(book: Book, sha256: String): Long`.

- [ ] **Step 1: Define failing orchestration tests**

Cover these exact outcomes: unsupported filename never opens input; protected file is deleted and returns `Rejected(PROTECTED)`; parser failure deletes a newly created copy; duplicate hash returns the existing book ID; success persists the parsed book with the private file URI.

```kotlin
sealed interface ImportResult {
    data class Imported(val bookId: Long) : ImportResult
    data class Duplicate(val bookId: Long) : ImportResult
    data class Rejected(val reason: ImportRejection) : ImportResult
}

enum class ImportRejection { UNSUPPORTED_FORMAT, PROTECTED, UNREADABLE, PARSE_FAILED, STORAGE_FAILED }

data class ImportRequest(
    val displayName: String,
    val openInput: () -> InputStream,
)

fun interface BookMetadataParser {
    suspend fun parse(file: File, format: SupportedBookFormat): Book?
}

interface BookImportCatalog {
    suspend fun findBySha256(sha256: String): Long?
    suspend fun insert(book: Book, sha256: String): Long
}
```

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*BookImportServiceTest'
```

Expected: FAIL because orchestration types do not exist.

- [ ] **Step 3: Implement the minimal service**

Use this order without side effects before validation:

```text
normalize format → open input → private atomic copy → protection inspection
→ lookup SHA-256 → metadata parse → catalog insert → Imported(bookId)
```

If protection or parsing fails, remove only a newly created private copy. Never delete a pre-existing duplicate. Convert expected failures to `ImportResult`; let coroutine cancellation propagate.

- [ ] **Step 4: Run GREEN and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*BookImportServiceTest'
git add app/src/main/java/com/air5005/pagenest/library/importing app/src/test/java/com/air5005/pagenest/library/importing
git commit -m "feat: orchestrate safe local book imports"
```

### Task 7: Adapt HandyReader parser/database APIs and wire the import UI

**Files:**
- Create: `app/src/main/java/com/air5005/pagenest/library/importing/HandyReaderImportAdapters.kt`
- Create: `app/src/test/java/com/air5005/pagenest/library/importing/HandyReaderImportAdaptersTest.kt`
- Modify: `app/src/main/java/com/wxn/reader/di/AppModule.kt`
- Modify: `app/src/main/java/com/wxn/reader/presentation/home/HomeViewModel.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: existing `FileParser`, `InsertBookUseCase`, `GetBookUrisUseCase`, and the Task 6 service.
- Produces: `HomeViewModel.importBooks(uris: List<Uri>)` and localized import results.

- [ ] **Step 1: Write failing adapter tests**

Verify the metadata adapter passes a `CachedFile` backed by the private file to `FileParser`, overwrites `Book.filePath` with `Uri.fromFile(privateFile).toString()`, and the catalog adapter returns the ID produced by `InsertBookUseCase`.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*HandyReaderImportAdaptersTest'
```

- [ ] **Step 3: Implement adapters and Hilt providers**

Keep Android `ContentResolver` access in an `ImportRequest` adapter and keep the core service testable with `InputStream`. Provide the private root as `File(context.filesDir, "books")`. Do not request broad storage permissions.

- [ ] **Step 4: Replace direct scan insertion with the service**

Change `HomeViewModel` so selected URIs flow through `BookImportService`. Map results to Chinese strings:

```text
Imported    → 已导入《书名》
Duplicate   → 书籍已在书架中
PROTECTED   → 不支持受 DRM 保护的书籍
UNSUPPORTED → 暂不支持此文件格式
UNREADABLE  → 无法读取所选文件
PARSE_FAILED→ 无法解析这本书
STORAGE_FAILED → 存储空间不足或复制失败
```

- [ ] **Step 5: Run tests and build**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*HandyReaderImportAdaptersTest' --tests '*BookImportServiceTest'
.\gradlew.bat :app:assembleDebug
```

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/air5005/pagenest/library/importing app/src/main/java/com/wxn/reader/di/AppModule.kt app/src/main/java/com/wxn/reader/presentation/home/HomeViewModel.kt app/src/main/res/values/strings.xml app/src/test
git commit -m "feat: connect private imports to the bookshelf"
```

### Task 8: Characterize reading-progress persistence

**Files:**
- Create: `app/src/test/java/com/wxn/reader/domain/use_case/reading_progress/SetReadingProgressUseCaseTest.kt`
- Modify: `app/src/main/java/com/wxn/reader/domain/use_case/reading_progress/SetReadingProgressUseCase.kt`

**Interfaces:**
- Consumes: `BooksRepository.setReadingProgress()` and `setReadingStatus()`.
- Produces: stable locator-to-percentage/status behavior.

- [ ] **Step 1: Write failing boundary tests**

Use a fake `BooksRepository` and assert:

```kotlin
// totalProgression 0.0   -> 0%, status unchanged
// totalProgression 0.03  -> 3%, IN_PROGRESS
// totalProgression 0.99  -> 99%, FINISHED
// malformed JSON         -> 0%, status unchanged
```

Also assert the original locator string is stored unchanged.

- [ ] **Step 2: Run RED against any uncovered behavior**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*SetReadingProgressUseCaseTest'
```

Expected: at least one boundary assertion fails until the use case is made explicit and testable.

- [ ] **Step 3: Make the smallest implementation adjustment**

Extract `internal fun progressionPercent(locatorJson: String): Float`, preserve the thresholds `> 2f` and `>= 99f`, and keep malformed locators at `0f`.

- [ ] **Step 4: Run GREEN and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*SetReadingProgressUseCaseTest'
git add app/src/main/java/com/wxn/reader/domain/use_case/reading_progress/SetReadingProgressUseCase.kt app/src/test/java/com/wxn/reader/domain/use_case/reading_progress/SetReadingProgressUseCaseTest.kt
git commit -m "test: characterize reading progress persistence"
```

### Task 9: Add legal test fixtures and complete automated verification

**Files:**
- Create: `app/src/test/resources/books/README.md`
- Create: `app/src/androidTest/assets/books/*`
- Create: `docs/TESTING.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: all import and reader components from Tasks 1–8.
- Produces: a documented five-format smoke suite and final Debug APK.

- [ ] **Step 1: Add redistributable fixtures**

Create a self-authored UTF-8 HTML source containing a title, two chapters, Chinese text, and one generated PNG. Install Calibre from its official Windows package and generate the four packaged formats; copy the source text directly for TXT:

```powershell
winget install --id KovidGoyal.Calibre --exact --accept-package-agreements --accept-source-agreements
$ebookConvert = 'C:\Program Files\Calibre2\ebook-convert.exe'
& $ebookConvert app\src\test\resources\books\source\sample.html app\src\androidTest\assets\books\sample.epub
& $ebookConvert app\src\test\resources\books\source\sample.html app\src\androidTest\assets\books\sample.pdf
& $ebookConvert app\src\test\resources\books\source\sample.html app\src\androidTest\assets\books\sample.mobi
& $ebookConvert app\src\test\resources\books\source\sample.html app\src\androidTest\assets\books\sample.azw3
Copy-Item -LiteralPath app\src\test\resources\books\source\sample.txt -Destination app\src\androidTest\assets\books\sample.txt
Get-FileHash app\src\androidTest\assets\books\* -Algorithm SHA256
```

`app/src/test/resources/books/README.md` records that the fixture text and image are PageNest-authored CC0 test material, the commands above, Calibre's version, each SHA-256, and the intended assertion. Do not add commercial books.

- [ ] **Step 2: Add instrumentation smoke coverage**

For each format, copy the asset through the same import service, assert `Imported`, reopen the stored book, save progress, recreate the repository/database, and assert the progress remains. Add a synthetic protected-MOBI metadata fixture that produces `Rejected(PROTECTED)` without including protected book content.

- [ ] **Step 3: Run the complete verification suite**

```powershell
.\gradlew.bat test
.\gradlew.bat lint
.\gradlew.bat assembleDebug
```

Expected: all commands exit 0.

- [ ] **Step 4: Inspect APK identity, ABI, and unwanted services**

```powershell
$apk = 'app\build\outputs\apk\debug\app-debug.apk'
$aapt = Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools\36.0.0\aapt.exe'
& $aapt dump badging $apk | Select-String 'package:|application-label|native-code'
& $aapt dump xmltree $apk AndroidManifest.xml | Select-String 'firebase|crashlytics|analytics'
```

Expected: package `com.air5005.pagenest`, label `页栖`, `arm64-v8a` native code present, and no Firebase/Crashlytics/Analytics components.

- [ ] **Step 5: Document test commands and current limitations**

`docs/TESTING.md` must contain desktop commands, device prerequisites, five-format checklist, DRM rejection expectation, fixture provenance policy, and the output APK path. Update README status to state that PageNest is based on HandyReader under GPLv3.

- [ ] **Step 6: Commit automated verification assets**

```powershell
git add app/src/test/resources app/src/androidTest/assets docs/TESTING.md README.md
git commit -m "test: add local reader verification suite"
```

### Task 10: Install and verify on the HyperOS 3 target phone

**Files:**
- Modify: `docs/TESTING.md`
- Create: `docs/test-results/2026-08-22-hyperos3-smoke.md`

**Interfaces:**
- Consumes: `app/build/outputs/apk/debug/app-debug.apk` and the physical Android 16 / HyperOS 3 device.
- Produces: recorded device evidence for the five-format acceptance criteria.

- [ ] **Step 1: Confirm the target device**

```powershell
adb devices -l
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.mi.os.version.name
```

Expected: one authorized device, Android `16`, SDK `36`, and HyperOS 3 identity.

- [ ] **Step 2: Install a clean Debug build**

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell monkey -p com.air5005.pagenest -c android.intent.category.LAUNCHER 1
```

Expected: install success and PageNest opens without a fatal exception.

- [ ] **Step 3: Execute the manual five-format checklist**

For EPUB, TXT, PDF, MOBI, and AZW3: import, open, turn at least three pages, exit, reopen, and verify restored position. Restart PageNest and verify all five shelf entries remain. Attempt the synthetic protected fixture and verify rejection with no shelf entry or temporary file.

- [ ] **Step 4: Capture diagnostics and results**

```powershell
adb logcat -c
adb logcat -d -v threadtime | Select-String 'FATAL EXCEPTION|com.air5005.pagenest'
```

Record device build, APK SHA-256, each format result, progress restoration, DRM rejection, and any reproducible limitation in `docs/test-results/2026-08-22-hyperos3-smoke.md`.

- [ ] **Step 5: Run final verification and commit evidence**

```powershell
.\gradlew.bat test lint assembleDebug
git add docs/TESTING.md docs/test-results/2026-08-22-hyperos3-smoke.md
git commit -m "docs: record HyperOS 3 reader smoke test"
git status --short
```

Expected: Gradle exits 0 and Git working tree is clean.
