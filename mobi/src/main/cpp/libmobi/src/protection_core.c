#include "protection_core.h"

#define PDB_HEADER_SIZE 78UL
#define PDB_RECORD_ENTRY_SIZE 8UL
#define PDB_RECORD_LIST_PADDING 2UL
#define PALMDOC_HEADER_SIZE 16UL
#define PALMDOC_ENCRYPTION_OFFSET 12UL

static unsigned int get16(const unsigned char *buffer) {
    return ((unsigned int) buffer[0] << 8) | buffer[1];
}

static unsigned long get32(const unsigned char *buffer) {
    return ((unsigned long) buffer[0] << 24) |
           ((unsigned long) buffer[1] << 16) |
           ((unsigned long) buffer[2] << 8) |
           buffer[3];
}

static int equals4(const unsigned char *value, const char expected[4]) {
    return value[0] == (unsigned char) expected[0] &&
           value[1] == (unsigned char) expected[1] &&
           value[2] == (unsigned char) expected[2] &&
           value[3] == (unsigned char) expected[3];
}

int mobi_protection_begin(
        const unsigned char *pdb_header,
        unsigned long header_size,
        unsigned long long file_size,
        MobiProtectionState *state) {
    if (pdb_header == 0 || state == 0) {
        return MOBI_PROTECTION_PARAM_ERR;
    }
    if (header_size < PDB_HEADER_SIZE) {
        return MOBI_PROTECTION_CORRUPT;
    }
    if (!((equals4(pdb_header + 60, "BOOK") && equals4(pdb_header + 64, "MOBI")) ||
          (equals4(pdb_header + 60, "TEXt") && equals4(pdb_header + 64, "REAd")))) {
        return MOBI_PROTECTION_UNSUPPORTED;
    }

    const unsigned int record_count = get16(pdb_header + 76);
    if (record_count == 0) {
        return MOBI_PROTECTION_CORRUPT;
    }
    const unsigned long long table_end = PDB_HEADER_SIZE +
            (unsigned long long) record_count * PDB_RECORD_ENTRY_SIZE +
            PDB_RECORD_LIST_PADDING;
    if (table_end > file_size) {
        return MOBI_PROTECTION_CORRUPT;
    }

    state->file_size = file_size;
    state->table_end = table_end;
    state->previous_offset = 0;
    state->record0_offset = 0;
    state->record0_end = 0;
    state->record_count = record_count;
    state->records_seen = 0;
    return MOBI_PROTECTION_SUCCESS;
}

int mobi_protection_accept_record(
        MobiProtectionState *state,
        const unsigned char *record_entry,
        unsigned long entry_size) {
    if (state == 0 || record_entry == 0) {
        return MOBI_PROTECTION_PARAM_ERR;
    }
    if (entry_size < PDB_RECORD_ENTRY_SIZE || state->records_seen >= state->record_count) {
        return MOBI_PROTECTION_CORRUPT;
    }

    const unsigned long offset = get32(record_entry);
    if ((unsigned long long) offset < state->table_end ||
        (unsigned long long) offset >= state->file_size ||
        (state->records_seen > 0 && offset <= state->previous_offset)) {
        return MOBI_PROTECTION_CORRUPT;
    }
    if (state->records_seen == 0) {
        state->record0_offset = offset;
    } else if (state->records_seen == 1) {
        state->record0_end = offset;
    }
    state->previous_offset = offset;
    state->records_seen++;
    return MOBI_PROTECTION_SUCCESS;
}

int mobi_protection_finish(
        const MobiProtectionState *state,
        const unsigned char *record0,
        unsigned long record0_size,
        int *encrypted) {
    if (state == 0 || record0 == 0 || encrypted == 0) {
        return MOBI_PROTECTION_PARAM_ERR;
    }
    if (state->records_seen != state->record_count) {
        return MOBI_PROTECTION_CORRUPT;
    }
    const unsigned long long record0_end = state->record0_end != 0
            ? state->record0_end
            : state->file_size;
    if (record0_end < (unsigned long long) state->record0_offset + PALMDOC_HEADER_SIZE ||
        record0_size < PALMDOC_HEADER_SIZE) {
        return MOBI_PROTECTION_CORRUPT;
    }

    const unsigned int encryption_type = get16(record0 + PALMDOC_ENCRYPTION_OFFSET);
    if (encryption_type == 0) {
        *encrypted = 0;
        return MOBI_PROTECTION_SUCCESS;
    }
    if (encryption_type == 1 || encryption_type == 2) {
        *encrypted = 1;
        return MOBI_PROTECTION_SUCCESS;
    }
    return MOBI_PROTECTION_CORRUPT;
}

int mobi_protection_inspect_headers(
        const unsigned char *pdb_header,
        unsigned long header_size,
        const unsigned char *record_table,
        unsigned long table_size,
        unsigned long long file_size,
        const unsigned char *record0,
        unsigned long record0_size,
        int *encrypted) {
    MobiProtectionState state;
    int result = mobi_protection_begin(pdb_header, header_size, file_size, &state);
    if (result != MOBI_PROTECTION_SUCCESS) {
        return result;
    }
    const unsigned long required_table_size =
            (unsigned long) state.record_count * PDB_RECORD_ENTRY_SIZE;
    if (record_table == 0 || table_size < required_table_size) {
        return MOBI_PROTECTION_CORRUPT;
    }
    unsigned int index;
    for (index = 0; index < state.record_count; index++) {
        result = mobi_protection_accept_record(
                &state,
                record_table + index * PDB_RECORD_ENTRY_SIZE,
                PDB_RECORD_ENTRY_SIZE);
        if (result != MOBI_PROTECTION_SUCCESS) {
            return result;
        }
    }
    return mobi_protection_finish(&state, record0, record0_size, encrypted);
}
