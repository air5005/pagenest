# PageNest Hybrid Voice Reading Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 PageNest 中交付支持五种书籍格式、在线/离线引擎、后台媒体控制、段落高亮和可靠进度保存的语音阅读功能。

**Architecture:** 把内容抽取、单段语音引擎、会话状态机、媒体服务和 UI 分成独立契约。`SpeechSession` 串联 `SpeechContentSource`、`SpeechEngineRouter` 与 `SpeechProgressCommitter`；Android 前台 `MediaSessionService` 持有唯一会话，阅读页通过控制器观察并发送命令。离线使用 Android 系统 TTS，在线使用 Azure AI Speech 官方实时接口，自动模式在有限重试后从同一段落回退离线。

**Tech Stack:** Kotlin、Coroutines/Flow、Hilt、Jetpack Compose、Android `TextToSpeech`、Media3 1.5.1、Ktor 3.0.0、PDFBox Android 2.0.27.0、Android Keystore、JUnit/MockK/Robolectric/AndroidX Test。

**Spec:** `docs/superpowers/specs/2026-08-23-voice-reading-design.md`

## Global Constraints

- Android `namespace = "com.wxn.reader"`，`applicationId = "com.air5005.pagenest"`，`minSdk = 29`，`compileSdk/targetSdk = 36`。
- 目标真机为 HyperOS 3 / Android 16；真机未连接时必须记录 `NOT RUN (no connected device)`，不得宣称设备门禁通过。
- 支持 EPUB、TXT、MOBI、AZW3 和可提取正文的 PDF；整本扫描版 PDF 显示“此 PDF 为扫描版，暂不支持语音朗读”，首版不做 OCR。
- 同一时刻只允许一个朗读会话；用户切书、跳章、翻页或改变引擎时必须先取消旧队列。
- 首版必须支持后台持续朗读、锁屏与通知栏控制，以及使用可控时钟实现的定时关闭。
- 自动模式优先已配置的 Azure 在线音色；连接/响应超时、429 和 5xx 最多重试 2 次，间隔 500 ms、1500 ms，随后从同一未完成段落回退系统 TTS。
- 连接超时 10 秒，完整响应超时 30 秒；认证、Region、余额错误不重试。
- 云请求最多 500 个 Unicode 码点；超长段落在自然标点处切分，不能丢字或重复。
- 只有段落完成后才能持久化段末进度；开始、暂停、取消、失败或进程死亡不得提交未完成段落。
- 在线正文只按当前段发送；缓存仅限当前书籍当前章节，最大 128 MiB，24 小时过期，LRU 淘汰。
- Azure Key 必须由 Android Keystore 中不可导出的 AES 密钥加密；密钥密文、缓存及设置文件排除自动备份。
- 日志不得包含 API Key、认证头、书名、正文、完整文件路径或云响应体。
- 不新增广泛存储权限；删除现有非正式 Edge TTS 发布路径，不以其接口作为回退。
- 普通自动化测试禁止访问真实 Azure；云契约使用本地假服务器。
- 每个任务必须 RED → GREEN、独立复审、完整相关回归、Conventional Commit，然后执行 `git push origin HEAD:master`。

---

### Task 1: Define speech models and deterministic paragraph segmentation

**Files:**
- Create: `app/src/main/java/com/air5005/pagenest/speech/model/SpeechModels.kt`
- Create: `app/src/main/java/com/air5005/pagenest/speech/content/SpeechSegmenter.kt`
- Create: `app/src/test/java/com/air5005/pagenest/speech/content/SpeechSegmenterTest.kt`

**Interfaces:**
- Consumes: `com.wxn.base.bean.Locator` and rendered `TextLine` paragraph/offset metadata.
- Produces:
  - `SpeechPosition(bookId: Long, chapterIndex: Int, pageIndex: Int?, paragraphIndex: Int, textOffset: Int)`
  - `SpeechSegment(id: String, position: SpeechPosition, partIndex: Int, text: String, locator: Locator)`
  - `SpeechMode { OFFLINE, ONLINE, AUTO }`
  - `SpeechError` with stable retry/security/UI categories
  - `SpeechPlaybackState { Idle, Preparing, Playing, Paused, Completed, Error }`
  - `SpeechSegmenter.fromParagraph(...) : List<SpeechSegment>`

- [ ] **Step 1: Write failing segmentation tests**

```kotlin
@Test fun `500 code points stay in one segment`() {
    val text = "书".repeat(500)
    val result = segmenter.fromParagraph(position(), text, progression = 0.25)
    assertEquals(listOf(text), result.map(SpeechSegment::text))
}

@Test fun `501 code points split at punctuation without loss`() {
    val first = "甲".repeat(480) + "。"
    val second = "乙".repeat(20)
    val result = segmenter.fromParagraph(position(), first + second, progression = 0.25)
    assertEquals(listOf(first, second), result.map(SpeechSegment::text))
    assertEquals(first + second, result.joinToString("") { it.text })
    assertEquals(listOf(0, 1), result.map { it.partIndex })
}

@Test fun `images rules and blank paragraphs produce no speech`() {
    assertTrue(segmenter.fromParagraph(position(), " \n\t", 0.0).isEmpty())
}
```

Add boundary cases for surrogate pairs, Chinese/English punctuation, a single 501-code-point token, stable IDs, offsets, and `Locator` end positions.

- [ ] **Step 2: Run RED**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests '*SpeechSegmenterTest'
```

Expected: compilation fails because `SpeechModels` and `SpeechSegmenter` do not exist.

- [ ] **Step 3: Implement the pure model and segmenter**

```kotlin
data class SpeechPosition(
    val bookId: Long,
    val chapterIndex: Int,
    val pageIndex: Int?,
    val paragraphIndex: Int,
    val textOffset: Int,
)

data class SpeechSegment(
    val id: String,
    val position: SpeechPosition,
    val partIndex: Int,
    val text: String,
    val locator: Locator,
)

enum class SpeechMode { OFFLINE, ONLINE, AUTO }

sealed interface SpeechError {
    val kind: Kind
    enum class Kind { NETWORK, RATE_LIMIT, SERVICE, AUTH, CONFIGURATION, QUOTA, CONTENT, ENGINE, DECODE }
    data object NetworkTimeout : SpeechError { override val kind = Kind.NETWORK }
    data object RateLimited : SpeechError { override val kind = Kind.RATE_LIMIT }
    data object ServiceUnavailable : SpeechError { override val kind = Kind.SERVICE }
    data object InvalidCredentials : SpeechError { override val kind = Kind.AUTH }
    data object InvalidRegion : SpeechError { override val kind = Kind.CONFIGURATION }
    data object QuotaExceeded : SpeechError { override val kind = Kind.QUOTA }
    data object NoExtractableText : SpeechError { override val kind = Kind.CONTENT }
    data object SystemTtsUnavailable : SpeechError { override val kind = Kind.ENGINE }
    data object MissingLanguageData : SpeechError { override val kind = Kind.ENGINE }
    data object UnsupportedLocale : SpeechError { override val kind = Kind.ENGINE }
    data object AudioDecodeFailure : SpeechError { override val kind = Kind.DECODE }
}

sealed interface SpeechPlaybackState {
    data object Idle : SpeechPlaybackState
    data class Preparing(val segment: SpeechSegment) : SpeechPlaybackState
    data class Playing(val segment: SpeechSegment, val engineId: String) : SpeechPlaybackState
    data class Paused(val segment: SpeechSegment) : SpeechPlaybackState
    data object Completed : SpeechPlaybackState
    data class Error(val error: SpeechError, val segment: SpeechSegment?) : SpeechPlaybackState
}

fun SpeechPlaybackState.currentSegment(): SpeechSegment? = when (this) {
    is SpeechPlaybackState.Preparing -> segment
    is SpeechPlaybackState.Playing -> segment
    is SpeechPlaybackState.Paused -> segment
    is SpeechPlaybackState.Error -> segment
    SpeechPlaybackState.Idle, SpeechPlaybackState.Completed -> null
}
```

Use `String.codePoints()` to enforce the 500-code-point limit. Prefer the last `。！？；.!?;\n` at or before the limit; otherwise split exactly at the limit. Normalize CRLF to LF and trim only leading/trailing whitespace while preserving all interior text.

- [ ] **Step 4: Run GREEN and regression build**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests '*SpeechSegmenterTest'
./gradlew.bat :app:assembleDebug :app:lintDebug
```

Expected: all segmenter tests pass; build and lint exit 0.

- [ ] **Step 5: Commit and push**

```powershell
git add app/src/main/java/com/air5005/pagenest/speech/model app/src/main/java/com/air5005/pagenest/speech/content/SpeechSegmenter.kt app/src/test/java/com/air5005/pagenest/speech/content/SpeechSegmenterTest.kt
git commit -m "feat: model deterministic speech segments"
git push origin HEAD:master
```

### Task 2: Adapt reflowable books and extract PDF speech text

**Files:**
- Create: `app/src/main/java/com/air5005/pagenest/speech/content/SpeechContentSource.kt`
- Create: `app/src/main/java/com/air5005/pagenest/speech/content/ReflowableSpeechContentSource.kt`
- Create: `app/src/main/java/com/air5005/pagenest/speech/content/PdfSpeechContentSource.kt`
- Create: `app/src/main/java/com/air5005/pagenest/speech/content/PdfSpeechDocument.kt`
- Modify: `app/src/main/java/com/wxn/reader/presentation/mainReader/PageViewController.kt`
- Modify: `app/src/main/java/com/wxn/reader/presentation/pdfReader/PdfReaderViewModel.kt`
- Create: `app/src/test/java/com/air5005/pagenest/speech/content/ReflowableSpeechContentSourceTest.kt`
- Create: `app/src/test/java/com/air5005/pagenest/speech/content/PdfSpeechContentSourceTest.kt`

**Interfaces:**
- Consumes: Task 1 `SpeechSegmenter`, `PageViewController`, PDFBox Android, app-private book URI.
- Produces:
  - `SpeechContentSource.current(): SpeechSegment?`
  - `SpeechContentSource.next(): SpeechSegment?`
  - `SpeechContentSource.previous(): SpeechSegment?`
  - `SpeechContentSource.seek(position: SpeechPosition): SpeechSegment?`
  - `SpeechContentSource.close()`
  - `PageViewController.speechPageSnapshot(): SpeechPageSnapshot`
  - `PdfSpeechAvailability { READABLE, SCANNED }`

- [ ] **Step 1: Write failing content-source tests**

```kotlin
@Test fun `reflowable source groups lines by paragraph and crosses chapter`() = runTest {
    val pages = fakePages(
        page(chapter = 0, line(paragraph = 4, text = "第一行"), line(paragraph = 4, text = "第二行")),
        page(chapter = 1, line(paragraph = 0, text = "下一章")),
    )
    val source = ReflowableSpeechContentSource(bookId = 7, navigator = pages, segmenter)
    assertEquals("第一行第二行", source.current()!!.text)
    assertEquals("下一章", source.next()!!.text)
}

@Test fun `text PDF exposes page segments and scanned PDF is rejected`() = runTest {
    val readable = pdfFactory.open(createPdf(pageText = listOf("第一页正文", "第二页正文")))
    val source = PdfSpeechContentSource(9, readable, segmenter)
    assertEquals(PdfSpeechAvailability.READABLE, source.availability())
    assertEquals(listOf("第一页正文", "第二页正文"), collectText(source))

    val scanned = pdfFactory.open(createImageOnlyPdf())
    assertEquals(PdfSpeechAvailability.SCANNED, PdfSpeechContentSource(10, scanned, segmenter).availability())
}
```

Also add a parameterized adapter test for `SupportedBookFormat.EPUB`, `TXT`, `MOBI`, and `AZW3`, proving that each parser's immutable page snapshots produce stable speech locations. Cover image/line-only `TextLine`, repeated paragraph across page boundaries, empty PDF pages, partial scanned pages, cancellation during PDF extraction, descriptor/document closure, and private URI access.

- [ ] **Step 2: Run RED**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests '*ReflowableSpeechContentSourceTest' --tests '*PdfSpeechContentSourceTest'
```

Expected: compilation fails because the content source interfaces do not exist.

- [ ] **Step 3: Implement snapshot adapters and bounded PDF extraction**

```kotlin
interface SpeechContentSource : AutoCloseable {
    suspend fun current(): SpeechSegment?
    suspend fun next(): SpeechSegment?
    suspend fun previous(): SpeechSegment?
    suspend fun seek(position: SpeechPosition): SpeechSegment?
    override fun close()
}

data class SpeechPageSnapshot(
    val chapterIndex: Int,
    val pageIndex: Int,
    val progression: Double,
    val lines: List<SpeechLineSnapshot>,
)
```

`PageViewController` must create immutable snapshots on Main and perform page/chapter moves on Main; no background task may mutate `TextLine`. `PdfSpeechDocument` opens one `PDDocument` for the source lifetime and uses `PDFTextStripper.startPage/endPage` for one-based page extraction. Normalize extracted PDF whitespace, treat an entire document with zero non-whitespace code points as `SCANNED`, and close `PDDocument` on cancellation and normal completion.

- [ ] **Step 4: Run GREEN and full parser regression**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests '*SpeechSegmenterTest' --tests '*ReflowableSpeechContentSourceTest' --tests '*PdfSpeechContentSourceTest'
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Expected: new content tests and all existing import/protection tests pass; build and lint exit 0.

- [ ] **Step 5: Commit and push**

```powershell
git add app/src/main/java/com/air5005/pagenest/speech/content app/src/main/java/com/wxn/reader/presentation/mainReader/PageViewController.kt app/src/main/java/com/wxn/reader/presentation/pdfReader/PdfReaderViewModel.kt app/src/test/java/com/air5005/pagenest/speech/content
git commit -m "feat: expose speech text for all book formats"
git push origin HEAD:master
```

### Task 3: Replace the global speech singleton with a cancellable offline engine

**Files:**
- Create: `app/src/main/java/com/air5005/pagenest/speech/engine/SpeechEngine.kt`
- Create: `app/src/main/java/com/air5005/pagenest/speech/engine/SystemTtsEngine.kt`
- Create: `app/src/main/java/com/air5005/pagenest/speech/engine/AndroidTextToSpeechFactory.kt`
- Create: `app/src/test/java/com/air5005/pagenest/speech/engine/SystemTtsEngineTest.kt`
- Modify: `app/src/main/java/com/wxn/reader/di/AppModule.kt`

**Interfaces:**
- Consumes: Task 1 `SpeechSegment` and Android `TextToSpeech`.
- Produces:
  - `SpeechEngine.id: String`
  - `SpeechEngine.voices(localeTag: String): List<SpeechVoice>`
  - `SpeechEngine.speak(request: SpeechRequest): SpeechEngineResult`
  - `SpeechEngine.stop()` and `SpeechEngine.close()`
  - `SystemTtsEngine` with `id = "system"`

- [ ] **Step 1: Write failing engine lifecycle tests**

```kotlin
@Test fun `completion resumes exactly once and cancellation stops utterance`() = runTest {
    val platform = FakePlatformTts()
    val engine = SystemTtsEngine(factoryReturning(platform), backgroundScope)
    val request = request("正文")
    val job = async { engine.speak(request) }
    platform.complete(request.segment.id)
    assertEquals(SpeechEngineResult.Completed, job.await())

    val cancelled = async { engine.speak(request("下一段")) }
    cancelled.cancelAndJoin()
    assertEquals(1, platform.stopCalls)
}
```

Cover initialization failure, missing language data, unsupported locale, error callback, duplicate callbacks, stale callbacks after stop, rate/pitch bounds 0.25–2.0, `QUEUE_FLUSH`, and close idempotence. Put the duplicate-callback/cancellation scenario in a 20-iteration loop inside the unit test so the stress gate remains fast and deterministic.

- [ ] **Step 2: Run RED**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests '*SystemTtsEngineTest'
```

Expected: compilation fails because `SpeechEngine` and `SystemTtsEngine` do not exist.

- [ ] **Step 3: Implement one-utterance cancellable System TTS**

```kotlin
interface SpeechEngine : AutoCloseable {
    val id: String
    suspend fun voices(localeTag: String): List<SpeechVoice>
    suspend fun speak(request: SpeechRequest): SpeechEngineResult
    suspend fun stop()
    override fun close()
}

data class SpeechRequest(
    val generationId: Long,
    val segment: SpeechSegment,
    val localeTag: String,
    val voiceId: String?,
    val rate: Float,
    val pitch: Float,
)

data class SpeechVoice(
    val id: String,
    val displayName: String,
    val localeTag: String,
)

sealed interface SpeechEngineResult {
    data object Completed : SpeechEngineResult
    data object Cancelled : SpeechEngineResult
    data class Failed(val error: SpeechError) : SpeechEngineResult
}
```

Use `suspendCancellableCoroutine` and a unique utterance ID derived from session generation plus segment ID. Install one `UtteranceProgressListener`, guard continuations with atomic state, call `TextToSpeech.stop()` on cancellation, and never call the legacy global `Speech.init/getInstance` path.

- [ ] **Step 4: Run GREEN and lifecycle stress**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests '*SystemTtsEngineTest'
./gradlew.bat :app:assembleDebug :app:lintDebug
```

Expected: all focused runs pass, with no leaked callback or double resume.

- [ ] **Step 5: Commit and push**

```powershell
git add app/src/main/java/com/air5005/pagenest/speech/engine app/src/test/java/com/air5005/pagenest/speech/engine app/src/main/java/com/wxn/reader/di/AppModule.kt
git commit -m "feat: add cancellable offline speech engine"
git push origin HEAD:master
```

### Task 4: Build the single-session queue, progress commit, and reader highlighting

**Files:**
- Create: `app/src/main/java/com/air5005/pagenest/speech/session/SpeechSession.kt`
- Create: `app/src/main/java/com/air5005/pagenest/speech/session/SpeechSessionCommand.kt`
- Create: `app/src/main/java/com/air5005/pagenest/speech/progress/SpeechProgressCommitter.kt`
- Create: `app/src/main/java/com/air5005/pagenest/speech/progress/RoomSpeechProgressCommitter.kt`
- Create: `app/src/test/java/com/air5005/pagenest/speech/session/SpeechSessionTest.kt`
- Create: `app/src/test/java/com/air5005/pagenest/speech/progress/SpeechProgressCommitterTest.kt`
- Modify: `app/src/main/java/com/wxn/reader/presentation/mainReader/PageViewController.kt`

**Interfaces:**
- Consumes: Tasks 1–3 content source, engine and locator model; existing `SetReadingProgressUseCase`.
- Produces:
  - `SpeechSession.state: StateFlow<SpeechPlaybackState>`
  - `SpeechSession.start(source, options)`, `pause()`, `resume()`, `next()`, `previous()`, `seek()`, `setSleepTimer()`, `stop()`
  - `SpeechProgressCommitter.commitCompleted(segment)`
  - `SpeechHighlightSink.show(segment)` and `clear()`

- [ ] **Step 1: Write failing state-machine tests**

```kotlin
@Test fun `progress commits only after completed segment`() = runTest {
    engine.enqueue(SpeechEngineResult.Completed, SpeechEngineResult.Cancelled)
    session.start(sourceOf(segmentA, segmentB), options())
    advanceUntilIdle()
    assertEquals(listOf(segmentA.locator), progress.committed)
    assertEquals(segmentB, session.state.value.currentSegment())
}

@Test fun `seek cancels old generation and cannot play stale fallback`() = runTest {
    session.start(sourceOf(oldA, oldB), options())
    engine.awaitStarted(oldA)
    session.seek(newPosition)
    engine.complete(oldA)
    assertEquals(listOf(newA.id), engine.requestsAfterCancellation.map { it.segment.id })
}
```

Cover pause/resume restarting the same unfinished segment, previous/next, chapter transition, natural completion, engine failure, cancellation priority, concurrent commands, single active generation, highlight order, and crash-safe last completed position. Use an injected monotonic `SpeechClock` and add a no-real-sleep test proving that timer expiry stops playback, clears the timer, and commits only the last completed segment.

- [ ] **Step 2: Run RED**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests '*SpeechSessionTest' --tests '*SpeechProgressCommitterTest'
```

Expected: compilation fails because session/progress contracts do not exist.

- [ ] **Step 3: Implement serialized command processing**

```kotlin
sealed interface SpeechSessionCommand {
    data class Start(val source: SpeechContentSource, val options: SpeechOptions) : SpeechSessionCommand
    data object Pause : SpeechSessionCommand
    data object Resume : SpeechSessionCommand
    data object Next : SpeechSessionCommand
    data object Previous : SpeechSessionCommand
    data class Seek(val position: SpeechPosition) : SpeechSessionCommand
    data class SetSleepTimer(val deadlineElapsedMillis: Long?) : SpeechSessionCommand
    data object Stop : SpeechSessionCommand
}

data class SpeechOptions(
    val mode: SpeechMode,
    val localeTag: String,
    val voiceId: String?,
    val rate: Float,
    val pitch: Float,
)
```

Process commands through one `Channel<SpeechSessionCommand>` owned by one supervisor job. Increment a generation on start/seek/stop and ignore callbacks from older generations. Call `commitCompleted` only after `SpeechEngineResult.Completed`; update highlight before each request; clear highlight on stop/error/complete. `RoomSpeechProgressCommitter` delegates reflowable `Locator.toJsonString()` to `SetReadingProgressUseCase` and delegates PDF page positions to the existing PDF update path without treating a started page as complete.

- [ ] **Step 4: Run GREEN and regression suite**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests '*SpeechSessionTest' --tests '*SpeechProgressCommitterTest'
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Expected: state machine tests and existing reader tests pass; build and lint exit 0.

- [ ] **Step 5: Commit and push**

```powershell
git add app/src/main/java/com/air5005/pagenest/speech/session app/src/main/java/com/air5005/pagenest/speech/progress app/src/test/java/com/air5005/pagenest/speech/session app/src/test/java/com/air5005/pagenest/speech/progress app/src/main/java/com/wxn/reader/presentation/mainReader/PageViewController.kt
git commit -m "feat: coordinate speech sessions and reading progress"
git push origin HEAD:master
```

### Task 5: Add foreground MediaSession playback and interruption handling

**Files:**
- Create: `app/src/main/java/com/air5005/pagenest/speech/playback/SpeechPlaybackService.kt`
- Create: `app/src/main/java/com/air5005/pagenest/speech/playback/SpeechMediaPlayer.kt`
- Create: `app/src/main/java/com/air5005/pagenest/speech/playback/SpeechAudioFocusController.kt`
- Create: `app/src/main/java/com/air5005/pagenest/speech/playback/BecomingNoisyReceiver.kt`
- Create: `app/src/main/java/com/air5005/pagenest/speech/playback/SpeechController.kt`
- Create: `app/src/main/java/com/air5005/pagenest/speech/playback/EncodedAudioPlayer.kt`
- Create: `app/src/main/java/com/air5005/pagenest/speech/playback/Media3EncodedAudioPlayer.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/test/java/com/air5005/pagenest/speech/playback/SpeechMediaPlayerTest.kt`
- Create: `app/src/test/java/com/air5005/pagenest/speech/playback/SpeechAudioFocusControllerTest.kt`
- Create: `app/src/test/java/com/air5005/pagenest/speech/playback/Media3EncodedAudioPlayerTest.kt`
- Create: `app/src/androidTest/java/com/air5005/pagenest/speech/playback/SpeechPlaybackServiceTest.kt`

**Interfaces:**
- Consumes: Task 4 `SpeechSession`.
- Produces: Media3 `MediaSessionService`, lock-screen/notification play-pause/previous/next/stop, app-facing `SpeechController`, and a cancellable `EncodedAudioPlayer` for official cloud audio.

- [ ] **Step 1: Write failing media and focus tests**

```kotlin
@Test fun `permanent focus loss pauses and never auto resumes`() = runTest {
    focus.onFocusChange(AudioManager.AUDIOFOCUS_LOSS)
    assertEquals(listOf(SpeechSessionCommand.Pause), commands.values)
    focus.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)
    assertEquals(listOf(SpeechSessionCommand.Pause), commands.values)
}

@Test fun `becoming noisy pauses current session`() {
    receiver.onReceive(context, Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    verify { controller.pause() }
}
```

Instrument service creation, notification channel, exported=false, foreground type, MediaController commands, process recreation, stop cleanup, and no automatic playback after recreation. Unit-test encoded MP3 completion, decode failure, cancellation/stop, and player release without using the network.

- [ ] **Step 2: Run RED**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests '*SpeechMediaPlayerTest' --tests '*SpeechAudioFocusControllerTest' --tests '*Media3EncodedAudioPlayerTest'
./gradlew.bat :app:assembleDebugAndroidTest
```

Expected: compilation fails because the playback service and controllers do not exist.

- [ ] **Step 3: Implement service and Media3 bridge**

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<service
    android:name="com.air5005.pagenest.speech.playback.SpeechPlaybackService"
    android:exported="false"
    android:foregroundServiceType="mediaPlayback">
    <intent-filter>
        <action android:name="androidx.media3.session.MediaSessionService" />
    </intent-filter>
</service>
```

`SpeechMediaPlayer` extends `SimpleBasePlayer`, maps `playWhenReady` to session pause/resume, exposes current `MediaItem` metadata, and implements previous/next as MediaSession custom commands. `SpeechPlaybackService.onGetSession` returns the single session. `Media3EncodedAudioPlayer` owns one ExoPlayer on the application looper, accepts private encoded-audio bytes through a bounded `ByteArrayDataSource`, suspends until ended/error, and stops/releases on cancellation. Register the noisy receiver only while active, abandon focus on stop, and retain paused state—not playing state—after process reconstruction.

- [ ] **Step 4: Run GREEN and AndroidTest assembly**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests '*SpeechMediaPlayerTest' --tests '*SpeechAudioFocusControllerTest' --tests '*Media3EncodedAudioPlayerTest'
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

If a device is connected:

```powershell
./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.air5005.pagenest.speech.playback.SpeechPlaybackServiceTest
```

Otherwise record `NOT RUN (no connected device)` in the task report and carry this exact command to Task 9.

- [ ] **Step 5: Commit and push**

```powershell
git add app/src/main/java/com/air5005/pagenest/speech/playback app/src/main/AndroidManifest.xml app/src/main/res/values/strings.xml app/src/test/java/com/air5005/pagenest/speech/playback app/src/androidTest/java/com/air5005/pagenest/speech/playback
git commit -m "feat: keep speech playback active in background"
git push origin HEAD:master
```

### Task 6: Store Azure credentials safely and implement the official cloud engine

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/air5005/pagenest/speech/security/SpeechCredentialStore.kt`
- Create: `app/src/main/java/com/air5005/pagenest/speech/security/KeystoreSpeechCredentialStore.kt`
- Create: `app/src/main/java/com/air5005/pagenest/speech/cloud/AzureSpeechClient.kt`
- Create: `app/src/main/java/com/air5005/pagenest/speech/cloud/AzureSsmlBuilder.kt`
- Create: `app/src/main/java/com/air5005/pagenest/speech/engine/AzureSpeechEngine.kt`
- Modify: `app/src/main/res/xml/backup_rules.xml`
- Modify: `app/src/main/res/xml/data_extraction_rules.xml`
- Modify: `app/src/main/java/com/wxn/reader/di/AppModule.kt`
- Create: `app/src/test/java/com/air5005/pagenest/speech/security/KeystoreSpeechCredentialStoreTest.kt`
- Create: `app/src/test/java/com/air5005/pagenest/speech/cloud/AzureSsmlBuilderTest.kt`
- Create: `app/src/test/java/com/air5005/pagenest/speech/cloud/AzureSpeechClientTest.kt`

**Interfaces:**
- Consumes: Task 3 `SpeechEngine`, existing Ktor client, Android Keystore.
- Produces:
  - `SpeechCredentialStore.saveAzure(key, region)`, `loadAzure()`, `clearAzure()`
  - `AzureSpeechClient.voices(credentials)` and `synthesize(credentials, request)`
  - `AzureSpeechEngine` with `id = "azure"`

- [ ] **Step 1: Write failing credential and cloud-contract tests**

```kotlin
@Test fun `stored blob never contains plaintext key and clear removes it`() = runTest {
    store.saveAzure("secret-key", "eastasia")
    assertFalse(backingFile.readText().contains("secret-key"))
    assertEquals(AzureCredentials("secret-key", "eastasia"), store.loadAzure())
    store.clearAzure()
    assertNull(store.loadAzure())
}

@Test fun `client escapes SSML and classifies authentication failure`() = runTest {
    val captured = mutableListOf<HttpRequestData>()
    val engine = MockEngine { request ->
        captured += request
        respond("", HttpStatusCode.Unauthorized)
    }
    val client = azureClient(engine)
    assertEquals(SpeechError.InvalidCredentials, client.synthesize(creds(), request("甲<&乙")).error)
    val ssml = (captured.single().body as TextContent).text
    assertTrue(ssml.contains("甲&lt;&amp;乙"))
}
```

Add `ktor-client-mock` 3.0.0 to the version catalog and `testImplementation(libs.ktor.client.mock)` to the app. Cover strict region label validation, region-derived official hostname, Key header redaction, voice-list parsing, 200 audio, 401/403, 429 with Retry-After cap, 5xx, connect/read timeout, cancellation, oversized request rejection, and no body logging. The credential unit test injects a deterministic fake `SecretKeyProvider`; Task 9 proves the real `AndroidKeyStore` provider on device.

- [ ] **Step 2: Run RED**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests '*KeystoreSpeechCredentialStoreTest' --tests '*AzureSsmlBuilderTest' --tests '*AzureSpeechClientTest'
```

Expected: compilation fails because credential/cloud classes do not exist.

- [ ] **Step 3: Implement encrypted credentials and Azure REST calls**

```kotlin
interface SpeechCredentialStore {
    suspend fun saveAzure(key: String, region: String)
    suspend fun loadAzure(): AzureCredentials?
    suspend fun clearAzure()
}

data class AzureCredentials(val key: String, val region: String)
```

Create an AES-256-GCM key named `pagenest_speech_credentials_v1` in `AndroidKeyStore`; store IV+ciphertext in `filesDir/speech-secrets/azure.bin` using atomic replace. Region must match `^[a-z0-9-]{2,32}$`; build only `https://<region>.tts.speech.microsoft.com/cognitiveservices/v1` and `/cognitiveservices/voices/list`. Use Ktor with 10-second connect and 30-second request timeout, `Ocp-Apim-Subscription-Key`, escaped SSML, and a bounded MP3 response body. `AzureSpeechEngine` passes the response to Task 5's injected `EncodedAudioPlayer`; coroutine cancellation cancels the HTTP request and stops the player. In both backup XML files exclude file-domain paths `speech-secrets/`, `speech-cache/`, and `datastore/speech_preferences.preferences_pb`.

- [ ] **Step 4: Run GREEN and security regression**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests '*KeystoreSpeechCredentialStoreTest' --tests '*AzureSsmlBuilderTest' --tests '*AzureSpeechClientTest'
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
rg -n "secret-key|Ocp-Apim-Subscription-Key.*Logger|request\.body.*Logger" app/src/main app/src/test
```

Expected: tests/build/lint pass; source scan has no credential/body logging call.

- [ ] **Step 5: Commit and push**

```powershell
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/java/com/air5005/pagenest/speech/security app/src/main/java/com/air5005/pagenest/speech/cloud app/src/main/java/com/air5005/pagenest/speech/engine/AzureSpeechEngine.kt app/src/main/java/com/wxn/reader/di/AppModule.kt app/src/main/res/xml app/src/test/java/com/air5005/pagenest/speech/security app/src/test/java/com/air5005/pagenest/speech/cloud
git commit -m "feat: add secure Azure speech synthesis"
git push origin HEAD:master
```

### Task 7: Add bounded audio cache and automatic online-to-offline fallback

**Files:**
- Create: `app/src/main/java/com/air5005/pagenest/speech/cache/SpeechAudioCache.kt`
- Create: `app/src/main/java/com/air5005/pagenest/speech/cache/FileSpeechAudioCache.kt`
- Create: `app/src/main/java/com/air5005/pagenest/speech/engine/SpeechEngineRouter.kt`
- Create: `app/src/main/java/com/air5005/pagenest/speech/engine/RetryPolicy.kt`
- Create: `app/src/test/java/com/air5005/pagenest/speech/cache/FileSpeechAudioCacheTest.kt`
- Create: `app/src/test/java/com/air5005/pagenest/speech/engine/SpeechEngineRouterTest.kt`

**Interfaces:**
- Consumes: System and Azure engines, Task 4 session options.
- Produces: mode-aware `SpeechEngineRouter.speak(request, mode): RoutedSpeechResult` and 128 MiB/24-hour cache.

- [ ] **Step 1: Write failing cache and fallback tests**

```kotlin
@Test fun `auto retries transient failures then falls back on same segment`() = runTest {
    azure.enqueue(NetworkTimeout, Http5xx, Http5xx)
    router.speak(request(segmentA), SpeechMode.AUTO)
    assertEquals(listOf(500L, 1500L), clock.recordedDelays)
    assertEquals(listOf(segmentA.id), system.requests.map { it.segment.id })
}

@Test fun `cache evicts LRU beyond 128 MiB and expires after 24 hours`() = runTest {
    val cache = fileCache(maxBytes = 128, expiryMillis = 86_400_000)
    cache.put(keyA, ByteArray(80), now = 0)
    cache.put(keyB, ByteArray(60), now = 1)
    assertNull(cache.get(keyA, now = 2))
    assertNotNull(cache.get(keyB, now = 2))
    assertNull(cache.get(keyB, now = 86_400_002))
}
```

Cover OFFLINE never touching Azure, ONLINE never falling back, AUTO authentication failure immediate fallback, cancellation with no retry/fallback, switching book/chapter cleanup, atomic cache publication, corrupt cache deletion, and no plaintext/key in cache names.

- [ ] **Step 2: Run RED**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests '*FileSpeechAudioCacheTest' --tests '*SpeechEngineRouterTest'
```

Expected: compilation fails because cache/router classes do not exist.

- [ ] **Step 3: Implement exact routing policy**

```kotlin
data class RetryPolicy(
    val delaysMillis: List<Long> = listOf(500L, 1500L),
    val retryable: Set<SpeechError.Kind> = setOf(
        SpeechError.Kind.NETWORK,
        SpeechError.Kind.RATE_LIMIT,
        SpeechError.Kind.SERVICE,
    ),
)
```

Hash `segment.text + voiceId + rate + pitch + localeTag` with SHA-256 for filenames; never include book title/text/key. Store audio and metadata atomically in `filesDir/speech-cache/<bookId>/<chapterKey>/`. Enforce expiry and capacity before/after publication. Router checks cache before Azure, applies exactly two delays, and calls system engine with the unchanged `SpeechSegment` only in AUTO.

- [ ] **Step 4: Run GREEN and full regression**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests '*FileSpeechAudioCacheTest' --tests '*SpeechEngineRouterTest' --tests '*SpeechSessionTest'
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Expected: cache/router/session and all existing tests pass.

- [ ] **Step 5: Commit and push**

```powershell
git add app/src/main/java/com/air5005/pagenest/speech/cache app/src/main/java/com/air5005/pagenest/speech/engine/SpeechEngineRouter.kt app/src/main/java/com/air5005/pagenest/speech/engine/RetryPolicy.kt app/src/test/java/com/air5005/pagenest/speech/cache app/src/test/java/com/air5005/pagenest/speech/engine/SpeechEngineRouterTest.kt
git commit -m "feat: fall back safely between speech engines"
git push origin HEAD:master
```

### Task 8: Wire reader controls, Azure settings, PDF UI, and remove unofficial Edge TTS

**Files:**
- Create: `app/src/main/java/com/air5005/pagenest/speech/settings/SpeechPreferencesRepository.kt`
- Create: `app/src/main/java/com/air5005/pagenest/speech/settings/SpeechSettingsViewModel.kt`
- Create: `app/src/main/java/com/air5005/pagenest/speech/ui/SpeechControlSheet.kt`
- Create: `app/src/main/java/com/air5005/pagenest/speech/ui/SpeechSettingsScreen.kt`
- Create: `app/src/test/java/com/air5005/pagenest/speech/settings/SpeechSettingsViewModelTest.kt`
- Create: `app/src/test/java/com/air5005/pagenest/speech/ui/SpeechControlPolicyTest.kt`
- Modify: `app/src/main/java/com/wxn/reader/presentation/mainReader/MainReadViewModel.kt`
- Modify: `app/src/main/java/com/wxn/reader/presentation/mainReader/ReaderView.kt`
- Modify: `app/src/main/java/com/wxn/reader/presentation/pdfReader/PdfReaderViewModel.kt`
- Modify: `app/src/main/java/com/wxn/reader/presentation/pdfReader/PdfReaderScreen.kt`
- Modify: `app/src/main/java/com/wxn/reader/navigation/SetupNavGraph.kt`
- Modify: `app/src/main/java/com/wxn/reader/navigation/Screens.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Delete: `app/src/main/java/com/wxn/reader/util/tts/EdgeTTS.kt`
- Delete: `app/src/main/java/com/wxn/reader/util/tts/DRM.kt`
- Delete: `app/src/main/java/com/wxn/reader/util/tts/Codec.kt`
- Delete: `app/src/main/java/com/wxn/reader/util/tts/CodecTest.kt`
- Delete: `app/src/main/java/com/wxn/reader/util/tts/Player.kt`
- Delete: `app/src/main/java/com/wxn/reader/util/tts/service/EdgeTtsService.kt`
- Delete or replace after reference audit: `app/src/main/java/com/wxn/reader/util/tts/repository/SpeakerRepository.kt`
- Delete or replace after reference audit: `app/src/main/java/com/wxn/reader/presentation/settings/components/SpeakerScreen.kt`
- Delete or replace after reference audit: `app/src/main/java/com/wxn/reader/presentation/settings/viewmodels/SpeakerViewModel.kt`

**Interfaces:**
- Consumes: Tasks 2–7 service/controller/settings contracts.
- Produces: identical speech controls for reflowable and PDF readers, Azure configuration/test connection, exact Chinese errors and consent.

- [ ] **Step 1: Write failing UI-policy and ViewModel tests**

```kotlin
@Test fun `auto mode consent is required before first Azure request`() = runTest {
    viewModel.selectMode(SpeechMode.AUTO)
    viewModel.start()
    assertEquals(SpeechUiEvent.RequestOnlineConsent, events.awaitItem())
    verify(exactly = 0) { controller.start(any()) }
}

@Test fun `scan PDF error maps to exact Chinese message`() {
    assertEquals(
        "此 PDF 为扫描版，暂不支持语音朗读",
        policy.messageFor(SpeechError.NoExtractableText),
    )
}
```

Cover play/pause/stop/previous/next, speed/pitch 0.25–2.0, mode changes cancelling old generation, engine/fallback indicator, Azure Key overwrite/delete without plaintext echo, Region validation, connection test, timeout/auth/rate/余额 messages, timer choices, reader destruction not stopping active background service, and explicit Stop doing so.

- [ ] **Step 2: Run RED**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests '*SpeechSettingsViewModelTest' --tests '*SpeechControlPolicyTest'
```

Expected: compilation fails because settings/UI policy classes do not exist.

- [ ] **Step 3: Implement Compose controls and both reader adapters**

```kotlin
data class SpeechControlUiState(
    val playback: SpeechPlaybackState,
    val mode: SpeechMode,
    val activeEngineLabel: String,
    val rate: Float,
    val pitch: Float,
    val voiceId: String?,
    val sleepTimerMinutes: Int?,
)
```

Replace the commented `TtsPlayer` integration with `SpeechControlSheet`; `MainReadViewModel` and `PdfReaderViewModel` create their respective `SpeechContentSource` and send commands through `SpeechController`. Keep active service playback across navigation, but cancel and replace when another book starts. Repurpose `Screens.TtsSetScreen` for `SpeechSettingsScreen`. Put mandated exact Chinese error/consent text in default resources with `translatable="false"`; add every other new UI label to default English and every existing localized `values-*` directory so lint reports no missing translation.

Before deleting legacy Edge files, run `rg -n 'EdgeTTS|EdgeTtsService|SpeakerRepository|SpeakerScreen|SpeakerViewModel|com\.wxn\.reader\.util\.tts' app/src/main` and remove or migrate every production reference. Preserve only generic visual components that the new screen actually uses.

- [ ] **Step 4: Run GREEN, reference audit, and complete app regression**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests '*SpeechSettingsViewModelTest' --tests '*SpeechControlPolicyTest' --tests '*SpeechSessionTest'
rg -n 'EdgeTTS|EdgeTtsService|rany2/edge-tts|speech.platform.bing.com' app/src/main
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

Expected: focused/full tests pass; Edge audit returns no production matches; both APKs build; lint has 0 errors.

- [ ] **Step 5: Commit and push**

```powershell
git add -A app/src/main/java/com/air5005/pagenest/speech/settings app/src/main/java/com/air5005/pagenest/speech/ui app/src/main/java/com/wxn/reader/presentation/mainReader app/src/main/java/com/wxn/reader/presentation/pdfReader app/src/main/java/com/wxn/reader/navigation app/src/main/java/com/wxn/reader/util/tts app/src/main/java/com/wxn/reader/presentation/settings app/src/main/res app/src/test/java/com/air5005/pagenest/speech
git commit -m "feat: connect voice reading controls to every reader"
git push origin HEAD:master
```

### Task 9: Prove Android lifecycle and HyperOS 3 release gates

**Files:**
- Create: `app/src/androidTest/java/com/air5005/pagenest/speech/SpeechKeystoreInstrumentedTest.kt`
- Create: `app/src/androidTest/java/com/air5005/pagenest/speech/SpeechReaderInstrumentedTest.kt`
- Create: `app/src/androidTest/java/com/air5005/pagenest/speech/playback/SpeechPlaybackRecoveryTest.kt`
- Create: `docs/testing/voice-reading-hyperos3.md`
- Modify: `docs/DEVELOPMENT.md`

**Interfaces:**
- Consumes: complete Tasks 1–8 feature.
- Produces: auditable automated/device evidence and exact install/test commands for the target phone.

- [ ] **Step 1: Add failing Android release-gate tests**

```kotlin
@Test fun encryptedCredentialSurvivesProcessReopenAndClears() = runTest {
    store.saveAzure("instrumented-secret", "eastasia")
    assertEquals("instrumented-secret", reopenedStore().loadAzure()!!.key)
    reopenedStore().clearAzure()
    assertNull(reopenedStore().loadAzure())
}

@Test fun serviceRecreationRestoresPausedPositionWithoutSpeaking() = runTest {
    val completed = segment("chapter-1", paragraphIndex = 3)
    launchAndComplete(completed)
    recreateServiceProcessBoundary()
    assertEquals(SpeechPlaybackState.Paused(completed), controller.state.first())
    assertEquals(0, fakeEngine.requestsAfterRecreation)
}
```

Add Android tests for notification actions, focus loss/gain, noisy route, user page seek cancelling the old generation, reflowable highlight, PDF page follow, scanned PDF message, and backup-rule resource presence.

- [ ] **Step 2: Run desktop/assembly gates before device**

```powershell
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
adb devices -l
```

Expected: desktop gate exits 0. If `adb devices -l` has no `device` row, record `NOT RUN (no connected device)` and do not mark Task 9 complete.

- [ ] **Step 3: Run exact connected tests on HyperOS 3 / Android 16**

```powershell
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.mi.os.version.name
./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.air5005.pagenest.speech
./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.wxn.reader.data.source.local.AppDatabaseMigrationTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: Android release is 16, SDK is 36, HyperOS property identifies the target build, speech Android tests pass, the carried Room migration test passes, and APK install exits `Success`.

- [ ] **Step 4: Execute and record the manual 60-minute matrix**

In `docs/testing/voice-reading-hyperos3.md`, record timestamp, device properties, APK SHA-256 and PASS/FAIL evidence for:

- EPUB, TXT, MOBI, AZW3 current paragraph, cross-page, cross-chapter, previous/next.
- Text PDF reading and image-only PDF rejection.
- System Chinese offline voice.
- Valid/invalid Azure Key and Region, online Chinese voice, Wi-Fi/mobile/offline transitions.
- Automatic fallback from the same segment with no duplicate speech.
- Background, lock screen, screen off, notification controls, audio focus and headphone removal.
- Highlight follow, manual page/chapter jump, completed-segment progress, force-stop/reopen paused recovery.
- Continuous 60-minute run with start/end battery, peak memory from `dumpsys meminfo`, crash/ANR logcat scan, and observed device temperature.

- [ ] **Step 5: Run final verification, commit, and push**

```powershell
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
git diff --check
git add app/src/androidTest/java/com/air5005/pagenest/speech docs/testing/voice-reading-hyperos3.md docs/DEVELOPMENT.md
git commit -m "test: verify voice reading on HyperOS 3"
git push origin HEAD:master
git ls-remote origin refs/heads/master
```

Expected: all automated gates pass, device evidence has no unexecuted row, and remote `master` equals local HEAD.

---

## Final whole-feature review gate

After Task 9, generate a review package from the parent of Task 1 through Task 9 HEAD. The final reviewer must check the full spec, all task reports, any deferred findings, manifest/backup/security changes, removal of unofficial Edge endpoints, cancellation/progress invariants, and HyperOS evidence. Run one bounded final fix wave if required, then one scoped re-review. Do not declare the voice-reading feature complete until the final review is clean and remote `master` matches the reviewed HEAD.
