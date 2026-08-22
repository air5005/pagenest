#include "bounded_zip_reader.h"
#include "publication_cover.h"

#include <dirent.h>
#include <ftw.h>
#include <limits.h>
#include <pthread.h>
#include <stdint.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "zip.h"

static int write_zip_entry(
        const char *path,
        const char *entry_name,
        const unsigned char *data,
        size_t data_size) {
    zipFile archive = zipOpen(path, APPEND_STATUS_CREATE);
    if (archive == NULL) {
        return 0;
    }
    if (zipOpenNewFileInZip(
            archive,
            entry_name,
            NULL,
            NULL,
            0,
            NULL,
            0,
            NULL,
            Z_DEFLATED,
            Z_BEST_COMPRESSION) != ZIP_OK) {
        zipClose(archive, NULL);
        return 0;
    }
    if (data_size > 0 && zipWriteInFileInZip(archive, data, (unsigned) data_size) != ZIP_OK) {
        zipCloseFileInZip(archive);
        zipClose(archive, NULL);
        return 0;
    }
    return zipCloseFileInZip(archive) == ZIP_OK && zipClose(archive, NULL) == ZIP_OK;
}

static int write_repeated_zip_entry(
        const char *path,
        const char *entry_name,
        size_t data_size) {
    unsigned char zeros[8192] = {0};
    zipFile archive = zipOpen(path, APPEND_STATUS_CREATE);
    if (archive == NULL || zipOpenNewFileInZip(
            archive,
            entry_name,
            NULL,
            NULL,
            0,
            NULL,
            0,
            NULL,
            Z_DEFLATED,
            Z_BEST_COMPRESSION) != ZIP_OK) {
        if (archive != NULL) {
            zipClose(archive, NULL);
        }
        return 0;
    }
    while (data_size > 0) {
        size_t chunk_size = data_size > sizeof(zeros) ? sizeof(zeros) : data_size;
        if (zipWriteInFileInZip(archive, zeros, (unsigned) chunk_size) != ZIP_OK) {
            zipCloseFileInZip(archive);
            zipClose(archive, NULL);
            return 0;
        }
        data_size -= chunk_size;
    }
    return zipCloseFileInZip(archive) == ZIP_OK && zipClose(archive, NULL) == ZIP_OK;
}

static long find_signature(FILE *file, uint32_t signature) {
    unsigned char window[4] = {0};
    long offset = 0;
    int value;
    while ((value = fgetc(file)) != EOF) {
        window[offset % 4] = (unsigned char) value;
        if (offset >= 3) {
            unsigned char ordered[4] = {
                window[(offset - 3) % 4],
                window[(offset - 2) % 4],
                window[(offset - 1) % 4],
                window[offset % 4]
            };
            uint32_t found = (uint32_t) ordered[0] |
                    ((uint32_t) ordered[1] << 8) |
                    ((uint32_t) ordered[2] << 16) |
                    ((uint32_t) ordered[3] << 24);
            if (found == signature) {
                return offset - 3;
            }
        }
        offset++;
    }
    return -1;
}

static int patch_u32_after_signature(
        const char *path,
        uint32_t signature,
        long field_offset,
        uint32_t value) {
    FILE *file = fopen(path, "r+b");
    long signature_offset;
    unsigned char bytes[4];
    if (file == NULL) {
        return 0;
    }
    signature_offset = find_signature(file, signature);
    if (signature_offset < 0 || fseek(file, signature_offset + field_offset, SEEK_SET) != 0) {
        fclose(file);
        return 0;
    }
    bytes[0] = (unsigned char) value;
    bytes[1] = (unsigned char) (value >> 8);
    bytes[2] = (unsigned char) (value >> 16);
    bytes[3] = (unsigned char) (value >> 24);
    if (fwrite(bytes, 1, sizeof(bytes), file) != sizeof(bytes)) {
        fclose(file);
        return 0;
    }
    return fclose(file) == 0;
}

static int corrupt_first_compressed_byte(const char *path) {
    FILE *file = fopen(path, "r+b");
    long local_header;
    unsigned char lengths[4];
    long data_offset;
    unsigned char invalid_deflate = 0xff;
    if (file == NULL) {
        return 0;
    }
    local_header = find_signature(file, 0x04034b50U);
    if (local_header < 0 || fseek(file, local_header + 26, SEEK_SET) != 0 ||
            fread(lengths, 1, sizeof(lengths), file) != sizeof(lengths)) {
        fclose(file);
        return 0;
    }
    data_offset = local_header + 30 +
            (long) ((unsigned) lengths[0] | ((unsigned) lengths[1] << 8)) +
            (long) ((unsigned) lengths[2] | ((unsigned) lengths[3] << 8));
    if (fseek(file, data_offset, SEEK_SET) != 0 ||
            fwrite(&invalid_deflate, 1, 1, file) != 1) {
        fclose(file);
        return 0;
    }
    return fclose(file) == 0;
}

static int zip_read_returns_negative(const char *path, const char *entry_name) {
    unsigned char buffer[32];
    unzFile archive = unzOpen(path);
    int read_result = 0;
    int saw_negative = 0;
    if (archive == NULL || unzLocateFile(archive, entry_name, 0) != UNZ_OK ||
            unzOpenCurrentFile(archive) != UNZ_OK) {
        if (archive != NULL) {
            unzClose(archive);
        }
        return 0;
    }
    while ((read_result = unzReadCurrentFile(archive, buffer, sizeof(buffer))) > 0) {
    }
    saw_negative = read_result < 0;
    unzCloseCurrentFile(archive);
    unzClose(archive);
    return saw_negative;
}

static int bounded_read(
        const char *path,
        const char *entry_name,
        size_t max_bytes,
        unsigned char **output,
        size_t *output_size) {
    unzFile archive = unzOpen(path);
    int result;
    if (archive == NULL) {
        return 0;
    }
    result = bounded_zip_read_file(archive, entry_name, max_bytes, output, output_size);
    if (unzClose(archive) != UNZ_OK) {
        return 0;
    }
    return result;
}

static int small_entry_reads_completely(const char *directory) {
    static const unsigned char expected[] = "small legal cover";
    char path[512];
    unsigned char *actual = NULL;
    size_t actual_size = 0;
    snprintf(path, sizeof(path), "%s/small.zip", directory);
    if (!write_zip_entry(path, "cover.bin", expected, sizeof(expected) - 1) ||
            !bounded_read(path, "cover.bin", 1024, &actual, &actual_size)) {
        free(actual);
        return 0;
    }
    if (actual_size != sizeof(expected) - 1 ||
            memcmp(actual, expected, sizeof(expected) - 1) != 0) {
        free(actual);
        return 0;
    }
    free(actual);
    return 1;
}

static int oversized_high_compression_entry_is_rejected(const char *directory) {
    unsigned char data[4097] = {0};
    char path[512];
    unsigned char *output = (unsigned char *) (uintptr_t) 1;
    size_t output_size = 99;
    snprintf(path, sizeof(path), "%s/oversized.zip", directory);
    return write_zip_entry(path, "cover.bin", data, sizeof(data)) &&
            !bounded_read(path, "cover.bin", 4096, &output, &output_size) &&
            output == NULL && output_size == 0;
}

static int crc_failure_never_returns_partial_bytes(const char *directory) {
    static const unsigned char data[] = "crc checked bytes";
    char path[512];
    unsigned char *output = (unsigned char *) (uintptr_t) 1;
    size_t output_size = 99;
    snprintf(path, sizeof(path), "%s/bad-crc.zip", directory);
    return write_zip_entry(path, "cover.bin", data, sizeof(data) - 1) &&
            patch_u32_after_signature(path, 0x02014b50U, 16, 0) &&
            !bounded_read(path, "cover.bin", 1024, &output, &output_size) &&
            output == NULL && output_size == 0;
}

static int declared_short_read_never_returns_partial_bytes(const char *directory) {
    static const unsigned char data[] = "short";
    char path[512];
    unsigned char *output = (unsigned char *) (uintptr_t) 1;
    size_t output_size = 99;
    snprintf(path, sizeof(path), "%s/short.zip", directory);
    return write_zip_entry(path, "cover.bin", data, sizeof(data) - 1) &&
            patch_u32_after_signature(path, 0x02014b50U, 24, 100) &&
            !bounded_read(path, "cover.bin", 1024, &output, &output_size) &&
            output == NULL && output_size == 0;
}

static int negative_decompression_never_returns_partial_bytes(const char *directory) {
    static const unsigned char data[] = "negative decompression result";
    char path[512];
    unsigned char *output = (unsigned char *) (uintptr_t) 1;
    size_t output_size = 99;
    snprintf(path, sizeof(path), "%s/bad-deflate.zip", directory);
    return write_zip_entry(path, "cover.bin", data, sizeof(data) - 1) &&
            corrupt_first_compressed_byte(path) &&
            zip_read_returns_negative(path, "cover.bin") &&
            !bounded_read(path, "cover.bin", 1024, &output, &output_size) &&
            output == NULL && output_size == 0;
}

static int make_files_directory(const char *root, const char *name, char *output, size_t capacity) {
    if (snprintf(output, capacity, "%s/%s", root, name) < 0 || mkdir(output, 0700) != 0) {
        return 0;
    }
    return 1;
}

static int count_directory_entries(const char *path) {
    DIR *directory = opendir(path);
    struct dirent *entry;
    int count = 0;
    if (directory == NULL) {
        return 0;
    }
    while ((entry = readdir(directory)) != NULL) {
        if (strcmp(entry->d_name, ".") != 0 && strcmp(entry->d_name, "..") != 0) {
            count++;
        }
    }
    closedir(directory);
    return count;
}

static int cover_directory_is_empty(const char *files_directory) {
    char covers[512];
    snprintf(covers, sizeof(covers), "%s/covers", files_directory);
    return count_directory_entries(covers) == 0;
}

static int epub_cover_call(
        const char *zip_path,
        const char *entry_name,
        const char *files_directory,
        char *output,
        size_t output_capacity) {
    unzFile archive = unzOpen(zip_path);
    int result;
    if (archive == NULL) {
        return 0;
    }
    result = publication_cover_write_epub_entry(
            archive,
            entry_name,
            "png",
            files_directory,
            output,
            output_capacity);
    if (unzClose(archive) != UNZ_OK) {
        return 0;
    }
    return result;
}

static int legal_epub_cover_uses_real_private_filesystem(const char *directory) {
    static const unsigned char png[] = {
        0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3
    };
    static const char entry_name[] = "OPS/images/../../../untrusted-title/cover.png";
    char zip_path[512];
    char files_directory[512];
    char canonical_files[512];
    char output[1024];
    char expected_prefix[1024];
    char covers_directory[1024];
    unsigned char actual[sizeof(png)];
    FILE *file;
    struct stat status;
    struct stat covers_status;
    snprintf(zip_path, sizeof(zip_path), "%s/legal-cover.zip", directory);
    if (!make_files_directory(directory, "legal-files", files_directory, sizeof(files_directory)) ||
            !write_zip_entry(zip_path, entry_name, png, sizeof(png)) ||
            realpath(files_directory, canonical_files) == NULL ||
            !epub_cover_call(zip_path, entry_name, files_directory, output, sizeof(output))) {
        return 0;
    }
    snprintf(expected_prefix, sizeof(expected_prefix), "%s/covers/cover_", canonical_files);
    snprintf(covers_directory, sizeof(covers_directory), "%s/covers", canonical_files);
    if (strncmp(output, expected_prefix, strlen(expected_prefix)) != 0 ||
            strstr(output, "untrusted-title") != NULL || stat(output, &status) != 0 ||
            (status.st_mode & 0777) != 0600 || stat(covers_directory, &covers_status) != 0 ||
            (covers_status.st_mode & 0777) != 0700 ||
            count_directory_entries(covers_directory) != 1) {
        return 0;
    }
    file = fopen(output, "rb");
    if (file == NULL || fread(actual, 1, sizeof(actual), file) != sizeof(actual) ||
            fclose(file) != 0 || memcmp(actual, png, sizeof(png)) != 0) {
        return 0;
    }
    return 1;
}

static int oversized_epub_cover_is_rejected_without_file(const char *directory) {
    char zip_path[512];
    char files_directory[512];
    char output[512] = "not-cleared";
    snprintf(zip_path, sizeof(zip_path), "%s/oversized-cover.zip", directory);
    return make_files_directory(directory, "oversized-files", files_directory, sizeof(files_directory)) &&
            write_repeated_zip_entry(
                    zip_path,
                    "OPS/cover.png",
                    PUBLICATION_COVER_MAX_BYTES + 1) &&
            !epub_cover_call(zip_path, "OPS/cover.png", files_directory, output, sizeof(output)) &&
            output[0] == '\0' && cover_directory_is_empty(files_directory);
}

static int corrupt_epub_cover_is_rejected_without_file(const char *directory) {
    static const unsigned char png[] = {
        0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3
    };
    char zip_path[512];
    char files_directory[512];
    char output[512] = "not-cleared";
    snprintf(zip_path, sizeof(zip_path), "%s/corrupt-cover.zip", directory);
    return make_files_directory(directory, "corrupt-files", files_directory, sizeof(files_directory)) &&
            write_zip_entry(zip_path, "OPS/cover.png", png, sizeof(png)) &&
            patch_u32_after_signature(zip_path, 0x02014b50U, 16, 0) &&
            !epub_cover_call(zip_path, "OPS/cover.png", files_directory, output, sizeof(output)) &&
            output[0] == '\0' && cover_directory_is_empty(files_directory);
}

static int partial_epub_cover_is_rejected_without_file(const char *directory) {
    static const unsigned char png[] = {
        0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3
    };
    char zip_path[512];
    char files_directory[512];
    char output[512] = "not-cleared";
    snprintf(zip_path, sizeof(zip_path), "%s/partial-cover.zip", directory);
    return make_files_directory(directory, "partial-files", files_directory, sizeof(files_directory)) &&
            write_zip_entry(zip_path, "OPS/cover.png", png, sizeof(png)) &&
            patch_u32_after_signature(zip_path, 0x02014b50U, 24, 100) &&
            !epub_cover_call(zip_path, "OPS/cover.png", files_directory, output, sizeof(output)) &&
            output[0] == '\0' && cover_directory_is_empty(files_directory);
}

static int legal_mobi_cover_uses_real_private_filesystem(const char *directory) {
    static const unsigned char png[] = {
        0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 4, 5, 6
    };
    char files_directory[512];
    char output[1024];
    unsigned char actual[sizeof(png)];
    FILE *file;
    struct stat status;
    if (!make_files_directory(directory, "mobi-legal-files", files_directory, sizeof(files_directory)) ||
            !publication_cover_write_mobi_record(
                    png,
                    sizeof(png),
                    files_directory,
                    output,
                    sizeof(output)) ||
            stat(output, &status) != 0 || (status.st_mode & 0777) != 0600) {
        return 0;
    }
    file = fopen(output, "rb");
    if (file == NULL || fread(actual, 1, sizeof(actual), file) != sizeof(actual) ||
            fclose(file) != 0 || memcmp(actual, png, sizeof(png)) != 0) {
        return 0;
    }
    return 1;
}

static int oversized_mobi_cover_is_rejected_before_write(const char *directory) {
    unsigned char *oversized = (unsigned char *) calloc(PUBLICATION_COVER_MAX_BYTES + 1, 1);
    static const unsigned char png_magic[] = {
        0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };
    char files_directory[512];
    char output[1024] = "not-cleared";
    int result;
    if (oversized == NULL ||
            !make_files_directory(
                    directory,
                    "mobi-oversized-files",
                    files_directory,
                    sizeof(files_directory))) {
        free(oversized);
        return 0;
    }
    memcpy(oversized, png_magic, sizeof(png_magic));
    result = !publication_cover_write_mobi_record(
            oversized,
            PUBLICATION_COVER_MAX_BYTES + 1,
            files_directory,
            output,
            sizeof(output)) &&
            output[0] == '\0' && cover_directory_is_empty(files_directory);
    free(oversized);
    return result;
}

static int symlinked_cover_directory_cannot_escape_canonical_files(const char *directory) {
    static const unsigned char png[] = {
        0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 7, 8, 9
    };
    char files_directory[512];
    char outside_directory[512];
    char covers_link[1024];
    char output[1024] = "not-cleared";
    if (!make_files_directory(directory, "symlink-files", files_directory, sizeof(files_directory)) ||
            !make_files_directory(directory, "outside-covers", outside_directory, sizeof(outside_directory))) {
        return 0;
    }
    snprintf(covers_link, sizeof(covers_link), "%s/covers", files_directory);
    if (symlink(outside_directory, covers_link) != 0) {
        return 0;
    }
    return !publication_cover_write_mobi_record(
            png,
            sizeof(png),
            files_directory,
            output,
            sizeof(output)) &&
            output[0] == '\0' && count_directory_entries(outside_directory) == 0;
}

typedef struct close_race_context_s {
    char covers_directory[1024];
    atomic_int stop;
    atomic_int closed_cover_fd;
} close_race_context;

static void *close_cover_fd_when_created(void *raw_context) {
    close_race_context *context = (close_race_context *) raw_context;
    while (!atomic_load_explicit(&context->stop, memory_order_acquire)) {
        DIR *covers = opendir(context->covers_directory);
        struct dirent *cover_entry;
        if (covers == NULL) {
            continue;
        }
        while ((cover_entry = readdir(covers)) != NULL &&
                !atomic_load_explicit(&context->closed_cover_fd, memory_order_acquire)) {
            char target[PATH_MAX];
            DIR *file_descriptors;
            struct dirent *descriptor_entry;
            if (strcmp(cover_entry->d_name, ".") == 0 || strcmp(cover_entry->d_name, "..") == 0) {
                continue;
            }
            snprintf(
                    target,
                    sizeof(target),
                    "%s/%s",
                    context->covers_directory,
                    cover_entry->d_name);
            file_descriptors = opendir("/proc/self/fd");
            if (file_descriptors == NULL) {
                continue;
            }
            while ((descriptor_entry = readdir(file_descriptors)) != NULL) {
                char descriptor_path[64];
                char descriptor_target[PATH_MAX];
                char *end = NULL;
                long descriptor = strtol(descriptor_entry->d_name, &end, 10);
                ssize_t length;
                if (end == descriptor_entry->d_name || *end != '\0' || descriptor < 0) {
                    continue;
                }
                snprintf(
                        descriptor_path,
                        sizeof(descriptor_path),
                        "/proc/self/fd/%ld",
                        descriptor);
                length = readlink(
                        descriptor_path,
                        descriptor_target,
                        sizeof(descriptor_target) - 1);
                if (length > 0) {
                    descriptor_target[length] = '\0';
                    if (strcmp(descriptor_target, target) == 0 && close((int) descriptor) == 0) {
                        atomic_store_explicit(
                                &context->closed_cover_fd,
                                1,
                                memory_order_release);
                        break;
                    }
                }
            }
            closedir(file_descriptors);
        }
        closedir(covers);
    }
    return NULL;
}

static int real_close_failure_removes_partial_cover(const char *directory) {
    unsigned char *data = (unsigned char *) calloc(PUBLICATION_COVER_MAX_BYTES, 1);
    static const unsigned char png_magic[] = {
        0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };
    char files_directory[512];
    char output[1024] = "not-cleared";
    close_race_context context = {
        .covers_directory = {0},
        .stop = ATOMIC_VAR_INIT(0),
        .closed_cover_fd = ATOMIC_VAR_INIT(0)
    };
    pthread_t closer;
    int write_result;
    int thread_result;
    if (data == NULL) {
        return 0;
    }
    memcpy(data, png_magic, sizeof(png_magic));
    if (!make_files_directory(
            directory,
            "close-failure-files",
            files_directory,
            sizeof(files_directory))) {
        free(data);
        return 0;
    }
    snprintf(
            context.covers_directory,
            sizeof(context.covers_directory),
            "%s/covers",
            files_directory);
    if (mkdir(context.covers_directory, 0700) != 0 ||
            pthread_create(&closer, NULL, close_cover_fd_when_created, &context) != 0) {
        free(data);
        return 0;
    }
    write_result = publication_cover_write_mobi_record(
            data,
            PUBLICATION_COVER_MAX_BYTES,
            files_directory,
            output,
            sizeof(output));
    atomic_store_explicit(&context.stop, 1, memory_order_release);
    thread_result = pthread_join(closer, NULL);
    free(data);
    return !write_result && thread_result == 0 &&
            atomic_load_explicit(&context.closed_cover_fd, memory_order_acquire) &&
            output[0] == '\0' && cover_directory_is_empty(files_directory);
}

static int remove_tree_entry(
        const char *path,
        const struct stat *status,
        int type,
        struct FTW *walk) {
    (void) status;
    (void) type;
    (void) walk;
    return remove(path);
}

int main(void) {
    char directory[] = "/tmp/pagenest-cover-zip-XXXXXX";
    int failures = 0;
#define RUN_FIXTURE(fixture) do { \
    if (!(fixture(directory))) { \
        fprintf(stderr, "%s failed\n", #fixture); \
        failures++; \
    } \
} while (0)
    if (mkdtemp(directory) == NULL) {
        return 1;
    }
    RUN_FIXTURE(small_entry_reads_completely);
    RUN_FIXTURE(oversized_high_compression_entry_is_rejected);
    RUN_FIXTURE(crc_failure_never_returns_partial_bytes);
    RUN_FIXTURE(declared_short_read_never_returns_partial_bytes);
    RUN_FIXTURE(negative_decompression_never_returns_partial_bytes);
    RUN_FIXTURE(legal_epub_cover_uses_real_private_filesystem);
    RUN_FIXTURE(oversized_epub_cover_is_rejected_without_file);
    RUN_FIXTURE(corrupt_epub_cover_is_rejected_without_file);
    RUN_FIXTURE(partial_epub_cover_is_rejected_without_file);
    RUN_FIXTURE(legal_mobi_cover_uses_real_private_filesystem);
    RUN_FIXTURE(oversized_mobi_cover_is_rejected_before_write);
    RUN_FIXTURE(symlinked_cover_directory_cannot_escape_canonical_files);
    RUN_FIXTURE(real_close_failure_removes_partial_cover);
#undef RUN_FIXTURE
    if (nftw(directory, remove_tree_entry, 16, FTW_DEPTH | FTW_PHYS) != 0) {
        fprintf(stderr, "temporary fixture cleanup failed\n");
        failures++;
    }
    return failures;
}
