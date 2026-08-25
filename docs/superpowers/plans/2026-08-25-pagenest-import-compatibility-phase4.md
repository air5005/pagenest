# PageNest Import Compatibility Phase 4 Implementation Plan

> Execute task-by-task with test-driven development. Work directly on `master`; commit and push every completed task.

**Goal:** Restore TXT and EPUB imports on ABI targets where the optional MOBI CRC native library is unavailable, then verify the immersive reader with a real imported book.

**Architecture:** Keep every format parser and the SHA-256 import pipeline unchanged. Introduce one pure best-effort legacy CRC boundary in `bookparser`, route both `DocumentFile` and `CachedFile` parsing through it, and treat only ordinary failures plus `LinkageError` as an unavailable optional enhancement.

**Spec:** `docs/superpowers/specs/2026-08-25-pagenest-import-compatibility-design.md`

## Task 1: Reproduce and fix optional CRC failure

**Files:**

- Create `bookparser/src/main/java/com/wxn/bookparser/impl/BestEffortBookCrc.kt`
- Create `bookparser/src/test/java/com/wxn/bookparser/impl/BestEffortBookCrcTest.kt`
- Modify `bookparser/src/main/java/com/wxn/bookparser/impl/FileParserImpl.kt`

1. Write focused tests proving successful CRC is preserved, null becomes zero, `IOException` becomes zero, `UnsatisfiedLinkError` becomes zero, and unrelated fatal `Error` is rethrown.
2. Run `:bookparser:testDebugUnitTest` and confirm RED because the compatibility boundary does not exist.
3. Implement the smallest pure function satisfying the contract.
4. Replace both unconditional `MobiParser.getFileCrc()` calls with the boundary.
5. Run focused module tests and the app unit suite.
6. Commit and push `fix: tolerate unavailable legacy book crc`.

## Task 2: Verify real import and reader entry

1. Build and install the debug APK on `pagenest_api36`.
2. Clear stale app import state while preserving the external test directory.
3. Import a UTF-8 TXT and the W3C/IDPF `wasteland.epub` already staged in `Download/PageNestTest`.
4. Confirm both formats are accepted or record format-specific parser evidence separately.
5. Open an imported book and verify正文、中央点击控制区、目录、进度、显示和听书入口。
6. Run the two Phase 3 Compose device suites again and check AndroidRuntime fatal count.
7. Record evidence in `docs/testing/import-compatibility-phase4.md`; commit and push.

## Task 3: Full gate and release

1. Run all JVM tests, debug APK, Android test APK, Lint, and focused API 36 device tests.
2. Bump to `versionCode 10`, `versionName 1.9.260825`.
3. Repeat the gate on the exact release tree.
4. Commit and push `build: prepare pagenest 1.9.260825`.
5. Tag and push `pagenest-v1.9.260825`.
6. Wait for the GitHub Release workflow; verify the APK asset digest against `SHA256SUMS.txt`.
7. Update the phase evidence and resume manual, then commit and push.
