#ifndef PAGENEST_PUBLICATION_COVER_H
#define PAGENEST_PUBLICATION_COVER_H

#include <stddef.h>

#include "../unzip101e/unzip.h"

#ifdef __cplusplus
extern "C" {
#endif

#define PUBLICATION_COVER_MAX_BYTES ((size_t) 32 * 1024 * 1024)

__attribute__((visibility("hidden"))) int publication_cover_write_epub_entry(
        unzFile archive,
        const char *entry_name,
        const char *expected_extension,
        const char *files_directory,
        char *output_path,
        size_t output_capacity);

__attribute__((visibility("hidden"))) int publication_cover_write_mobi_record(
        const unsigned char *data,
        size_t data_size,
        const char *files_directory,
        char *output_path,
        size_t output_capacity);

#ifdef __cplusplus
}
#endif

#endif
