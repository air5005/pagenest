#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
output_directory="$repo_root/mobi/build/native-caller-tests"
cc="${CC:-cc}"
cxx="${CXX:-c++}"
libmobi="$repo_root/mobi/src/main/cpp/libmobi/src"
util="$repo_root/mobi/src/main/cpp/util"
unzip="$repo_root/mobi/src/main/cpp/unzip101e"

mkdir -p "$output_directory"
cd "$output_directory"

common_flags=(
    -D_GNU_SOURCE
    -ffunction-sections
    -fdata-sections
    -I "$libmobi"
    -I "$util"
    -I "$unzip"
)

"$cc" \
    -std=c11 \
    -Wall \
    -Wextra \
    -Werror \
    -Wno-unused-but-set-variable \
    '-DPACKAGE_VERSION="0.12"' \
    -DUSE_MINIZ \
    "${common_flags[@]}" \
    ${CFLAGS:-} \
    -c \
    "$libmobi/buffer.c" \
    "$libmobi/debug.c" \
    "$libmobi/memory.c" \
    "$libmobi/miniz.c" \
    "$libmobi/read.c" \
    "$libmobi/structure.c" \
    "$libmobi/util.c" \
    "$libmobi/write.c" \
    "$util/bounded_zip_reader.c" \
    "$util/publication_cover.c" \
    "$util/mobi_cover_selection.c" \
    "$util/safe_cover_writer.c"

"$cc" \
    -std=c11 \
    -w \
    "${common_flags[@]}" \
    ${CFLAGS:-} \
    -c \
    "$unzip/ioapi.c" \
    "$unzip/unzip.c" \
    "$unzip/zip.c"

"$cxx" \
    -std=c++17 \
    -Wall \
    -Wextra \
    -Werror \
    "${common_flags[@]}" \
    ${CXXFLAGS:-${CFLAGS:-}} \
    -c \
    "$util/tinyxml2.cpp" \
    "$util/epub_cover_selection.cpp" \
    "$repo_root/mobi/src/test/native/publication_caller_integration_test.cpp"

"$cxx" \
    *.o \
    ${LDFLAGS:-} \
    -Wl,--gc-sections \
    -pthread \
    -lz \
    -o publication_caller_integration_test

./publication_caller_integration_test \
    "${libmobi%/src}/tests/samples/sample-multimedia.mobi"
echo "5 production EPUB, 3 production MOBI, and 1 production RAII fixture passed"
