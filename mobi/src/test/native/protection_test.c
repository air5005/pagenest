#include "protection_core.h"

#define PDB_HEADER_SIZE 78
#define PALMDOC_HEADER_SIZE 16

static void put16(unsigned char *buffer, unsigned int offset, unsigned int value) {
    buffer[offset] = (unsigned char) (value >> 8);
    buffer[offset + 1] = (unsigned char) value;
}

static void put32(unsigned char *buffer, unsigned int offset, unsigned long value) {
    buffer[offset] = (unsigned char) (value >> 24);
    buffer[offset + 1] = (unsigned char) (value >> 16);
    buffer[offset + 2] = (unsigned char) (value >> 8);
    buffer[offset + 3] = (unsigned char) value;
}

static void copy4(unsigned char *target, unsigned int offset, const char value[4]) {
    target[offset] = (unsigned char) value[0];
    target[offset + 1] = (unsigned char) value[1];
    target[offset + 2] = (unsigned char) value[2];
    target[offset + 3] = (unsigned char) value[3];
}

static void make_fixture(
        unsigned char *pdb_header,
        unsigned char *record_table,
        unsigned char *record0,
        const char type[4],
        const char creator[4],
        unsigned int encryption_type,
        unsigned int record_count,
        unsigned long record0_offset,
        unsigned long record1_offset) {
    unsigned int index;
    for (index = 0; index < PDB_HEADER_SIZE; index++) {
        pdb_header[index] = 0;
    }
    for (index = 0; index < 16; index++) {
        record_table[index] = 0;
        record0[index] = 0;
    }
    copy4(pdb_header, 60, type);
    copy4(pdb_header, 64, creator);
    put16(pdb_header, 76, record_count);
    put32(record_table, 0, record0_offset);
    if (record_count > 1) {
        put32(record_table, 8, record1_offset);
    }
    put16(record0, 12, encryption_type);
}

static int inspect(
        unsigned char *pdb_header,
        unsigned char *record_table,
        unsigned char *record0,
        unsigned long long file_size,
        unsigned long record0_bytes,
        int expected_result,
        int expected_encrypted) {
    int encrypted = !expected_encrypted;
    int result = mobi_protection_inspect_headers(
            pdb_header,
            PDB_HEADER_SIZE,
            record_table,
            16,
            file_size,
            record0,
            record0_bytes,
            &encrypted);
    if (result != expected_result) {
        return 1;
    }
    if (result == MOBI_PROTECTION_SUCCESS && encrypted != expected_encrypted) {
        return 1;
    }
    return 0;
}

__declspec(dllexport) int run_protection_tests(void) {
    unsigned char pdb_header[PDB_HEADER_SIZE];
    unsigned char record_table[16];
    unsigned char record0[PALMDOC_HEADER_SIZE];
    int failures = 0;

    make_fixture(pdb_header, record_table, record0, "BOOK", "MOBI", 0, 1, 88, 0);
    failures += inspect(pdb_header, record_table, record0, 128, 16,
                        MOBI_PROTECTION_SUCCESS, 0);

    make_fixture(pdb_header, record_table, record0, "BOOK", "MOBI", 1, 1, 88, 0);
    failures += inspect(pdb_header, record_table, record0, 128, 16,
                        MOBI_PROTECTION_SUCCESS, 1);

    make_fixture(pdb_header, record_table, record0, "TEXt", "REAd", 2, 1, 88, 0);
    failures += inspect(pdb_header, record_table, record0, 128, 16,
                        MOBI_PROTECTION_SUCCESS, 1);

    make_fixture(pdb_header, record_table, record0, "BOOK", "REAd", 0, 1, 88, 0);
    failures += inspect(pdb_header, record_table, record0, 128, 16,
                        MOBI_PROTECTION_UNSUPPORTED, 1);

    make_fixture(pdb_header, record_table, record0, "TEXt", "MOBI", 0, 1, 88, 0);
    failures += inspect(pdb_header, record_table, record0, 128, 16,
                        MOBI_PROTECTION_UNSUPPORTED, 1);

    make_fixture(pdb_header, record_table, record0, "BOOK", "MOBI", 3, 1, 88, 0);
    failures += inspect(pdb_header, record_table, record0, 128, 16,
                        MOBI_PROTECTION_CORRUPT, 1);

    make_fixture(pdb_header, record_table, record0, "BOOK", "MOBI", 0, 1, 88, 0);
    failures += inspect(pdb_header, record_table, record0, 96, 8,
                        MOBI_PROTECTION_CORRUPT, 1);

    make_fixture(pdb_header, record_table, record0, "BOOK", "MOBI", 0, 1,
                 0xfffffff0UL, 0);
    failures += inspect(pdb_header, record_table, record0, 128, 16,
                        MOBI_PROTECTION_CORRUPT, 1);

    make_fixture(pdb_header, record_table, record0, "BOOK", "MOBI", 0, 2, 96, 96);
    failures += inspect(pdb_header, record_table, record0, 160, 16,
                        MOBI_PROTECTION_CORRUPT, 1);

    return failures;
}
