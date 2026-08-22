#include "bounded_zip_reader.h"

#include <stdlib.h>
#include <string.h>

#define ZIP_READ_CHUNK_BYTES 8192U

static int append_bytes(
        unsigned char **data,
        size_t *size,
        size_t *capacity,
        const unsigned char *chunk,
        size_t chunk_size,
        size_t max_bytes) {
    size_t required;
    size_t next_capacity;
    unsigned char *grown;
    if (chunk_size > max_bytes - *size) {
        return 0;
    }
    required = *size + chunk_size;
    if (required > *capacity) {
        next_capacity = *capacity == 0 ? ZIP_READ_CHUNK_BYTES : *capacity;
        while (next_capacity < required) {
            size_t doubled = next_capacity > max_bytes / 2 ? max_bytes : next_capacity * 2;
            if (doubled <= next_capacity) {
                next_capacity = required;
                break;
            }
            next_capacity = doubled;
        }
        if (next_capacity > max_bytes) {
            next_capacity = max_bytes;
        }
        grown = (unsigned char *) realloc(*data, next_capacity);
        if (grown == NULL) {
            return 0;
        }
        *data = grown;
        *capacity = next_capacity;
    }
    if (chunk_size > 0) {
        memcpy(*data + *size, chunk, chunk_size);
    }
    *size = required;
    return 1;
}

int bounded_zip_read_file(
        unzFile archive,
        const char *entry_name,
        size_t max_bytes,
        unsigned char **output,
        size_t *output_size) {
    unz_file_info info;
    unsigned char chunk[ZIP_READ_CHUNK_BYTES];
    unsigned char *data = NULL;
    size_t size = 0;
    size_t capacity = 0;
    int opened = 0;
    int success = 0;
    int read_result = 0;
    int close_result = UNZ_OK;

    if (output != NULL) {
        *output = NULL;
    }
    if (output_size != NULL) {
        *output_size = 0;
    }
    if (archive == NULL || entry_name == NULL || output == NULL || output_size == NULL) {
        return 0;
    }
    if (unzLocateFile(archive, entry_name, 0) != UNZ_OK ||
            unzGetCurrentFileInfo(archive, &info, NULL, 0, NULL, 0, NULL, 0) != UNZ_OK ||
            (size_t) info.uncompressed_size > max_bytes ||
            unzOpenCurrentFile(archive) != UNZ_OK) {
        return 0;
    }
    opened = 1;
    while ((read_result = unzReadCurrentFile(archive, chunk, sizeof(chunk))) > 0) {
        if (!append_bytes(
                &data,
                &size,
                &capacity,
                chunk,
                (size_t) read_result,
                max_bytes)) {
            goto cleanup;
        }
    }
    if (read_result < 0 || size != (size_t) info.uncompressed_size) {
        goto cleanup;
    }
    success = 1;

cleanup:
    if (opened) {
        close_result = unzCloseCurrentFile(archive);
    }
    if (!success || close_result != UNZ_OK) {
        free(data);
        return 0;
    }
    *output = data;
    *output_size = size;
    return 1;
}
