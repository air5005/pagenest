/** @file protection.c
 *
 * Read-only MOBI protection metadata inspection.
 */

#include <stdio.h>
#include <string.h>

#include "mobi.h"
#include "read.h"

static MOBI_RET load_record0(MOBIData *m, FILE *file) {
    MOBI_RET result = mobi_load_pdbheader(m, file);
    if (result != MOBI_SUCCESS) {
        return result;
    }
    if ((strcmp(m->ph->type, "BOOK") != 0 && strcmp(m->ph->type, "TEXt") != 0) ||
        m->ph->rec_count == 0) {
        return MOBI_FILE_UNSUPPORTED;
    }

    result = mobi_load_reclist(m, file);
    if (result != MOBI_SUCCESS) {
        return result;
    }

    MOBIPdbRecord *record0 = m->rec;
    if (record0->next != NULL) {
        if (record0->next->offset <= record0->offset) {
            return MOBI_DATA_CORRUPT;
        }
        record0->size = record0->next->offset - record0->offset;
    } else {
        if (fseek(file, 0, SEEK_END) != 0) {
            return MOBI_DATA_CORRUPT;
        }
        const long file_size = ftell(file);
        if (file_size <= 0 || (unsigned long) file_size <= record0->offset) {
            return MOBI_DATA_CORRUPT;
        }
        record0->size = (size_t) file_size - record0->offset;
    }

    result = mobi_load_recdata(record0, file);
    if (result != MOBI_SUCCESS) {
        return result;
    }
    return mobi_parse_record0(m, 0);
}

MOBI_RET mobi_inspect_encrypted_file(const char *path, bool *encrypted) {
    if (path == NULL || encrypted == NULL) {
        return MOBI_PARAM_ERR;
    }

    MOBIData *m = mobi_init();
    if (m == NULL) {
        return MOBI_INIT_FAILED;
    }
    FILE *file = fopen(path, "rb");
    if (file == NULL) {
        mobi_free(m);
        return MOBI_FILE_NOT_FOUND;
    }

    const MOBI_RET result = load_record0(m, file);
    fclose(file);
    if (result == MOBI_SUCCESS) {
        *encrypted = mobi_is_encrypted(m);
    }
    mobi_free(m);
    return result;
}
