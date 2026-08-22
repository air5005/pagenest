/** @file mobi_cover.c
 *
 * Cover extraction adapted from libmobi's mobitool example.
 *
 * Copyright (c) 2020 Bartek Fabiszewski
 * http://www.fabiszewski.net
 *
 * Licensed under LGPL, either version 3, or any later.
 * See <http://www.gnu.org/licenses/>
 */

#include "mobi_cover.h"

#include "../log.h"
#include "common.h"

int mobi_dump_cover(
        const MOBIData *m,
        const char *title,
        const char *target_dir,
        char **target_path) {
    MOBIPdbRecord *record = NULL;
    MOBIExthHeader *exth = mobi_get_exthrecord_by_tag(m, EXTH_COVEROFFSET);
    if (exth) {
        uint32_t offset = mobi_decode_exthvalue(exth->data, exth->size);
        size_t first_resource = mobi_get_first_resource_record(m);
        size_t uid = first_resource + offset;
        record = mobi_get_record_by_seqnumber(m, uid);
    }
    if (record == NULL || record->size < 4) {
        LOGD("Cover not found\n");
        return ERROR;
    }

    const unsigned char jpg_magic[] = "\xff\xd8\xff";
    const unsigned char gif_magic[] = "\x47\x49\x46\x38";
    const unsigned char png_magic[] = "\x89\x50\x4e\x47\x0d\x0a\x1a\x0a";
    const unsigned char bmp_magic[] = "\x42\x4d";

    char extension[4] = "raw";
    if (memcmp(record->data, jpg_magic, 3) == 0) {
        snprintf(extension, sizeof(extension), "%s", "jpg");
    } else if (memcmp(record->data, gif_magic, 4) == 0) {
        snprintf(extension, sizeof(extension), "%s", "gif");
    } else if (record->size >= 8 && memcmp(record->data, png_magic, 8) == 0) {
        snprintf(extension, sizeof(extension), "%s", "png");
    } else if (record->size >= 6 && memcmp(record->data, bmp_magic, 2) == 0) {
        const size_t bmp_size = (uint32_t) record->data[2]
                | ((uint32_t) record->data[3] << 8)
                | ((uint32_t) record->data[4] << 16)
                | ((uint32_t) record->data[5] << 24);
        if (record->size == bmp_size) {
            snprintf(extension, sizeof(extension), "%s", "bmp");
        }
    }

    char cover_name[512];
    snprintf(cover_name, sizeof(cover_name), "%s_cover.%s", title, extension);

    char parent_path[FILENAME_MAX];
    snprintf(parent_path, sizeof(parent_path), "%s/covers", target_dir);
    if (!dir_exists(parent_path) && make_directory(parent_path) != SUCCESS) {
        return ERROR;
    }

    char cover_path[FILENAME_MAX];
    snprintf(cover_path, sizeof(cover_path), "%s/%s", parent_path, cover_name);
    LOGD("Saving cover to %s\n", cover_path);

    int result = write_file(record->data, record->size, cover_path);
    if (result == SUCCESS && target_path != NULL) {
        snprintf(*target_path, sizeof(cover_path), "%s", cover_path);
    }
    return result;
}
