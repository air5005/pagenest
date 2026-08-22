#ifndef PAGENEST_MOBI_COVER_SELECTION_H
#define PAGENEST_MOBI_COVER_SELECTION_H

#include <stddef.h>

#include "../libmobi/src/mobi.h"

#ifdef __cplusplus
extern "C" {
#endif

__attribute__((visibility("hidden"))) int mobi_cover_extract_from_data(
        const MOBIData *mobi_data,
        const char *files_directory,
        char *output_path,
        size_t output_capacity);

#ifdef __cplusplus
}
#endif

#endif
