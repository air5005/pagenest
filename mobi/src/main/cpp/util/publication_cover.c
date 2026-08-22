#include "publication_cover.h"

#include <stdlib.h>
#include <string.h>

#include "bounded_zip_reader.h"
#include "safe_cover_writer.h"

int publication_cover_write_epub_entry(
        unzFile archive,
        const char *entry_name,
        const char *expected_extension,
        const char *files_directory,
        char *output_path,
        size_t output_capacity) {
    unsigned char *data = NULL;
    size_t data_size = 0;
    const char *detected_extension;
    int result = 0;
    if (output_path != NULL && output_capacity > 0) {
        output_path[0] = '\0';
    }
    if (archive == NULL || entry_name == NULL || expected_extension == NULL ||
            files_directory == NULL || output_path == NULL || output_capacity == 0 ||
            !bounded_zip_read_file(
                    archive,
                    entry_name,
                    PUBLICATION_COVER_MAX_BYTES,
                    &data,
                    &data_size) ||
            data_size == 0) {
        free(data);
        return 0;
    }
    detected_extension = safe_cover_extension_from_bytes(data, data_size);
    if (detected_extension != NULL && strcmp(expected_extension, detected_extension) == 0) {
        result = safe_cover_write_bytes(
                files_directory,
                detected_extension,
                data,
                data_size,
                output_path,
                output_capacity);
    }
    free(data);
    return result;
}

int publication_cover_write_mobi_record(
        const unsigned char *data,
        size_t data_size,
        const char *files_directory,
        char *output_path,
        size_t output_capacity) {
    const char *detected_extension;
    if (output_path != NULL && output_capacity > 0) {
        output_path[0] = '\0';
    }
    if (data == NULL || data_size == 0 || data_size > PUBLICATION_COVER_MAX_BYTES ||
            files_directory == NULL || output_path == NULL || output_capacity == 0) {
        return 0;
    }
    detected_extension = safe_cover_extension_from_bytes(data, data_size);
    if (detected_extension == NULL) {
        return 0;
    }
    return safe_cover_write_bytes(
            files_directory,
            detected_extension,
            data,
            data_size,
            output_path,
            output_capacity);
}
