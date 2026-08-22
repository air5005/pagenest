#include "mobi_cover_selection.h"

#include <stdint.h>

#include "publication_cover.h"

int mobi_cover_extract_from_data(
        const MOBIData *mobi_data,
        const char *files_directory,
        char *output_path,
        size_t output_capacity) {
    if (output_path != NULL && output_capacity > 0) {
        output_path[0] = '\0';
    }
    if (mobi_data == NULL || files_directory == NULL || output_path == NULL ||
            output_capacity == 0) {
        return 0;
    }
    MOBIExthHeader *cover_offset = mobi_get_exthrecord_by_tag(mobi_data, EXTH_COVEROFFSET);
    if (cover_offset == NULL) {
        return 0;
    }
    uint32_t offset = mobi_decode_exthvalue(cover_offset->data, cover_offset->size);
    size_t first_resource = mobi_get_first_resource_record(mobi_data);
    if (first_resource == MOBI_NOTSET || offset > SIZE_MAX - first_resource) {
        return 0;
    }
    MOBIPdbRecord *cover_record = mobi_get_record_by_seqnumber(
            mobi_data,
            first_resource + offset);
    if (cover_record == NULL) {
        return 0;
    }
    return publication_cover_write_mobi_record(
            cover_record->data,
            cover_record->size,
            files_directory,
            output_path,
            output_capacity);
}
