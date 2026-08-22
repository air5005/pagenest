#ifndef PAGENEST_EPUB_COVER_SELECTION_H
#define PAGENEST_EPUB_COVER_SELECTION_H

#include <stddef.h>

#include "../unzip101e/unzip.h"

#ifdef __cplusplus
extern "C" {
#endif

__attribute__((visibility("hidden"))) int epub_cover_extract_from_archive(
        unzFile archive,
        const char *files_directory,
        char *output_path,
        size_t output_capacity);

__attribute__((visibility("hidden"))) int epub_cover_extract_from_package(
        unzFile archive,
        const char *package_path,
        const char *package_data,
        size_t package_size,
        const char *files_directory,
        char *output_path,
        size_t output_capacity);

#ifdef __cplusplus
}
#endif

#endif
