# Task 6 Report: Validated import orchestration with stable results

## Status

DONE.

- Baseline: `1148c42f1ea16f2f7f20d0065bffa9a07d682e1c`
- Commit: `cf77f06` (`feat: orchestrate safe local book imports`)
- Push: not performed

## Implementation

- Added `ImportRequest`, the `ImportResult` sealed result family, the five specified rejection codes, `BookMetadataParser`, and `BookImportCatalog`.
- Added `BookImportService.execute()` with the fail-fast order: filename format normalization, input open, private atomic copy, protection inspection, SHA-256 catalog lookup, metadata parse, and atomic catalog insert.
- Calls each orchestration dependency at most once on the success path. Unsupported names return before opening the source. Every later rejection stops all downstream parsing/catalog work.
- Maps input-open exceptions to `UNREADABLE`, private-copy exceptions to `STORAGE_FAILED`, protected/unreadable inspection outcomes to their matching rejection, parser null/exception outcomes to `PARSE_FAILED`, and catalog lookup/insert exceptions to `STORAGE_FAILED`.
- Requires `BookImportCatalog.insert()` to be atomic: if it throws, no catalog record may become visible. Catalog insertion is the final operation, so no validation, protection, duplicate, parsing, or cleanup failure can create an erroneous record.
- Deletes only a newly published (`wasExisting == false`) private file after protection, parsing, catalog, input-close, or cancellation failure. An existing duplicate is never deleted. If ordinary rejection cleanup fails, the result becomes `STORAGE_FAILED` rather than claiming successful cleanup.
- Treats `PublishedBookCleanupException.storedBook` as the already-published result and continues orchestration, preserving Task 5's published-state semantics.
- Propagates `CancellationException` from every stage. A newly published file is cleaned before rethrow; cleanup failure is suppressed on the original cancellation. Cancellation raised while closing a source after an earlier copy failure is also propagated rather than converted to a stable rejection.
- Closes the `InputStream` opened by the request. `PrivateBookStore` remains unchanged and continues to treat its input as caller-owned.
- Did not add Android `ContentResolver`, HandyReader parser/database adapters, Hilt wiring, or UI behavior; those remain Task 7 scope.

## TDD evidence

### Main RED

Only `BookImportServiceTest.kt` existed when this command was run with JDK 17:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*BookImportServiceTest'
```

The build reached `:app:compileDebugUnitTestKotlin` and failed with unresolved references to `BookImportService`, `ImportRequest`, `ImportResult`, `ImportRejection`, `BookMetadataParser`, and `BookImportCatalog`. This was the expected feature-missing RED. An earlier attempt used Android Studio's JDK 25 and stopped before compilation with the Gradle/JDK compatibility error `25.0.2`; that environmental precondition was not counted as RED.

After the minimal implementation, the complete targeted suite reached 14 tests, 0 failures, and 0 errors.

### Cancellation cleanup RED

Self-review identified an uncovered boundary: a source read could fail normally and then source `close()` could throw `CancellationException`. A focused test was added before the fix:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*BookImportServiceTest.cancellationWhileClosingAFailedCopyPropagatesInsteadOfMappingToStorageFailure'
```

It failed 1 test because no `CancellationException` was thrown. The close-failure helper was then changed to prioritize cancellation and attach the earlier copy failure as suppressed context. The same focused command passed 1 test, and the complete targeted suite passed 15 tests.

## Test coverage

The 15 orchestration tests cover:

- unsupported filename short-circuiting before source open and every dependency;
- source-open `UNREADABLE` mapping without private-root creation;
- copy/read `STORAGE_FAILED` mapping and Task 5 partial cleanup;
- `PROTECTED` and inspection `UNREADABLE` cleanup before catalog lookup;
- duplicate SHA-256 returning the existing ID without parse/insert and preserving the existing private file;
- parser null and parser exception mapping with new-copy cleanup;
- catalog lookup and insert failure mapping with no inserted record;
- cleanup failure becoming `STORAGE_FAILED`;
- parser cancellation cleanup and propagation;
- cancellation during source close after failed copy;
- continued orchestration from `PublishedBookCleanupException.storedBook`;
- exact successful ordering, single dependency calls, private file passed to the parser, SHA-256 passed to the catalog, and the parser-produced private URI persisted unchanged.

## Final verification

Targeted Task 6 suite:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*BookImportServiceTest'
```

Result: `BUILD SUCCESSFUL in 45s`; JUnit XML reports 15 tests, 0 failures, 0 errors, and 0 skipped.

Full app regression, Debug APK, and lint:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Result: `BUILD SUCCESSFUL in 3m 42s`; 333 actionable tasks, 41 executed and 292 up-to-date. Five JUnit suites report 86 tests, 0 failures, 0 errors, and 0 skipped. `app-debug.apk` was generated. Lint reports 0 errors and 127 pre-existing repository warnings; no Task 6 source warning was reported.

Immediately before commit, `git diff --cached --check` produced no whitespace errors. The Conventional Commit was created locally and was not pushed.

## Risks and notes

- Task 6 uses `Files.deleteIfExists()` for post-publication rejection cleanup because Task 5's public API intentionally exposes storage, not catalog-aware deletion. A cleanup exception is never hidden as a successful domain rejection. The existing Task 5 threat model excludes arbitrary malicious code already sharing the app UID.
- A `PublishedBookCleanupException` can report a durable final file while also reporting unresolved temporary cleanup. Per the approved Task 6 contract, orchestration continues with its `storedBook`; Task 6 cannot directly repair Task 5's private temporary entry through the public API.
- `BookImportCatalog.insert()` must honor its documented atomic-throw contract in the Task 7 adapter. Task 6 cannot compensate a database implementation that commits a visible record and then throws because the specified catalog interface deliberately has no delete/rollback operation.

---

## Fix round 1

### Status

DONE.

- Fix commit: `6fbf050` (`fix: coordinate atomic book imports`)
- Push: not performed

### Contract and implementation changes

- Added injectable `BookImportCoordinator.withHashLock(sha256, block)` and a bounded, coroutine-safe `InProcessBookImportCoordinator`. Its contract requires Task 7 to combine process-local coordination with an app-private per-SHA OS lock for cross-process callers.
- Moved private-file revalidation, protection inspection, SHA lookup, parsing, atomic catalog publication, and expected-failure cleanup into the SHA critical section. A missing/non-regular stored file returns `STORAGE_FAILED` before parsing or catalog work.
- Replaced the non-atomic catalog shape with `findBySha256(): CatalogMatch?` and `insertOrGet(): CatalogWriteResult.Inserted|Existing`. Existing results carry the catalog row's private file as well as its ID. Task 7 is constrained to a Room SHA unique index and transaction returning the generated database ID, never an inserted-count surrogate.
- Compared stored and catalog files by normalized canonical paths. A same-path concurrent winner is preserved; a different-path newly created redundant copy is deleted; `wasExisting == true` is never deleted. Comparison errors fail closed as `STORAGE_FAILED` without deleting.
- Forced every parsed `Book.filePath` to the stored private file URI before catalog publication, so a parser-supplied source/content URI cannot escape into the bookshelf.
- Explicitly propagated cancellation from the production protection inspector, coordinator, catalog, and delete boundary. `PublishedBookCleanupException` with a cancellation cause now closes the source, deletes only a new final file, preserves close-cancellation priority, and suppresses cleanup failures.
- Closed the source for every throwable from `PrivateBookStore.store()`. Expected `Exception`/`LinkageError` failures map to `STORAGE_FAILED`; non-domain fatal throwables remain fatal after close. A close cancellation takes priority and retains the earlier failure as suppressed context.
- Kept Task 7 Android, Room, Hilt, ContentResolver, UI, and OS-lock adapter implementation out of this task; only their production constraints were updated in the integration plan.

### RED → GREEN evidence

1. The first fix-round target run failed at `:app:compileDebugUnitTestKotlin` with unresolved `BookImportCoordinator`, `InProcessBookImportCoordinator`, `CatalogWriteResult`, constructor arguments, and `insertOrGet` overrides. Adding the coordinator and atomic catalog contracts made the target suite GREEN.
2. A deterministic reverse-schedule concurrency test then reproduced the critical dangling-URI bug: the `wasExisting == false` owner deleted the same private file already published by the existing-side catalog winner. Extending catalog matches with `privateFile` and comparing canonical paths changed that focused test from RED to GREEN.
3. Self-review cleanup tests were added before their fixes. Delete returning `false` and published cancellation combined with a source-close cancellation both failed first, then passed after honoring the Boolean result and ensuring cleanup precedes propagation.
4. Two further cancellation ownership tests failed first: source-close cancellation did not take priority over store cancellation, and a `false` cancellation-cleanup result was not reported. Minimal failure-selection/suppression changes made both focused tests GREEN.

### Coverage added

- Two separate services sharing a real coordinator and private root: owner-first success, existing-side-first insertion, and owner parse failure followed by existing-side missing-file validation.
- One atomic row and one live private URI under concurrent same-SHA imports; no duplicate parsing/insertion and no dangling catalog URI.
- Same bytes imported through different extensions, deleting only the different-path redundant new copy.
- Atomic `insertOrGet` winner handling, cleanup exception/false handling, and fail-closed canonical comparison errors.
- Parser source/content URI replacement with the stored private URI.
- Cancellation from coordinator acquisition, catalog lookup, deletion, published cleanup, source close, and production protection probes.
- `UnsatisfiedLinkError` during storage closes the source and maps to `STORAGE_FAILED`.

### Final verification

Targeted Task 6 production/tests:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*BookImportServiceTest' --tests '*DefaultBookProtectionInspectorTest'
```

Result: `BUILD SUCCESSFUL in 48s`; JUnit XML reports 31 service tests plus 7 production-inspector tests, 0 failures, and 0 errors.

Full app regression, Debug APK, and lint:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Result: `BUILD SUCCESSFUL in 3m 40s`; 333 actionable tasks, 41 executed and 292 up-to-date. Five JUnit suites report 103 tests, 0 failures, 0 errors, and 0 skipped. `app-debug.apk` was generated. Lint reports 0 errors and 127 pre-existing warnings.

---

## Fix round 2

### Status

DONE.

- Fix commit: this commit (`fix: harden published import lifecycle`)
- Push: not performed

### Contract and implementation changes

- Refactored the post-store lifecycle so a returned `StoredBook`, including the one carried by `PublishedBookCleanupException`, is never deleted outside `BookImportCoordinator.withHashLock`. Source close now occurs inside the locked lifecycle. Lock-acquisition failure/cancellation closes the caller-owned source but deliberately keeps the published orphan because another process may already have committed a row for the same file.
- Made every uncertain failure cleanup catalog-aware. The service re-queries the SHA while holding the hash lock and preserves a same-file winner; it deletes only a different-path copy created by this request. This also protects rows committed immediately before an adapter throws or cancellation becomes visible.
- Added `PrivateBookFileValidator` with the only service-visible outcomes `SAME`, `DIFFERENT`, and `INVALID`. Missing, outside-root, symbolic-link, and non-regular catalog candidates fail closed as `STORAGE_FAILED` without deletion. The production contract requires trusted-root descriptor-relative, no-follow opens and live regular-file identity comparison; canonical path comparison is explicitly insufficient.
- Added identity-guarded cause/suppressed throwable-graph traversal. Nested cancellation now wins at input open, store/source close, coordinator, protection, catalog, parser, validator, and deletion boundaries while retaining the original failure as suppressed context. Cycles terminate safely.
- Captured every `Throwable` from source close. Expected `Exception`/`LinkageError` failures map to `STORAGE_FAILED` after locked safe cleanup, cancellation propagates, and fatal errors are rethrown with cleanup/acquisition context suppressed. A fatal acquisition-time close never deletes the published file.
- Protection rejections now confirm there is no same-file catalog winner before deleting a new copy. Parser output remains forcibly rebound to `storedBook.file.toURI()` before publication.
- Strengthened `BookImportCoordinator` documentation to require Task 7's process-singleton mutex, persistent never-unlinked per-SHA lock file, cancellable OS-lock acquisition, lock ownership across the suspending block, and non-cancellable release/close that cannot replace a committed result or primary failure.
- Made the Task 7 Room plan implementable: nullable legacy `sha256`, database version 2, `MIGRATION_1_2`, `.addMigrations`, schema `2.json`, a non-null unique index while retaining multiple NULL legacy rows, DAO INSERT IGNORE plus query transaction returning the real generated ID, no `REPLACE`, commit-boundary cancellation/throw tests, and complete database/schema commit staging.
- Kept Android/Room/Hilt/UI, the production OS-lock coordinator, and the production descriptor-backed file validator in Task 7 scope.

### RED → GREEN evidence

1. Four deterministic published-lifecycle tests initially failed under the old outside-lock cleanup: acquisition failure/cancellation and owner source-close failure could remove a file used by another winner. Moving close and all deletion into the locked lifecycle made all four pass. The source-close schedule was corrected to an existing-winner-first barrier after the first test design deadlocked by waiting while already holding the shared lock.
2. Four cancellation/fatal tests initially failed: Task 5's suppressed root-close cancellation was lost, a cause/suppressed cycle was not traversed, and fatal source-close cleanup/context semantics were absent. The graph traversal and all-`Throwable` cleanup made all four pass. Two focused production-inspector/catalog nested-cancellation tests also changed RED to GREEN.
3. The four validator regressions first failed to compile because `PrivateBookFileValidator`/`PrivateBookFileMatch` did not exist. After the explicit contract and injected validator were added, missing/outside/symlink/non-regular cases all passed without deleting the new copy.
4. A reverse-schedule protection test reproduced a dangling catalog URI when the new-copy owner rejected protection after the existing side committed the shared file. Catalog-aware protection cleanup changed it from RED to GREEN.
5. The commit-then-throw and commit-then-cancel tests were verified by temporarily restoring the former blind-delete behavior: both failed at their live-file assertions. Restoring locked SHA re-query made both pass.
6. A final fatal catalog failure during protected cleanup was RED because the helper converted `AssertionError` to a stable rejection. Restricting mapping to expected `Exception`/`LinkageError` made the fatal error propagate while preserving the published file, and the test passed.

### Coverage added

- Acquisition failure/cancellation, fatal acquisition-time source close, owner source-close failure, and existing-winner-first protected rejection with deterministic cross-service barriers.
- Catalog commit-then-throw/cancel recovery with a live committed private URI.
- Task 5 primary failure with suppressed close cancellation, nested catalog/inspector cancellation, cancellation graph cycles, and fatal source-close cleanup suppression.
- Missing, outside-root, symbolic-link, and non-regular catalog file validation, plus existing `SAME` and cross-extension `DIFFERENT` coverage.
- Fatal cleanup catalog failure propagation without unsafe deletion.

### Final verification

Targeted Task 6 production/tests are included in the final full run. JUnit XML reports 48 `BookImportServiceTest` tests plus 8 `DefaultBookProtectionInspectorTest` tests, all passing.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Result: `BUILD SUCCESSFUL in 3m 44s`; 333 actionable tasks, 42 executed and 291 up-to-date. Five JUnit suites report 121 tests, 0 failures, 0 errors, and 0 skipped. `app-debug.apk` was generated. Lint reports 0 errors and 127 warnings. `git diff --check` reports no whitespace errors (only the repository's CRLF conversion notices).

---

## Fix round 3

### Status

DONE.

- Fix commit: this commit (`fix: bound private import cleanup`)
- Push: not performed

### Contract and implementation changes

- Cancellation compensation now runs exactly one SHA catalog re-query and one validator-owned cleanup decision inside `withContext(NonCancellable)` with an injectable positive timeout (5 seconds by default). Any timeout or cleanup failure is suppressed on the original `CancellationException`, which is always rethrown. A timeout exits the critical section so the SHA lock cannot remain held by an indefinitely suspended cooperative DAO.
- Replaced the service-visible match-then-path-delete sequence with `PrivateBookFileValidator.resolveDuplicate(storedBook, catalogFile): DuplicateResolution` and `deleteNewCopy(storedBook)`. The validator owns live identity comparison plus any redundant-new-copy removal in one boundary. `BookImportService` no longer imports `Files`, accepts a delete callback, or performs path-based deletion.
- Required the Task 7 validator to reuse Task 5's pinned trusted-root directory descriptor and descriptor-relative `NOFOLLOW`/`fstatat`/`unlinkat`/parent-directory `fsync` primitives. This covers all cooperating PageNest post-publication lifecycle mutations after the SHA is known. Per Task 5's ruling, it does not claim protection from arbitrary malicious code already sharing the app UID.
- Removed the `wasExisting` duplicate short circuit. Every catalog candidate is validated: `SAME` and successfully handled `DIFFERENT` yield `Duplicate`; `INVALID` and `CLEANUP_FAILED` yield `STORAGE_FAILED`. A `wasExisting == true` `DIFFERENT` result preserves both files, and validator cleanup is contractually a no-op for every pre-existing final.
- Changed source-open handling to catch every `Throwable`, promote cancellation found anywhere in its cause/suppressed graph, map only expected `Exception`/`LinkageError` to `UNREADABLE`, and rethrow other fatal errors.
- Tightened the coordinator contract so every cooperating post-publication mutation for a known SHA, including validator cleanup, must occur under the same critical section.
- Completed the Task 7 plan's test/build surface: it now lists and stages `app/build.gradle.kts` and `gradle/libs.versions.toml`, exact Room testing/AndroidX test dependencies, process tests, Android-test compilation, and the migration `connectedDebugAndroidTest` gate. If no device is connected, Task 7 must record `NOT RUN` rather than claim success; Task 10 must execute the gate on its target device before integration unless a real exported-schema host migration test satisfies the same contract.

### RED → GREEN evidence

1. Two real cancelled-`Job` tests with fake DAO `ensureActive()` calls failed first: pre-commit cleanup left an orphan and post-commit cleanup never observed the committed row. The bounded `NonCancellable` compensation made both pass; the pre-commit test then imported the same SHA again through the same coordinator to prove lock release.
2. The validator-owned API tests first failed at Kotlin compilation because `resolveDuplicate`, `deleteNewCopy`, and `DuplicateResolution` did not exist. After the interface/service refactor, four `wasExisting` invalid-candidate tests, the valid-different preservation test, and the path-replacement mutation seam passed.
3. Validator cancellation initially replaced the primary cancellation during the compensation re-query. Capturing every compensation throwable and suppressing it on the original cancellation made the focused test pass while preserving both pre-existing files.
4. Fatal source-open plus nested cancellation was RED because fatal `Error` bypassed the cancellation graph. The unified all-`Throwable` classifier made the nested cancellation test pass; the plain fatal test confirms fatal errors still propagate.
5. The validator cleanup-suppression test was mutation-checked by temporarily removing `addSuppressed`; it failed at the cleanup-context assertion, then passed after restoring the primary-cancellation suppression path.
6. The bounded-cleanup test first failed to compile because no timeout contract existed. With a 100 ms test timeout and a 750 ms fake DAO suspension, it now rethrows the original cancellation with `TimeoutCancellationException` suppressed in under the test bound and successfully reacquires the same SHA lock for a retry.

### Coverage added

- Real cancelled Jobs for pre-commit orphan cleanup, commit-then-cancel live-row preservation, validator cleanup failure, and bounded slow-DAO cleanup.
- Same-coordinator retry after cancellation and timeout, demonstrating no SHA lock leak/deadlock.
- `wasExisting` duplicates whose catalog candidate is missing, outside the trusted root, a symlink, or non-regular, plus a valid different live file that must not be deleted.
- A validator-owned path-replacement seam proving the service performs no second delete after resolution, and validator cancellation propagation without pre-existing mutation.
- Fatal source-open propagation and cancellation nested under a fatal source-open failure.

### Final verification

Targeted Task 6 production/tests:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*BookImportServiceTest' --tests '*DefaultBookProtectionInspectorTest'
```

Result: `BUILD SUCCESSFUL in 49s`; JUnit XML reports 61 service tests plus 8 production-inspector tests, 0 failures, and 0 errors.

Full app regression, Debug APK, and lint:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Result: `BUILD SUCCESSFUL in 3m 43s`; 333 actionable tasks, 41 executed and 292 up-to-date. Five JUnit suites report 134 tests, 0 failures, 0 errors, and 0 skipped. `app-debug.apk` was generated. Lint reports 0 errors and 127 warnings.
