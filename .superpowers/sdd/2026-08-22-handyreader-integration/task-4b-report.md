# Task 4b Report: Close remaining safe-cover extraction boundaries

## Status

DONE.

- Baseline: `05dd3c96355600459325802a6c6eb6ffa32e803b`
- Commit: the Conventional Commit containing this report
- Push: not performed

## Implementation

- Added a hidden bounded ZIP reader shared by `zip_ext` and the EPUB cover caller. It rejects an entry from central-directory metadata before opening when the declared uncompressed size exceeds the caller's limit, counts every actual decompressed byte, never grows beyond the limit, rejects negative reads and declared/actual short reads, and requires `unzCloseCurrentFile` to return `UNZ_OK` so CRC failures cannot return partial bytes.
- Added hidden publication-cover caller helpers with one `32 MiB` policy constant. EPUB passes that limit into the ZIP reader before allocation/decompression; MOBI/AZW3 rejects an oversized record before image-magic inspection or writing. Both helpers keep the existing title-independent safe writer and clear the output path on failure.
- `epub_util::load_epub` now owns its opened `unzFile` with an RAII guard. Every malformed-container/OPF early return closes the handle. `zip_ext::inner_zip_files` no longer closes a handle it does not own, preventing double-close under deterministic outer ownership.
- The protection probe source and call path were not changed. No DRM decryption, bypass, key handling, encryption, RawML parsing, or write capability was added.

## TDD evidence

### 1. Bounded ZIP decompression and strict errors

- RED: the new actual-minizip fixture failed to compile because `bounded_zip_reader.h` and `bounded_zip_reader.c` did not exist.
- GREEN: five actual ZIP fixtures pass: legal complete bytes, oversized high-compression data, CRC failure reported by `unzCloseCurrentFile`, a declared-size short read, and deliberately corrupted deflate data that the fixture independently confirms makes `unzReadCurrentFile` return a negative result.
- Mutation RED: restoring the old "non-positive read means success" semantics and ignoring close/length errors made the suite exit nonzero. Restoring strict read/length/close checks returned it to GREEN.

### 2. Direct EPUB caller and real filesystem wrapper

- RED: the direct caller fixture failed to compile because `publication_cover_write_epub_entry` did not exist.
- GREEN: the caller uses a real `unzFile` plus the production POSIX `realpath`, `mkdirat`, `openat`, random source, `write`, `fsync`, `close`, and `unlinkat` wrappers in a temporary app-private-style directory. A legal PNG succeeds under canonical `files/covers`, with a random internal basename, `covers` mode `0700`, file mode `0600`, exact bytes, and no title/href-derived output segment. Oversized/high-compression, CRC-corrupt, and partial/short ZIP fixtures fail with an empty output and no leftover cover.

### 3. MOBI/AZW3 cover cap

- RED: the MOBI fixture failed to compile because `publication_cover_write_mobi_record` did not exist.
- GREEN: a legal small record writes through the real wrapper; a `32 MiB + 1` record is rejected before writing and leaves no file.

### 4. Deterministic EPUB archive ownership

- RED: the native C++ fixture failed to compile because `zip_archive_owner.h` did not exist.
- GREEN: an observable close hook proves a representative malformed-EPUB early return closes exactly once. `load_epub` now constructs that guard immediately after `unzOpen`, before any early-return branch.

### 5. Canonical containment and real failure cleanup

- GREEN plus mutation RED: a real `files/covers` symlink to an outside directory is rejected and the outside directory stays empty. Removing both canonical equality and `O_NOFOLLOW` defenses makes this fixture fail; restoring them returns GREEN.
- GREEN plus mutation RED: a concurrent real descriptor close during an exact-limit `32 MiB` write makes the production writer's `fsync`/`close` path fail. The real `unlinkat` cleanup removes the partial file. Disabling that cleanup makes the named fixture fail; restoring it returns GREEN.

## Host-native verification

Existing protection/injected-writer/RAII runner:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\mobi\src\test\native\run-protection-tests.ps1
```

Result:

```text
9 native protection fixtures passed
9 native safe-cover fixtures passed
1 native ZIP archive owner fixture passed
```

Real POSIX filesystem and caller runner was executed under the installed Ubuntu WSL environment. Because that environment had no system compiler and sudo required a password, GCC 13/libc/zlib development packages were downloaded and extracted only into the Codex task's temporary `work/` directory; nothing was installed system-wide. The runner accepts standard `CC`, `CFLAGS`, and `LDFLAGS` overrides:

```text
5 bounded ZIP, 4 real EPUB cover, and 4 real filesystem/MOBI cover fixtures passed
```

## Fresh Android/JVM verification

Environment:

```text
JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot
ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
ANDROID_SDK_ROOT=%LOCALAPPDATA%\Android\Sdk
```

Command:

```powershell
.\gradlew.bat :mobi:clean :app:testDebugUnitTest :mobi:assembleDebug :app:assembleDebug
```

Final result after all source and test edits (including fix round 1):

```text
BUILD SUCCESSFUL in 1m 8s
205 actionable tasks: 59 executed, 146 up-to-date
3 JUnit suites, 32 tests, 0 failures, 0 errors, 0 skipped
```

The native build still emits existing upstream warnings. No new task source warning blocked either ABI.

## ABI, graph, symbol, and boundary audit

```text
NINJA_FILES=2
FORBIDDEN_ACTIVE_RULES=0
arm64-v8a: USE_ENCRYPTION:BOOL=OFF
armeabi-v7a: USE_ENCRYPTION:BOOL=OFF
SAFE_WRITER_BUILD_RULES=1 per ABI
PUBLICATION_COVER_BUILD_RULES=1 per ABI
BOUNDED_ZIP_BUILD_RULES=1 per ABI
PROTECTION_RAWML_COVER_DECRYPT_WRITE_HITS=0
TITLE_DERIVED_COVER_REFS=0
```

`llvm-nm -D --defined-only` confirms for both ABIs:

- `libappmobi.so` exports `Java_com_wxn_mobi_inative_NativeLib_isMobiEncrypted`.
- `libmobi.so` exports `mobi_inspect_encrypted_file` and `mobi_is_encrypted`.
- `publication_cover*`, `bounded_zip*`, `safe_cover*`, and `mobi_dump_cover` have zero dynamic exports.
- Operational DRM/decrypt/encrypt exports remain zero. The pre-existing disabled DRM stub exports in `libmobi.so` remain unchanged.

`git diff --check` exits zero. Git only reports the repository's configured LF-to-CRLF checkout warnings.

## Risks and notes

- No device or emulator was available. The production Android ABI code was freshly compiled and audited, while the same POSIX wrapper implementation was executed on the real WSL filesystem rather than through injected syscall substitutes.
- The new cover helpers are hidden implementation symbols and remain entirely separate from the fixed-read, read-only, header-only protection probe.

## Fix round 1: production publication paths

Review found that the first-round cover fixtures exercised the bounded publication helpers directly. They did not prove the upstream EPUB container/OPF selection or MOBI EXTH/resource-record selection, and the old PNG-shaped test bytes were not independently decodable. The standalone archive-owner fixture also did not connect its close observation to the production malformed-EPUB path.

### Qualified RED

The new production caller fixture was compiled before any selection interfaces existed. Compilation stopped at the first missing production boundary:

```text
fatal error: epub_cover_selection.h: No such file or directory
RED_EXIT=1
```

The same test translation unit also referenced the then-missing MOBI selector and production archive runner. Implementation followed only after this RED.

### Production EPUB caller GREEN

- Added a hidden production selector used by `epub_util::load_epub`. The archive-level entry reads a real `META-INF/container.xml`, resolves a unique rootfile, parses the actual OPF metadata/manifest, selects one legacy `meta name="cover"` or EPUB 3 `cover-image` item, validates MIME, safely resolves the href, requires one exact ZIP entry, and only then invokes the bounded writer.
- The legal fixture is an actual small EPUB ZIP containing `META-INF/container.xml`, `OPS/package.opf`, and `OPS/images/cover.png`. Its cover href contains a safe normalizable `images/../images/cover.png` segment, while unrelated metadata contains traversal-like text; neither affects the random internal output name.
- The cover is a complete 68-byte 1x1 PNG with valid `IHDR`, compressed `IDAT`, and `IEND` chunks. A decoder implemented independently in the fixture validates each chunk boundary and CRC, inflates the IDAT stream with zlib, checks the decoded scanline, requires `IEND`, and rejects trailing bytes.
- Oversized high-compression, central-directory CRC corruption, declared-size partial/short, and OPF MIME mismatch fixtures all enter the same container/OPF selector. Each returns failure, clears the output path, and leaves no cover file.

### Production MOBI/AZW3 caller GREEN

- Added a hidden production selector used by `mobi_util::loadMobi`. It reads `EXTH_COVEROFFSET`, obtains the first resource record, checks addition overflow, finds the record by sequence, and invokes the existing capped publication writer.
- The fixture starts from libmobi's committed clear KF8/AZW3 multimedia sample, replaces the independently known fixture record with the complete PNG, serializes a real `.azw3`, reloads it through `mobi_load_filename`, and then calls the production selector. Production derives the cover record from first-resource plus EXTH offset rather than using the fixture's literal record number.
- The legal output passes the same independent PNG decoder. A `32 MiB + 1` record and a corrupt-magic record go through the same MOBI selector, fail, clear the output, and leave no residual file.

### Production-connected archive close GREEN

- `epub_util::load_epub` now performs its complete archive body through the shared production `with_zip_archive` runner, which owns the handle immediately with `zip_archive_owner`.
- A malformed real EPUB lacking `META-INF/container.xml` enters that same production runner and production selector. Its injected close hook wraps the real `unzClose` and observes exactly one close on the early return.
- Mutation RED: removing ownership from that production runner makes the close fixture fail with `CLOSE_MUTATION_EXIT=1`; restoring it returns GREEN.

Additional selection mutations were also demonstrated:

```text
EPUB_MUTATION_EXIT=1
MOBI_MUTATION_EXIT=1
CLOSE_MUTATION_EXIT=1
```

Disabling the EPUB writer call, disabling the MOBI writer call, or removing the production runner ownership respectively kills the legal EPUB, legal MOBI, or close fixture.

### Fix-round host verification

The new runner compiles the real minizip implementation, non-DRM libmobi reader/writer subset, safe filesystem writer, bounded reader, and both production selectors:

```text
5 production EPUB, 3 production MOBI, and 1 production RAII fixture passed
```

Fresh regressions after the final production-source refactor:

```text
5 bounded ZIP, 4 real EPUB cover, and 4 real filesystem/MOBI cover fixtures passed
9 native protection fixtures passed
9 native safe-cover fixtures passed
1 native ZIP archive owner fixture passed
```

The final clean Android build compiled both selectors for both ABIs. Post-build audit results:

```text
JUNIT_SUITES=3 TESTS=32 FAILURES=0 ERRORS=0 SKIPPED=0
NINJA_FILES=2 FORBIDDEN_ACTIVE_RULES=0
arm64-v8a USE_ENCRYPTION:BOOL=OFF EPUB_SELECTOR_PRESENT=1 MOBI_SELECTOR_PRESENT=1
armeabi-v7a USE_ENCRYPTION:BOOL=OFF EPUB_SELECTOR_PRESENT=1 MOBI_SELECTOR_PRESENT=1
arm64-v8a APP JNI_PROBE=1 COVER_EXPORTS=0 DRM_EXPORTS=0
armeabi-v7a APP JNI_PROBE=1 COVER_EXPORTS=0 DRM_EXPORTS=0
arm64-v8a LIBMOBI INSPECT=1 IS_ENCRYPTED=1 DRM_OPERATIONAL=0
armeabi-v7a LIBMOBI INSPECT=1 IS_ENCRYPTED=1 DRM_OPERATIONAL=0
PROTECTION_GRAPH_DIFF_LINES=0
NEW_SELECTOR_BOUNDARY_HITS=0
```

`NEW_SELECTOR_BOUNDARY_HITS=0` confirms the new selector/runner files contain no RawML, decrypt, encrypt, title, or author boundary crossing. The protection probe and DRM graph remain unchanged.
