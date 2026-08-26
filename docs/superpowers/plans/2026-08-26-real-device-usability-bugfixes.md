# PageNest Real-device Usability Bugfix Plan

## Scope

Fix the three defects reproduced on the HyperOS device without changing the
catalog or private-library data model:

1. Online discovery exposes an explicit search action and supports the keyboard
   search action. Submitting runs immediately and cancels the pending debounce.
2. App startup loads the persisted shelf only. A saved scan directory is scanned
   only after an explicit user import/refresh action.
3. Batch import feedback is a bounded count summary, automatically disappears,
   and always has a dismiss action. Per-book duplicate messages are never joined
   into an unbounded overlay.

## TDD checkpoints

- [x] Add a failing Discovery ViewModel test for immediate explicit submission.
- [x] Add a failing Compose test for the visible search action.
- [x] Add failing Home tests for no startup scan, compact batch summaries, and
      explicit snackbar dismissal.
- [x] Implement the smallest production changes that make the tests pass.
- [x] Compile the Android test APK and run focused JVM tests plus lint.
- [x] Build the next version APK and complete the local release gate; commit, push, and GitHub
      Release, then verify the downloaded artifact checksum.
