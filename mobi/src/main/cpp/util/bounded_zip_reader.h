#ifndef PAGENEST_BOUNDED_ZIP_READER_H
#define PAGENEST_BOUNDED_ZIP_READER_H

#include <stddef.h>

#include "../unzip101e/unzip.h"

#ifdef __cplusplus
extern "C" {
#endif

__attribute__((visibility("hidden"))) int bounded_zip_read_file(
        unzFile archive,
        const char *entry_name,
        size_t max_bytes,
        unsigned char **output,
        size_t *output_size);

#ifdef __cplusplus
}
#endif

#endif
