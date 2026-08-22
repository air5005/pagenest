#ifndef LIBMOBI_PROTECTION_CORE_H
#define LIBMOBI_PROTECTION_CORE_H

#define MOBI_PROTECTION_SUCCESS 0
#define MOBI_PROTECTION_PARAM_ERR 2
#define MOBI_PROTECTION_CORRUPT 3
#define MOBI_PROTECTION_UNSUPPORTED 6

typedef struct {
    unsigned long long file_size;
    unsigned long long table_end;
    unsigned long previous_offset;
    unsigned long record0_offset;
    unsigned long record0_end;
    unsigned int record_count;
    unsigned int records_seen;
} MobiProtectionState;

int mobi_protection_begin(
        const unsigned char *pdb_header,
        unsigned long header_size,
        unsigned long long file_size,
        MobiProtectionState *state);
int mobi_protection_accept_record(
        MobiProtectionState *state,
        const unsigned char *record_entry,
        unsigned long entry_size);
int mobi_protection_finish(
        const MobiProtectionState *state,
        const unsigned char *record0,
        unsigned long record0_size,
        int *encrypted);
int mobi_protection_inspect_headers(
        const unsigned char *pdb_header,
        unsigned long header_size,
        const unsigned char *record_table,
        unsigned long table_size,
        unsigned long long file_size,
        const unsigned char *record0,
        unsigned long record0_size,
        int *encrypted);

#endif
