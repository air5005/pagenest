# PageNest HyperOS 3 Device Gate Phase 5 Implementation Plan

> Execute task-by-task with test-driven development. Work directly on `master`; commit and push every completed task.

**Goal:** Make target-device selection and evidence collection deterministic before running PageNest's ARM64 format and background-speech release gates.

**Architecture:** Keep device classification in a pure PowerShell module and ADB interaction in a thin command wrapper. A failing preflight stops before installation; a passing preflight creates redacted JSON/text evidence in a temporary directory.

**Spec:** `docs/superpowers/specs/2026-08-25-pagenest-hyperos3-device-gate-design.md`

## Task 1: Add a TDD-protected HyperOS 3 preflight

**Files:**

- Create `tools/HyperOs3Preflight.psm1`
- Create `tools/hyperos3-device-preflight.ps1`
- Create `tools/tests/HyperOs3Preflight.Tests.ps1`
- Modify `docs/DEVELOPMENT.md`

1. Write failing tests for a valid ARM64 HyperOS 3 snapshot and rejection of emulator, wrong Android release/SDK, wrong ABI and missing HyperOS identity.
2. Run the self-contained test script and confirm RED because the module does not exist.
3. Implement the pure snapshot evaluator and verify all rejection reasons are stable.
4. Implement the ADB wrapper with explicit serial selection, property collection, redacted evidence output and nonzero failure exit.
5. Run unit tests, invoke the wrapper against the current emulator and confirm it refuses to qualify it as a target phone.
6. Document exact usage; commit and push `test: add hyperos3 device preflight`.

## Task 2: Run the ARM64 format smoke matrix

1. Connect the target phone and require Task 1 preflight PASS.
2. Install the 1.9.260825 GitHub Release APK without clearing existing user data.
3. Import sanitized fixtures for TXT, EPUB, MOBI, AZW3 and extractable PDF.
4. Verify open/read/progress/settings/image-skin behavior and capture redacted evidence.
5. Diagnose and fix any failure with systematic-debugging and TDD; commit and push each fix.
6. Update `docs/testing/voice-reading-hyperos3.md` with actual device evidence.

## Task 3: Run speech lifecycle and 60-minute gate

1. Run speech and Room connected tests on the explicitly selected phone.
2. Execute offline/online, background, lock-screen, audio-focus and noisy-route checks.
3. Execute the documented 60-minute sampling matrix.
4. Record battery, memory, thermal, crash and ANR evidence without secrets or book content.
5. Complete the HyperOS document only when every required row has real evidence; otherwise leave it `NOT RUN` or `FAIL`.
6. Run the full release gate, commit and push the final Phase 5 checkpoint.
