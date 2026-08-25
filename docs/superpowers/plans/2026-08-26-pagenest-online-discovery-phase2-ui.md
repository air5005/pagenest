# PageNest Online Discovery Phase 2 UI Implementation Plan

> **Execution:** Follow Superpower TDD task by task on `master`. Every task starts with a focused failing test, ends with focused regression, then commits and pushes before the next task.

**Goal:** Expose the Phase 1 online catalog through a polished blue-green discovery experience, enrich detail metadata with low-volume Open Library lookups, and change the home navigation to `书架 / 发现 / 听书 / 我的` without enabling unreviewed online file downloads.

**Architecture:** Keep all transports behind injected services. `DiscoveryViewModel` depends on small repository/enrichment interfaces and emits immutable UI state. `DiscoveryScreen` and `OnlineBookDetailScreen` are stateless Compose renderers. The existing `HomeScreen` hosts discovery as the second top-level tab so bottom-navigation state remains compatible with the current app architecture. Phase 3 alone will own download, redirect validation and private-library import.

**Stack:** Kotlin, coroutines/Flow, Ktor MockEngine/OkHttp, kotlinx.serialization, Hilt, Jetpack Compose Material 3, Coil, JUnit, Robolectric and Compose UI tests.

---

## Global constraints

- Work directly on `master`, as explicitly requested.
- Do not scrape commercial fiction websites or bypass access controls.
- Gutendex and Project Gutenberg remain the enabled public catalog sources.
- Standard Ebooks code remains available, but production requests are disabled until an official open-source/patron access arrangement exists. UI reports `需要授权`, not a transport failure.
- Open Library is detail/search enrichment, not the primary application database. Serialize requests, enforce at most one request per second, use a PageNest/GitHub User-Agent, request only explicit fields, and cache detail metadata for 24 hours.
- Open Library `has_fulltext`, `public_scan_b` and `ebook_access` are descriptive availability only in Phase 2. They must not create a direct-download action.
- Covers use HTTPS `covers.openlibrary.org` URLs based on cover ID/OLID; never crawl or prefetch off-screen lists.
- Only allow external source pages generated from recognized source IDs and validated identifiers. Never open a transport-provided arbitrary URL.
- No `加入书架` or `开始阅读` network action in Phase 2. Detail UI explains that secure import is coming in Phase 3.
- Never expose title, query, response body or acquisition URL in exception/log text.
- All network tests use MockEngine/local fixtures; screen tests use fakes.
- Add every new user-facing string to base, `values-zh`, and all currently maintained locale folders so Lint remains at 0 errors.
- After each task: `git diff --check`, focused tests, commit, push `origin master`.

---

### Task 1: Add production discovery wiring and source availability

**Files:**

- Create: `app/src/main/java/com/air5005/pagenest/discovery/repository/DiscoveryCatalogRepository.kt`
- Modify: `app/src/main/java/com/air5005/pagenest/discovery/repository/OnlineDiscoveryRepository.kt`
- Create: `app/src/main/java/com/air5005/pagenest/discovery/config/DiscoverySourceRegistry.kt`
- Create: `app/src/main/java/com/air5005/pagenest/discovery/di/DiscoveryModule.kt`
- Test: `app/src/test/java/com/air5005/pagenest/discovery/config/DiscoverySourceRegistryTest.kt`
- Test: `app/src/test/java/com/air5005/pagenest/discovery/di/DiscoveryModuleContractTest.kt`

**Produces:**

```kotlin
interface DiscoveryCatalogRepository {
    suspend fun discover(request: CatalogRequest): DiscoveryResult
}

data class DiscoverySourceStatus(
    val id: String,
    val enabled: Boolean,
    val reason: SourceDisabledReason? = null,
)
```

- [ ] Write RED tests proving source order is stable (`gutendex`, `gutenberg-opds`), Standard Ebooks is disabled with `AUTHORIZATION_REQUIRED`, and no Standard HTTP client is constructed by the default registry.
- [ ] Make `OnlineDiscoveryRepository` implement the interface without changing Phase 1 behavior.
- [ ] Provide a dedicated OkHttp `HttpClient` with redirects disabled, `FileCatalogCache(context.filesDir/discovery-cache)`, the enabled source list, registry statuses, repository and lifecycle close hook.
- [ ] Verify Hilt/Kotlin compilation and existing repository tests.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*DiscoverySourceRegistryTest' --tests '*DiscoveryModuleContractTest' --tests '*OnlineDiscoveryRepositoryTest'
git commit -m "feat: wire production online discovery sources"
git push origin master
```

---

### Task 2: Add rate-limited Open Library detail enrichment

**Files:**

- Create: `app/src/main/java/com/air5005/pagenest/discovery/openlibrary/OpenLibraryDtos.kt`
- Create: `app/src/main/java/com/air5005/pagenest/discovery/openlibrary/OpenLibraryEnricher.kt`
- Create: `app/src/main/java/com/air5005/pagenest/discovery/openlibrary/OpenLibraryRateLimiter.kt`
- Create: `app/src/main/java/com/air5005/pagenest/discovery/openlibrary/OpenLibraryMetadataCache.kt`
- Create: `app/src/test/resources/discovery/openlibrary/pride-search.json`
- Create: `app/src/test/resources/discovery/openlibrary/no-match.json`
- Test: `app/src/test/java/com/air5005/pagenest/discovery/openlibrary/OpenLibraryEnricherTest.kt`
- Test: `app/src/test/java/com/air5005/pagenest/discovery/openlibrary/OpenLibraryRateLimiterTest.kt`
- Test: `app/src/test/java/com/air5005/pagenest/discovery/openlibrary/OpenLibraryMetadataCacheTest.kt`

**Produces:**

```kotlin
data class OpenLibraryMetadata(
    val workId: String,
    val coverUrl: String?,
    val firstPublishYear: Int?,
    val editionCount: Int?,
    val publicScan: Boolean,
    val ebookAccess: OpenLibraryEbookAccess,
    val sourcePageUrl: String,
)

interface OnlineBookEnricher {
    suspend fun enrich(book: OnlineBook): OpenLibraryMetadata?
}
```

- [ ] Start with MockEngine tests for exact-title/first-author matching, explicit `fields`, URL encoding, 2 MiB bound, HTTPS host checks, 404/no match, malformed response, and response-body-safe failures.
- [ ] Test that calls are serialized and start at least 1,000 ms apart using injected monotonic clock/delay; cancellation propagates.
- [ ] Test 24-hour hashed metadata cache, corrupt deletion, atomic write and bounded total size.
- [ ] Request only `key,title,author_name,cover_i,first_publish_year,edition_count,language,has_fulltext,public_scan_b,ebook_access` from `https://openlibrary.org/search.json`.
- [ ] Build covers as `https://covers.openlibrary.org/b/id/{coverId}-L.jpg?default=false` and source pages as `https://openlibrary.org/works/{OLID}`.
- [ ] Never emit `OnlineAcquisition` from Open Library in this phase.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*OpenLibraryEnricherTest' --tests '*OpenLibraryRateLimiterTest' --tests '*OpenLibraryMetadataCacheTest'
git commit -m "feat: enrich discovery details from open library"
git push origin master
```

---

### Task 3: Build deterministic discovery presentation state

**Files:**

- Create: `app/src/main/java/com/air5005/pagenest/discovery/ui/DiscoveryUiState.kt`
- Create: `app/src/main/java/com/air5005/pagenest/discovery/ui/DiscoveryViewModel.kt`
- Create: `app/src/main/java/com/air5005/pagenest/discovery/ui/DiscoverySections.kt`
- Test: `app/src/test/java/com/air5005/pagenest/discovery/ui/DiscoveryViewModelTest.kt`
- Test: `app/src/test/java/com/air5005/pagenest/discovery/ui/DiscoverySectionsTest.kt`

**State:** loading, refreshing, selected catalog tab, selected language, search text/mode, books, curated row, ranked row, unavailable source IDs, source statuses, selected book, enrichment state and one-shot user message.

- [ ] Write RED tests for initial recommended load, tab/language changes, retry, 350 ms debounced search, latest label semantics, stale-cache indicator, partial-source warning, selection/back behavior and cancellation of superseded requests.
- [ ] Keep display sections pure: curated row takes the first 8 readable entries; ranking is deterministic and never duplicates a stable key.
- [ ] Do not put localized strings inside state. Emit typed status/reason values and localize only in Compose.
- [ ] Inject dispatchers/repository/enricher/registry so JVM tests need no Android framework and no network.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*DiscoveryViewModelTest' --tests '*DiscoverySectionsTest'
git commit -m "feat: model online discovery presentation state"
git push origin master
```

---

### Task 4: Implement discovery and online detail Compose pages

**Files:**

- Create: `app/src/main/java/com/air5005/pagenest/discovery/ui/DiscoveryScreen.kt`
- Create: `app/src/main/java/com/air5005/pagenest/discovery/ui/OnlineBookDetailScreen.kt`
- Create: `app/src/main/java/com/air5005/pagenest/discovery/ui/DiscoveryComponents.kt`
- Create: `app/src/main/java/com/air5005/pagenest/discovery/ui/OnlineSourceLinkPolicy.kt`
- Test: `app/src/test/java/com/air5005/pagenest/discovery/ui/OnlineSourceLinkPolicyTest.kt`
- Test: `app/src/androidTest/java/com/air5005/pagenest/discovery/ui/DiscoveryScreenTest.kt`
- Test: `app/src/androidTest/java/com/air5005/pagenest/discovery/ui/OnlineBookDetailScreenTest.kt`

**Visual contract:** blue-green gradient header; rounded search field; `推荐 / 热门 / 最新 / 来源` tabs; `全部 / 中文 / English` segmented control; category shortcuts; gradient banner; horizontal curated cards; vertical numbered ranking; Material 3 dynamic dark/light compatibility.

- [ ] First write source-link policy tests: only numeric Gutenberg IDs, valid Standard Ebooks slugs and valid Open Library OLIDs produce HTTPS allowlisted pages; traversal, credentials, query injection and unknown sources return null.
- [ ] Write Compose RED tests for loading, content, empty, stale, partial warning, source authorization state, language selection, book selection, detail metadata, rights/format chips, back navigation and large-font semantics.
- [ ] Use Coil lazy cover loading with placeholders and content descriptions; no bulk cover prefetch.
- [ ] Detail page shows source, rights, formats and `查看来源`. If direct acquisitions exist, show a disabled Phase 3 notice instead of invoking a download.
- [ ] Add previews/sample states for light and dark themes.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*OnlineSourceLinkPolicyTest'
.\gradlew.bat :app:assembleDebugAndroidTest
git commit -m "feat: add online discovery and detail screens"
git push origin master
```

---

### Task 5: Integrate four-item home navigation and translations

**Files:**

- Modify: `app/src/main/java/com/wxn/reader/presentation/home/HomeScreen.kt`
- Modify: `app/src/main/java/com/wxn/reader/presentation/home/HomeViewModel.kt` only if tab-state range/migration requires it
- Create: `app/src/main/java/com/wxn/reader/presentation/home/HomeTopLevelDestination.kt`
- Test: `app/src/test/java/com/wxn/reader/presentation/home/HomeTopLevelDestinationTest.kt`
- Test: `app/src/androidTest/java/com/wxn/reader/presentation/home/HomeNavigationTest.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Modify: all other maintained `values-*` string files

- [ ] Replace magic indices with `HomeTopLevelDestination(SHELF=0, DISCOVERY=1, AUDIO=2, MINE=3)` and test stable mapping.
- [ ] Insert Explore icon/`发现` between shelf and audio.
- [ ] Discovery owns its own header; existing shelf search/top bar, audio list and mine page remain unchanged.
- [ ] Back from detail returns to discovery; back at discovery root follows existing activity behavior. Reselecting discovery scrolls/refreshes only through an explicit callback, not a duplicate request.
- [ ] Preserve `showAllBooks`, shelf pager and selection-mode behavior when switching away and back.
- [ ] Add accessibility labels and translations for tabs, filters, source statuses, errors, detail metadata and Phase 3 notice.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*HomeTopLevelDestinationTest' --tests '*DiscoveryViewModelTest'
.\gradlew.bat :app:assembleDebugAndroidTest :app:lintDebug
git commit -m "feat: add discovery to home navigation"
git push origin master
```

---

### Task 6: Device acceptance, checkpoint and versioned GitHub Release

**Files:**

- Modify: `app/build.gradle.kts`
- Create: `docs/testing/online-discovery-phase2.md`
- Modify: `docs/TASK5_RESUME_MANUAL.md`

- [ ] Bump to `versionCode = 11`, `versionName = "1.10.260826"`.
- [ ] Run all JVM tests, debug APK, Android-test APK and Lint. Lint must have 0 errors without adding a baseline.
- [ ] If API 36 emulator is available, install both APKs and run discovery/navigation UI tests; capture light/dark screenshots and verify no fatal crash. Record emulator evidence separately from pending HyperOS 3 ARM64 evidence.
- [ ] Record test counts, screenshots, APK paths, limitations, Open Library/Standard Ebooks policy, and Phase 3 entry in `docs/testing/online-discovery-phase2.md`.
- [ ] Commit and push the checkpoint, fetch `origin/master`, and verify exact HEAD equality.
- [ ] Create annotated tag `pagenest-v1.10.260826`, push it, wait for `.github/workflows/release-apk.yml`, then verify the GitHub Release APK digest against `SHA256SUMS.txt` and package metadata.
- [ ] Commit any remote release evidence update and push `master`.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
git diff --check
git commit -m "release: prepare PageNest 1.10.260826"
git push origin master
git tag -a pagenest-v1.10.260826 -m "PageNest 1.10.260826"
git push origin pagenest-v1.10.260826
```

---

## Phase 2 completion boundary

Phase 2 is complete only when the four-item navigation and discovery/detail screens are usable, all new transports are mocked in tests, Open Library rate/cache rules are enforced, Standard Ebooks remains policy-safe, full verification passes, and GitHub Release `pagenest-v1.10.260826` contains an APK plus matching `SHA256SUMS.txt`.

After Phase 2, create a separate Phase 3 plan for secure download/import. Its mandatory gate covers redirect count and target revalidation, DNS/private-network protection if applicable, 100 MiB size cap, content-type and magic-byte checks, temporary-file fsync, existing `BookImportService`, private-store validation, duplicate handling, cancellation cleanup, then opening the existing reader with saved progress.

## Official references checked for this plan

- Open Library API usage: <https://openlibrary.org/developers/api>
- Open Library Search API: <https://openlibrary.org/dev/docs/api/search>
- Open Library Covers API: <https://openlibrary.org/dev/docs/api/covers>
- Open Library Subjects API: <https://openlibrary.org/dev/docs/api/subjects>
- Project Gutenberg OPDS: <https://www.gutenberg.org/ebooks/offline_catalogs.html>
- Standard Ebooks feeds: <https://standardebooks.org/feeds>
