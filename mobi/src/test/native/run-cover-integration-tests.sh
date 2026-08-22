#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
output_directory="$repo_root/mobi/build/native-cover-tests"
cc="${CC:-cc}"

mkdir -p "$output_directory"
cd "$output_directory"
"$cc" \
    -std=c11 \
    -D_GNU_SOURCE \
    -Wall \
    -Wextra \
    -Werror \
    ${CFLAGS:-} \
    -I "$repo_root/mobi/src/main/cpp/util" \
    -I "$repo_root/mobi/src/main/cpp/unzip101e" \
    "$repo_root/mobi/src/test/native/cover_integration_test.c" \
    "$repo_root/mobi/src/main/cpp/util/bounded_zip_reader.c" \
    "$repo_root/mobi/src/main/cpp/util/publication_cover.c" \
    "$repo_root/mobi/src/main/cpp/util/safe_cover_writer.c" \
    -c

"$cc" \
    -std=c11 \
    -w \
    ${CFLAGS:-} \
    -I "$repo_root/mobi/src/main/cpp/unzip101e" \
    "$repo_root/mobi/src/main/cpp/unzip101e/ioapi.c" \
    "$repo_root/mobi/src/main/cpp/unzip101e/unzip.c" \
    "$repo_root/mobi/src/main/cpp/unzip101e/zip.c" \
    -c

"$cc" \
    cover_integration_test.o \
    bounded_zip_reader.o \
    publication_cover.o \
    safe_cover_writer.o \
    ioapi.o \
    unzip.o \
    zip.o \
    ${LDFLAGS:-} \
    -pthread \
    -lz \
    -o "$output_directory/cover_integration_test"

rm -f cover_integration_test.o bounded_zip_reader.o publication_cover.o safe_cover_writer.o \
    ioapi.o unzip.o zip.o

"$output_directory/cover_integration_test"
echo "5 bounded ZIP, 4 real EPUB cover, and 4 real filesystem/MOBI cover fixtures passed"
