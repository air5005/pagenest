/** @file protection.c
 *
 * Read-only MOBI protection metadata inspection.
 */

#include <stdio.h>

#include "mobi.h"
#include "protection_core.h"

#define PDB_HEADER_SIZE 78
#define PDB_RECORD_ENTRY_SIZE 8
#define PALMDOC_HEADER_SIZE 16

static MOBI_RET as_mobi_result(int result) {
    switch (result) {
        case MOBI_PROTECTION_SUCCESS:
            return MOBI_SUCCESS;
        case MOBI_PROTECTION_PARAM_ERR:
            return MOBI_PARAM_ERR;
        case MOBI_PROTECTION_UNSUPPORTED:
            return MOBI_FILE_UNSUPPORTED;
        case MOBI_PROTECTION_CORRUPT:
        default:
            return MOBI_DATA_CORRUPT;
    }
}

static MOBI_RET inspect_stream(FILE *file, bool *encrypted) {
    if (file == NULL || encrypted == NULL) {
        return MOBI_PARAM_ERR;
    }
    if (fseek(file, 0, SEEK_END) != 0) {
        return MOBI_DATA_CORRUPT;
    }
    const long file_size = ftell(file);
    if (file_size <= 0 || fseek(file, 0, SEEK_SET) != 0) {
        return MOBI_DATA_CORRUPT;
    }

    unsigned char pdb_header[PDB_HEADER_SIZE];
    if (fread(pdb_header, 1, sizeof(pdb_header), file) != sizeof(pdb_header)) {
        return MOBI_DATA_CORRUPT;
    }
    MobiProtectionState state;
    int result = mobi_protection_begin(
            pdb_header,
            sizeof(pdb_header),
            (unsigned long long) file_size,
            &state);
    if (result != MOBI_PROTECTION_SUCCESS) {
        return as_mobi_result(result);
    }

    unsigned char record_entry[PDB_RECORD_ENTRY_SIZE];
    unsigned int index;
    for (index = 0; index < state.record_count; index++) {
        if (fread(record_entry, 1, sizeof(record_entry), file) != sizeof(record_entry)) {
            return MOBI_DATA_CORRUPT;
        }
        result = mobi_protection_accept_record(&state, record_entry, sizeof(record_entry));
        if (result != MOBI_PROTECTION_SUCCESS) {
            return as_mobi_result(result);
        }
    }

    if (state.record0_offset > (unsigned long) file_size ||
        fseek(file, (long) state.record0_offset, SEEK_SET) != 0) {
        return MOBI_DATA_CORRUPT;
    }
    unsigned char record0[PALMDOC_HEADER_SIZE];
    if (fread(record0, 1, sizeof(record0), file) != sizeof(record0)) {
        return MOBI_DATA_CORRUPT;
    }
    int protection = 0;
    result = mobi_protection_finish(&state, record0, sizeof(record0), &protection);
    if (result == MOBI_PROTECTION_SUCCESS) {
        *encrypted = protection != 0;
    }
    return as_mobi_result(result);
}

MOBI_RET mobi_inspect_encrypted_file(const char *path, bool *encrypted) {
    if (path == NULL || encrypted == NULL) {
        return MOBI_PARAM_ERR;
    }
    FILE *file = fopen(path, "rb");
    if (file == NULL) {
        return MOBI_FILE_NOT_FOUND;
    }

    const MOBI_RET result = inspect_stream(file, encrypted);
    fclose(file);
    return result;
}
