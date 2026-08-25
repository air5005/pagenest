# PageNest Online Discovery Phase 1 Catalog Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a source-neutral, fully unit-tested online catalog core that reads Gutendex and OPDS feeds, merges duplicate public-domain books, ranks cross-source results, and serves cached results when sources fail.

**Architecture:** Keep transport/parsing inside source adapters and expose only `OnlineCatalogSource` domain values. `OnlineDiscoveryRepository` runs adapters independently, merges results through deterministic pure functions, and stores serialized aggregate pages in a bounded file cache; no UI or book download is introduced in this phase.

**Tech Stack:** Kotlin 2.1.10, Coroutines, Ktor 3.0.0, kotlinx.serialization JSON 1.8.0, secure JAXP DOM parsing, JUnit 4, Ktor MockEngine.

**Spec:** `docs/superpowers/specs/2026-08-25-pagenest-online-discovery-design.md`

## Global Constraints

- Work directly on `master`; every completed task is committed and pushed to `origin/master`.
- Use RED → GREEN → REFACTOR and run the focused test before each task commit.
- `minSdk = 29`, `compileSdk = 36`, `targetSdk = 36`, JDK 17.
- No production test may access a real external API; Ktor MockEngine and committed fixtures are mandatory.
- Only HTTPS acquisition URLs are represented as directly readable.
- Open Library, UI navigation, secure book download/import, and Release version changes are outside Phase 1.
- Logs and exceptions must not contain full titles, query text, acquisition URLs, or response bodies.
- A single malformed or unavailable source must not invalidate successful sources.

---

### Task 1: Define source-neutral catalog contracts

**Files:**

- Create: `app/src/main/java/com/air5005/pagenest/discovery/model/OnlineCatalogModels.kt`
- Create: `app/src/main/java/com/air5005/pagenest/discovery/source/OnlineCatalogSource.kt`
- Test: `app/src/test/java/com/air5005/pagenest/discovery/model/OnlineCatalogModelsTest.kt`

**Interfaces:**

- Consumes: no Phase 1 production types.
- Produces: `CatalogKind`, `CatalogLanguage`, `CatalogRequest`, `CatalogPage`, `OnlineBook`, `SourceBookDetails`, `SourceReference`, `OnlineAcquisition`, `RightsStatus`, `AcquisitionAccess`, `CatalogSourceException`, `OnlineCatalogSource`.

- [ ] **Step 1: Write the failing model-policy tests**

```kotlin
@Test fun `direct reading requires explicit free full access and https`() {
    val valid = acquisition(url = "https://files.example/book.epub", access = FREE_FULL)
    val preview = acquisition(url = "https://files.example/sample.epub", access = PREVIEW)
    val insecure = acquisition(url = "http://files.example/book.epub", access = FREE_FULL)

    assertTrue(valid.canReadDirectly)
    assertFalse(preview.canReadDirectly)
    assertFalse(insecure.canReadDirectly)
}

@Test fun `best acquisition prefers standard ebooks epub then other epub then txt`() {
    val book = onlineBook(
        acquisitions = listOf(
            acquisition(sourceId = "gutenberg", format = TXT),
            acquisition(sourceId = "gutenberg", format = EPUB),
            acquisition(sourceId = "standard-ebooks", format = EPUB),
        ),
    )
    assertEquals("standard-ebooks", book.bestReadableAcquisition()!!.sourceId)
}
```

- [ ] **Step 2: Run RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*OnlineCatalogModelsTest'
```

Expected: compilation fails because the discovery model package does not exist.

- [ ] **Step 3: Implement immutable serializable domain values**

Use these exact public shapes:

```kotlin
@Serializable enum class CatalogKind { RECOMMENDED, POPULAR, LATEST, SUBJECT, SEARCH }
@Serializable enum class CatalogLanguage { ALL, ZH, EN }
@Serializable enum class OnlineBookFormat { EPUB, TXT, HTML, PDF, UNKNOWN }
@Serializable enum class RightsStatus { PUBLIC_DOMAIN, FREE_FULL, PREVIEW_ONLY, BORROW_ONLY, UNKNOWN }
@Serializable enum class AcquisitionAccess { FREE_FULL, PREVIEW, BORROW, EXTERNAL }

@Serializable data class CatalogRequest(
    val kind: CatalogKind,
    val language: CatalogLanguage = CatalogLanguage.ALL,
    val subject: String? = null,
    val query: String? = null,
    val pageToken: String? = null,
    val pageSize: Int = 20,
)

@Serializable data class OnlineAcquisition(
    val sourceId: String,
    val format: OnlineBookFormat,
    val url: String,
    val access: AcquisitionAccess,
    val qualityPriority: Int,
) {
    val canReadDirectly: Boolean
        get() = access == AcquisitionAccess.FREE_FULL && url.startsWith("https://")
}

@Serializable data class SourceReference(val sourceId: String, val sourceBookId: String)

@Serializable data class OnlineBook(
    val stableKey: String,
    val title: String,
    val authors: List<String>,
    val summary: String?,
    val languages: List<String>,
    val subjects: List<String>,
    val coverUrl: String?,
    val sourceRank: Int,
    val popularity: Long?,
    val catalogUpdatedAtEpochMillis: Long?,
    val rightsStatus: RightsStatus,
    val sourceReferences: List<SourceReference>,
    val acquisitions: List<OnlineAcquisition>,
)

@Serializable data class CatalogPage(
    val books: List<OnlineBook>,
    val nextPageToken: String?,
    val sourceWarnings: List<String> = emptyList(),
)

@Serializable data class SourceBookDetails(
    val book: OnlineBook,
    val related: List<SourceReference> = emptyList(),
)

enum class CatalogSourceFailure { NETWORK, TIMEOUT, HTTP, RESPONSE_TOO_LARGE, MALFORMED, UNTRUSTED_URL }
class CatalogSourceException(val failure: CatalogSourceFailure) : RuntimeException(failure.name)

interface OnlineCatalogSource {
    val id: String
    suspend fun browse(request: CatalogRequest): CatalogPage
    suspend fun details(reference: SourceReference): SourceBookDetails?
}
```

`bestReadableAcquisition()` filters `canReadDirectly`, then sorts by `qualityPriority`, with Standard Ebooks EPUB assigned the lowest priority number by adapters.

- [ ] **Step 4: Run GREEN**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*OnlineCatalogModelsTest'
```

Expected: all model tests pass.

- [ ] **Step 5: Commit and push**

```powershell
git add app/src/main/java/com/air5005/pagenest/discovery/model app/src/main/java/com/air5005/pagenest/discovery/source app/src/test/java/com/air5005/pagenest/discovery/model
git commit -m "feat: define online catalog contracts"
git push origin master
```

### Task 2: Implement the Gutendex adapter

**Files:**

- Create: `app/src/main/java/com/air5005/pagenest/discovery/source/gutendex/GutendexDtos.kt`
- Create: `app/src/main/java/com/air5005/pagenest/discovery/source/gutendex/GutendexCatalogSource.kt`
- Create: `app/src/test/resources/discovery/gutendex/popular.json`
- Create: `app/src/test/resources/discovery/gutendex/malformed.json`
- Test: `app/src/test/java/com/air5005/pagenest/discovery/source/gutendex/GutendexCatalogSourceTest.kt`

**Interfaces:**

- Consumes: Task 1 `OnlineCatalogSource` and models; injected Ktor `HttpClient`; configurable HTTPS base URL.
- Produces: `GutendexCatalogSource(client, baseUrl, json)`.

- [ ] **Step 1: Write failing MockEngine tests**

```kotlin
@Test fun `popular request maps metadata and only secure full acquisitions`() = runTest {
    val source = sourceReturning(resource("discovery/gutendex/popular.json"))
    val page = source.browse(CatalogRequest(CatalogKind.POPULAR, CatalogLanguage.ZH))

    assertEquals("gutendex", page.books.single().sourceReferences.single().sourceId)
    assertEquals(listOf("zh"), page.books.single().languages)
    assertEquals(OnlineBookFormat.EPUB, page.books.single().bestReadableAcquisition()!!.format)
    assertEquals("/books?languages=zh&sort=popular&page=1", captured.encodedPathAndQuery)
}

@Test fun `latest means descending catalog ids and malformed body is source failure`() = runTest {
    assertRequest(CatalogKind.LATEST, expectedQuery = "sort=descending")
    assertFailsWith<CatalogSourceException> { malformedSource().browse(request()) }
}
```

Also cover search encoding, subject encoding, pagination URL rejection when host changes, ignored HTTP links, unknown fields, non-2xx status, 2 MiB response cap, and no response body in exception messages.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*GutendexCatalogSourceTest'
```

Expected: compilation fails because the adapter does not exist.

- [ ] **Step 3: Implement DTO mapping and bounded HTTP**

Use `@Serializable` DTOs matching Gutendex `count`, `next`, `previous`, `results`, and nested book/person/format values. Configure `Json { ignoreUnknownKeys = true; explicitNulls = false }`.

Build requests with Ktor `URLBuilder`; never concatenate query text. Accept only the configured HTTPS host for base and `next`. Read bytes with a hard 2 MiB maximum before decoding. Map:

```kotlin
sourceBookId = dto.id.toString()
stableKey = "gutenberg:${dto.id}"
rightsStatus = if (dto.copyright == false) PUBLIC_DOMAIN else UNKNOWN
access = if (dto.copyright == false) FREE_FULL else EXTERNAL
popularity = dto.downloadCount
sourceRank = index + 1
```

Only create EPUB/TXT acquisitions from HTTPS URLs and explicit public-domain entries. Use quality priorities: EPUB `20`, TXT `30`, HTML `90`. `details()` fetches `/books/{id}`, uses the same mapper, and wraps it in `SourceBookDetails`.

- [ ] **Step 4: Run GREEN and app regression**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*GutendexCatalogSourceTest' --tests '*OnlineCatalogModelsTest'
```

Expected: all focused tests pass without network access.

- [ ] **Step 5: Commit and push**

```powershell
git add app/src/main/java/com/air5005/pagenest/discovery/source/gutendex app/src/test/java/com/air5005/pagenest/discovery/source/gutendex app/src/test/resources/discovery/gutendex
git commit -m "feat: read gutendex public-domain catalog"
git push origin master
```

### Task 3: Parse secure OPDS and add Gutenberg/Standard Ebooks adapters

**Files:**

- Create: `app/src/main/java/com/air5005/pagenest/discovery/source/opds/OpdsFeedParser.kt`
- Create: `app/src/main/java/com/air5005/pagenest/discovery/source/opds/OpdsCatalogSource.kt`
- Create: `app/src/main/java/com/air5005/pagenest/discovery/source/opds/KnownOpdsSources.kt`
- Create: `app/src/test/resources/discovery/opds/gutenberg-popular.xml`
- Create: `app/src/test/resources/discovery/opds/standard-ebooks.xml`
- Create: `app/src/test/resources/discovery/opds/xxe.xml`
- Test: `app/src/test/java/com/air5005/pagenest/discovery/source/opds/OpdsFeedParserTest.kt`
- Test: `app/src/test/java/com/air5005/pagenest/discovery/source/opds/OpdsCatalogSourceTest.kt`

**Interfaces:**

- Consumes: Task 1 models and source contract; injected Ktor client.
- Produces: `OpdsFeedParser.parse(xml): ParsedOpdsFeed`, `OpdsCatalogSource`, `KnownOpdsSources.gutenberg(...)`, `KnownOpdsSources.standardEbooks(...)`.

- [ ] **Step 1: Write failing secure-parser tests**

```kotlin
@Test fun `parses acquisition links cover author language and next page`() {
    val feed = parser.parse(resource("discovery/opds/standard-ebooks.xml"))
    assertEquals("https://standardebooks.org/ebooks/page-2", feed.nextUrl)
    assertEquals("application/epub+zip", feed.entries.single().acquisitions.single().type)
}

@Test fun `doctype and external entities are rejected`() {
    assertFailsWith<OpdsParseException> {
        parser.parse(resource("discovery/opds/xxe.xml"))
    }
}
```

Adapter tests must cover Gutenberg ID normalization to `gutenberg:<id>`, Standard Ebooks EPUB priority `10`, HTTPS filtering, untrusted next-host rejection, page-size truncation, partial metadata, and XML/body-size failures without body logging.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*OpdsFeedParserTest' --tests '*OpdsCatalogSourceTest'
```

Expected: compilation fails because OPDS types do not exist.

- [ ] **Step 3: Implement hardened DOM parsing**

Create a fresh `DocumentBuilderFactory` per parse and set all of:

```kotlin
factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
factory.isXIncludeAware = false
factory.isExpandEntityReferences = false
```

Parse Atom/OPDS by namespace-aware local names. Treat links whose `rel` contains `http://opds-spec.org/acquisition` or equals `http://opds-spec.org/acquisition/open-access` as acquisition candidates. Do not resolve arbitrary schemes.

`OpdsCatalogSource` receives a source configuration containing `id`, `baseUrl`, `allowedHosts`, `rightsStatus`, `epubPriority`, and request-path mapper. Gutenberg uses popular/download and new-book feeds; Standard Ebooks uses its navigation/acquisition feed. Both accept only HTTPS links on configured hosts.

- [ ] **Step 4: Run GREEN**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*OpdsFeedParserTest' --tests '*OpdsCatalogSourceTest' --tests '*OnlineCatalogModelsTest'
```

Expected: parser and both adapter configurations pass.

- [ ] **Step 5: Commit and push**

```powershell
git add app/src/main/java/com/air5005/pagenest/discovery/source/opds app/src/test/java/com/air5005/pagenest/discovery/source/opds app/src/test/resources/discovery/opds
git commit -m "feat: add secure public-domain opds catalogs"
git push origin master
```

### Task 4: Merge duplicates and rank sources deterministically

**Files:**

- Create: `app/src/main/java/com/air5005/pagenest/discovery/aggregate/OnlineBookFingerprint.kt`
- Create: `app/src/main/java/com/air5005/pagenest/discovery/aggregate/OnlineBookMerger.kt`
- Create: `app/src/main/java/com/air5005/pagenest/discovery/aggregate/ReciprocalRankFusion.kt`
- Test: `app/src/test/java/com/air5005/pagenest/discovery/aggregate/OnlineBookMergerTest.kt`
- Test: `app/src/test/java/com/air5005/pagenest/discovery/aggregate/ReciprocalRankFusionTest.kt`

**Interfaces:**

- Consumes: Task 1 `OnlineBook` and source pages.
- Produces: `OnlineBookMerger.merge(pages): List<OnlineBook>` and `ReciprocalRankFusion.rank(sourceLists, k = 60): List<OnlineBook>`.

- [ ] **Step 1: Write failing merge/rank tests**

```kotlin
@Test fun `gutenberg json and opds references merge without losing fallback links`() {
    val merged = merger.merge(listOf(gutendexPage(id = "1342"), gutenbergOpdsPage(id = "1342")))
    assertEquals(1, merged.size)
    assertEquals(setOf("gutendex", "gutenberg-opds"), merged.single().sourceReferences.map { it.sourceId }.toSet())
}

@Test fun `standard ebooks epub wins but gutenberg txt remains fallback`() {
    val merged = merger.merge(listOf(standardPride(), gutenbergPride())).single()
    assertEquals("standard-ebooks", merged.bestReadableAcquisition()!!.sourceId)
    assertTrue(merged.acquisitions.any { it.format == OnlineBookFormat.TXT })
}

@Test fun `same title with different authors is not merged`() {
    assertEquals(2, merger.merge(listOf(page(book("Home", "A")), page(book("Home", "B")))).size)
}
```

RRF tests must prove source raw download counts are never compared, input order does not change ties, and `k = 60` uses `1.0 / (k + rank)`.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*OnlineBookMergerTest' --tests '*ReciprocalRankFusionTest'
```

Expected: compilation fails because aggregate classes do not exist.

- [ ] **Step 3: Implement conservative fingerprints and RRF**

Normalize Unicode with NFKC, lowercase using `Locale.ROOT`, collapse whitespace and punctuation, but never transliterate languages. Merge by shared explicit IDs first. Only use `normalizedTitle|normalizedFirstAuthor|primaryLanguage` when all fields are nonblank.

When merging, choose the longest nonblank summary, HTTPS cover from the highest-quality source, union identifiers/subjects/acquisitions, strongest explicit rights status, and smallest source rank. Deduplicate acquisitions by normalized URL without logging it.

RRF groups each source's ranked list, sums scores by merged stable key, then sorts by descending score, best readable acquisition priority, normalized title and stable key for deterministic ties.

- [ ] **Step 4: Run GREEN**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*OnlineBookMergerTest' --tests '*ReciprocalRankFusionTest'
```

Expected: all merge and rank cases pass.

- [ ] **Step 5: Commit and push**

```powershell
git add app/src/main/java/com/air5005/pagenest/discovery/aggregate app/src/test/java/com/air5005/pagenest/discovery/aggregate
git commit -m "feat: merge and rank online catalogs"
git push origin master
```

### Task 5: Add bounded aggregate cache and resilient repository

**Files:**

- Create: `app/src/main/java/com/air5005/pagenest/discovery/cache/CatalogCache.kt`
- Create: `app/src/main/java/com/air5005/pagenest/discovery/cache/FileCatalogCache.kt`
- Create: `app/src/main/java/com/air5005/pagenest/discovery/repository/OnlineDiscoveryRepository.kt`
- Test: `app/src/test/java/com/air5005/pagenest/discovery/cache/FileCatalogCacheTest.kt`
- Test: `app/src/test/java/com/air5005/pagenest/discovery/repository/OnlineDiscoveryRepositoryTest.kt`

**Interfaces:**

- Consumes: Tasks 1–4 source contract, aggregate functions, and serializable catalog values.
- Produces: `CatalogCache`, `FileCatalogCache`, `OnlineDiscoveryRepository.discover(request): DiscoveryResult`.

- [ ] **Step 1: Write failing cache/repository tests**

```kotlin
@Test fun `one failed source returns successful books and stable warning`() = runTest {
    val result = repository(sources = listOf(successSource(), failingSource("standard-ebooks"))).discover(popular())
    assertEquals(1, result.page.books.size)
    assertEquals(listOf("standard-ebooks"), result.unavailableSourceIds)
    assertFalse(result.fromStaleCache)
}

@Test fun `all failed sources return stale cache without deleting it`() = runTest {
    cache.put(key, cachedAt = now - 31.minutes, page = cachedPage())
    val result = repository(allFailing(), cache, now).discover(popular())
    assertTrue(result.fromStaleCache)
    assertEquals(cachedPage().books, result.page.books)
}

@Test fun `corrupt cache is deleted and never returned`() = runTest {
    writeRawCache("not-json")
    assertNull(cache.get(key, now))
    assertFalse(cacheFile.exists())
}
```

Cover 30-minute popular/recommended TTL, 60-minute latest/subject TTL, 24-hour details TTL reservation, atomic temp publication, hashed filenames with no query text, 4 MiB total cache cap, cancellation propagation, 8-second per-source timeout, deterministic warning order, and no title/query/URL in exceptions.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*FileCatalogCacheTest' --tests '*OnlineDiscoveryRepositoryTest'
```

Expected: compilation fails because cache and repository classes do not exist.

- [ ] **Step 3: Implement cache and partial-success orchestration**

Use these exact result contracts:

```kotlin
data class CachedCatalogPage(val cachedAtEpochMillis: Long, val page: CatalogPage)
interface CatalogCache {
    suspend fun get(key: String): CachedCatalogPage?
    suspend fun put(key: String, value: CachedCatalogPage)
    suspend fun remove(key: String)
}

data class DiscoveryResult(
    val page: CatalogPage,
    val fromStaleCache: Boolean,
    val unavailableSourceIds: List<String>,
)
```

Hash the canonical serialized `CatalogRequest` with SHA-256 for cache filenames. Write `<hash>.tmp`, fsync/close, then atomically replace `<hash>.json`. Enforce the 4 MiB cap by oldest `cachedAtEpochMillis`, never by user-visible names.

Repository flow:

1. Return a fresh cached aggregate immediately when within TTL.
2. Otherwise query sources with `supervisorScope`, `withTimeout(8_000)`, and stable source order.
3. Merge and rank successful pages; cache nonempty aggregate.
4. If every source fails, return stale cache with all source IDs marked unavailable.
5. If no source and no cache succeeds, return an empty page with stable warnings; do not throw transport text to UI.
6. Rethrow `CancellationException` unchanged.

- [ ] **Step 4: Run GREEN and full Phase 1 gate**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*FileCatalogCacheTest' --tests '*OnlineDiscoveryRepositoryTest'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

Expected: focused tests pass; all app tests pass; both APKs build; Lint has 0 errors.

- [ ] **Step 5: Record checkpoint, commit, and push**

Create `docs/testing/online-discovery-phase1.md` with fixture/parser counts, focused/full test results, APK path, Lint error count, known real-network non-coverage, and next Phase 2 entry.

```powershell
git diff --check
git add app/src/main/java/com/air5005/pagenest/discovery/cache app/src/main/java/com/air5005/pagenest/discovery/repository app/src/test/java/com/air5005/pagenest/discovery/cache app/src/test/java/com/air5005/pagenest/discovery/repository docs/testing/online-discovery-phase1.md docs/TASK5_RESUME_MANUAL.md
git commit -m "feat: cache resilient online discovery results"
git push origin master
git fetch origin master
git rev-parse HEAD
git rev-parse origin/master
```

Expected: local `HEAD` and `origin/master` are identical and the worktree is clean.

---

## Phase 1 Completion Boundary

Phase 1 is complete only when all five task commits are pushed, all parser/aggregate/cache tests use local fixtures, the full app gate passes, and the checkpoint documents that no real external service was required. Do not add the navigation item or expose unfinished discovery UI in this phase.

After Phase 1, create the Phase 2 plan for Open Library enrichment, Compose discovery/detail screens, four-item navigation, and screen-level tests. Secure download/import remains a separate Phase 3 plan so its URL, redirect, size and private-store security gate can be reviewed independently.
