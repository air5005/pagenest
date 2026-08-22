#!/usr/bin/env sh
set -eu

project_directory=$1
output_directory=$2
compiler=${CC:-cc}
if ! command -v "$compiler" >/dev/null 2>&1; then
    echo "Native C compiler is unavailable: $compiler" >&2
    exit 127
fi
mkdir -p "$output_directory"
"$compiler" -std=c11 -Wall -Wextra -Werror \
    "$project_directory/src/test/cpp/private_book_store_validation_test.c" \
    "$project_directory/src/test/cpp/private_book_store_publish_test.c" \
    "$project_directory/src/main/cpp/private_book_store_validation.c" \
    "$project_directory/src/main/cpp/private_book_store_publish.c" \
    -o "$output_directory/private_book_store_validation_test"
"$output_directory/private_book_store_validation_test"
echo 'private_book_store_native_validation=PASS'
