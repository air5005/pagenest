# PageNest Online Discovery Phase 3 Secure Import Implementation Plan

> **Execution:** Use Superpower TDD on `master`. Every task begins with focused RED tests and ends with focused regression, `git diff --check`, commit and push before the next task.

**Goal:** Safely acquire explicitly free EPUB/TXT/PDF files from allowlisted discovery sources, reuse the existing private `BookImportService`, support add-to-shelf/start-reading/cancel, and archive PageNest 1.11.260826 on GitHub.

**Architecture:** A pure URL/address policy guards a no-redirect production transport. `SecureBookDownloader` streams into an app-private staging file with a 100 MiB hard limit and format checks. `OnlineBookImportCoordinator` owns fallback, ledger, per-book concurrency and `BookImportService` handoff. `DiscoveryViewModel` exposes typed acquisition state; `HomeScreen` remains the owner of reader navigation.

**Stack:** Kotlin, coroutines/Flow, OkHttp or Ktor OkHttp transport, Hilt, existing `BookImportService`, Jetpack Compose Material 3, JUnit, MockWebServer/MockEngine, Robolectric and Compose UI tests.

## Global constraints

- Work directly on `master`; commit and push after every completed task.
- Ordinary tests never access external services.
- Never weaken or duplicate `BookImportService`, `PrivateBookStore`, SHA-256 coordination, protection inspection or catalog transaction boundaries.
- Never log/display full acquisition URLs, response bodies, queries, private paths or uncontrolled exception messages.
- Standard Ebooks remains disabled until official authorization is configured.
- Open Library never supplies acquisition links in this phase.
- Only `FREE_FULL + HTTPS` EPUB/TXT/PDF candidates are eligible; HTML remains external-view only.
- Download limit is 100 MiB over actual streamed bytes; redirects are manual and at most 3.
- Every new visible string is localized in every maintained locale.

### Task 1: Implement pure download URL and public-address policies

**Files:**

- Create `app/src/main/java/com/air5005/pagenest/discovery/download/DownloadUrlPolicy.kt`
- Create `app/src/main/java/com/air5005/pagenest/discovery/download/PublicAddressPolicy.kt`
- Test `DownloadUrlPolicyTest.kt`
- Test `PublicAddressPolicyTest.kt`

- [x] RED tests bind each source to exact HTTPS hosts and port 443.
- [x] Reject credentials, fragments, control characters, unknown sources/hosts, protocol downgrade, excessive URLs and unsafe relative/absolute redirect targets.
- [x] RED tests reject loopback, any-local, link-local, site-local, multicast, IPv4 private/shared/documentation/benchmark/reserved and IPv6 unique-local/documentation ranges.
- [x] Reject the entire DNS answer if any address is not public; never silently drop unsafe members.
- [x] Add a production DNS adapter that returns only a fully accepted resolution to the same HTTP client that connects.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*DownloadUrlPolicyTest' --tests '*PublicAddressPolicyTest'
git commit -m "feat: enforce online book network policy"
git push origin master
```

### Task 2: Build bounded staged downloader and format validator

**Files:**

- Create `SecureBookDownloader.kt`, `BookDownloadTransport.kt`, `DownloadedBookValidator.kt`, `DownloadModels.kt`
- Create production transport/DI provider under `discovery/download`
- Test downloader, validator and production client contracts with fixtures.

- [x] First prove manual redirects, maximum 3 hops, per-hop policy, status mapping, 10/30/120 second timeouts and disabled automatic retries.
- [x] Stream to random app-private `.part` files; validate `Content-Length` and actual bytes against 100 MiB.
- [x] Emit monotonic progress without title/URL; cancellation closes body/output and removes staging file.
- [x] Flush and fsync before validation; cleanup on sync/validation failure.
- [x] Validate EPUB ZIP + stored mimetype entry, PDF `%PDF-`, and text BOM/NUL/binary heuristic; reject explicit MIME conflicts.
- [x] Startup cleanup only removes old regular `.part` entries inside the trusted staging directory and never follows links.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*SecureBookDownloaderTest' --tests '*DownloadedBookValidatorTest' --tests '*BookDownloadClientContractTest'
git commit -m "feat: securely stage online book downloads"
git push origin master
```

### Task 3: Coordinate fallback, ledger and existing private import

**Files:**

- Create `OnlineBookImportCoordinator.kt`, `OnlineImportLedger.kt`, `OnlineImportModels.kt`
- Add small adapters for `BookImportService` and local book existence lookup.
- Test fallback, concurrency, ledger and import handoff.

- [x] Filter/sort candidates using existing eligibility/priority and only supported EPUB/TXT/PDF.
- [x] Retry each candidate at most once only for typed recoverable failures; stop on unsafe, too-large, rights or format-spoof failures.
- [x] Always delete staging in `finally`, including `BookImportService` cancellation/failure.
- [x] Map `Imported` and `Duplicate` to a local book ID without changing import semantics.
- [x] Persist stableKey-to-bookId atomically without URL; validate local book existence on every hit and remove stale mappings.
- [x] Serialize the same stable key while allowing different keys; cancellation of one waiter must not corrupt the owner operation.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*OnlineBookImportCoordinatorTest' --tests '*OnlineImportLedgerTest' --tests '*BookImportServiceTest'
git commit -m "feat: import downloaded books into private library"
git push origin master
```

### Task 4: Add deterministic acquisition state to DiscoveryViewModel

**Files:**

- Modify `DiscoveryUiState.kt`, `DiscoveryViewModel.kt`, `DiscoveryModule.kt`
- Extend `DiscoveryViewModelTest.kt`; add DI contract tests.

- [ ] Add typed idle/downloading/validating/importing/added/error states and byte progress.
- [ ] Add `addToShelf`, `startReading`, `cancelAcquisition`, and one-shot local book ID event.
- [ ] Prevent double taps/parallel work for the selected book; close detail cancels active work.
- [ ] Add-to-shelf stays on detail; start-reading emits book ID exactly once.
- [ ] Existing ledger hit skips transport and reaches added/open state.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*DiscoveryViewModelTest' --tests '*DiscoveryModuleContractTest'
git commit -m "feat: model online book import progress"
git push origin master
```

### Task 5: Add detail actions, progress, cancel and reader handoff

**Files:**

- Modify `OnlineBookDetailScreen.kt`, `DiscoveryScreen.kt`, `HomeScreen.kt`
- Modify all locale discovery resource files.
- Extend `OnlineBookDetailScreenTest.kt`, `HomeNavigationTest.kt`.

- [ ] Show actions only for eligible acquisitions; inaccessible details remain source-only.
- [ ] Show determinate/indeterminate progress, safe localized phases/errors and cancel.
- [ ] Show persistent “已加入书架” for success; add action does not navigate.
- [ ] Start action passes book ID to `HomeViewModel.openDashboardBook`; PDF/EPUB/TXT use existing routes.
- [ ] Back/close/cancel behavior is deterministic and large-font semantics remain usable.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*DiscoveryViewModelTest' :app:assembleDebugAndroidTest :app:lintDebug
git commit -m "feat: add one-tap online book import actions"
git push origin master
```

### Task 6: Device acceptance, checkpoint and PageNest 1.11.260826 Release

**Files:**

- Modify `app/build.gradle.kts` to versionCode 12/versionName `1.11.260826`.
- Create `docs/testing/online-discovery-phase3.md`.
- Update `docs/TASK5_RESUME_MANUAL.md`.

- [ ] Run all JVM tests, debug APK, Android-test APK and Lint; Lint errors must remain 0.
- [ ] If API 36 emulator is available, run fixture-backed TXT download→import→open acceptance and discovery UI tests; never replace ARM64 conclusions with x86_64.
- [ ] If HyperOS 3 ARM64 is connected, run EPUB/TXT/PDF and weak-network cancellation matrix; otherwise explicitly leave it pending.
- [ ] Record exact counts, digests, package metadata, limitations and next entry.
- [ ] Commit/push checkpoint and prove local HEAD equals `origin/master`.
- [ ] Tag `pagenest-v1.11.260826`, wait for GitHub Actions, and verify downloaded Release APK against `SHA256SUMS.txt` and package metadata.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
git commit -m "release: prepare PageNest 1.11.260826"
git push origin master
git tag -a pagenest-v1.11.260826 -m "PageNest 1.11.260826"
git push origin pagenest-v1.11.260826
```

## Completion boundary

Phase 3 is complete only when eligible online EPUB/TXT/PDF downloads are policy-safe, bounded, cancellable, imported through the existing private library, duplicate-safe, navigable through the existing reader routes, fully tested without live-network dependencies, checkpointed on `master`, and archived in a verified GitHub Release.
