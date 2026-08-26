# PageNest Resilient Open Catalog Implementation Plan

## Task 1: Preserve source failure categories

- [x] Add failure-category output to `DiscoveryResult`.
- [x] Add failing repository tests for typed source failures and timeout failures.
- [x] Implement safe classification and diagnostic logging without private payloads.

## Task 2: Add Open Library catalog fallback

- [x] Add fixture-driven failing tests for request construction and public-access
  filtering.
- [x] Implement a bounded Open Library catalog adapter with identified requests.
- [x] Register it after the two Gutenberg-backed sources.

## Task 3: Harden network and presentation behavior

- [x] Add failing DI/registry tests for stable source status and trusted links.
- [x] Keep automatic redirects disabled until source-specific allow-list handling is
  implemented.
- [x] Present a useful source-state message while retaining cached/live results.

## Task 4: Verify and release

- [x] Run focused discovery tests, all required app unit-test partitions, lint, and
  build the debug APK.
- [ ] Install on the connected HyperOS target and verify Online Discovery plus logs.
- [x] Update test evidence and resume documentation.
- Commit and push `master`, publish the versioned APK and checksum in a GitHub
  Release, then verify the uploaded checksum.
