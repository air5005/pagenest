#include "safe_cover_writer.h"

#define SAFE_COVER_PATH_CAPACITY 4096
#define SAFE_COVER_NAME_CAPACITY 64
#define SAFE_COVER_RANDOM_BYTES 16

static size_t safe_text_length(const char *text) {
    size_t length = 0;
    if (text == NULL) {
        return 0;
    }
    while (text[length] != '\0') {
        length++;
    }
    return length;
}

static int safe_text_equal(const char *left, const char *right) {
    size_t index = 0;
    if (left == NULL || right == NULL) {
        return 0;
    }
    while (left[index] != '\0' && right[index] != '\0') {
        if (left[index] != right[index]) {
            return 0;
        }
        index++;
    }
    return left[index] == right[index];
}

static int safe_copy_text(char *output, size_t capacity, const char *value) {
    size_t index;
    size_t length = safe_text_length(value);
    if (output == NULL || capacity == 0 || value == NULL || length + 1 > capacity) {
        return 0;
    }
    for (index = 0; index <= length; index++) {
        output[index] = value[index];
    }
    return 1;
}

static int safe_extension_allowed(const char *extension) {
    return safe_text_equal(extension, "jpg") ||
            safe_text_equal(extension, "png") ||
            safe_text_equal(extension, "gif") ||
            safe_text_equal(extension, "bmp") ||
            safe_text_equal(extension, "webp");
}

static int safe_bytes_equal(
        const unsigned char *data,
        const unsigned char *magic,
        size_t length) {
    size_t index;
    for (index = 0; index < length; index++) {
        if (data[index] != magic[index]) {
            return 0;
        }
    }
    return 1;
}

const char *safe_cover_extension_from_bytes(const unsigned char *data, size_t data_size) {
    static const unsigned char jpeg_magic[] = {0xff, 0xd8, 0xff};
    static const unsigned char png_magic[] = {0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    static const unsigned char gif_magic[] = {0x47, 0x49, 0x46, 0x38};
    static const unsigned char webp_magic[] = {0x57, 0x45, 0x42, 0x50};
    unsigned long declared_size;
    if (data == NULL) {
        return NULL;
    }
    if (data_size >= sizeof(jpeg_magic) &&
            safe_bytes_equal(data, jpeg_magic, sizeof(jpeg_magic))) {
        return "jpg";
    }
    if (data_size >= sizeof(png_magic) &&
            safe_bytes_equal(data, png_magic, sizeof(png_magic))) {
        return "png";
    }
    if (data_size >= sizeof(gif_magic) &&
            safe_bytes_equal(data, gif_magic, sizeof(gif_magic))) {
        return "gif";
    }
    if (data_size >= 6 && data[0] == 0x42 && data[1] == 0x4d) {
        declared_size = (unsigned long) data[2] |
                ((unsigned long) data[3] << 8) |
                ((unsigned long) data[4] << 16) |
                ((unsigned long) data[5] << 24);
        if (declared_size == data_size) {
            return "bmp";
        }
    }
    if (data_size >= 12 && data[0] == 0x52 && data[1] == 0x49 &&
            data[2] == 0x46 && data[3] == 0x46 &&
            safe_bytes_equal(data + 8, webp_magic, sizeof(webp_magic))) {
        declared_size = (unsigned long) data[4] |
                ((unsigned long) data[5] << 8) |
                ((unsigned long) data[6] << 16) |
                ((unsigned long) data[7] << 24);
        if (declared_size + 8 == data_size) {
            return "webp";
        }
    }
    return NULL;
}

static int safe_join_cover_directory(
        const char *canonical_files_directory,
        char *output,
        size_t capacity) {
    static const char suffix[] = "covers";
    size_t base_length = safe_text_length(canonical_files_directory);
    size_t suffix_length = sizeof(suffix) - 1;
    size_t index;
    int root = base_length == 1 && canonical_files_directory[0] == '/';
    size_t required = base_length + (root ? 0 : 1) + suffix_length + 1;
    if (base_length == 0 || canonical_files_directory[0] != '/' || required > capacity) {
        return 0;
    }
    for (index = 0; index < base_length; index++) {
        output[index] = canonical_files_directory[index];
    }
    if (!root) {
        output[index++] = '/';
    }
    for (base_length = 0; base_length < suffix_length; base_length++) {
        output[index++] = suffix[base_length];
    }
    output[index] = '\0';
    return 1;
}

static int safe_make_name(
        const unsigned char random[SAFE_COVER_RANDOM_BYTES],
        const char *extension,
        char *output,
        size_t capacity) {
    static const char prefix[] = "cover_";
    static const char hex[] = "0123456789abcdef";
    size_t extension_length = safe_text_length(extension);
    size_t index;
    size_t position = 0;
    size_t required = sizeof(prefix) - 1 + SAFE_COVER_RANDOM_BYTES * 2 + 1 +
            extension_length + 1;
    if (!safe_extension_allowed(extension) || required > capacity) {
        return 0;
    }
    for (index = 0; index < sizeof(prefix) - 1; index++) {
        output[position++] = prefix[index];
    }
    for (index = 0; index < SAFE_COVER_RANDOM_BYTES; index++) {
        output[position++] = hex[random[index] >> 4];
        output[position++] = hex[random[index] & 0x0f];
    }
    output[position++] = '.';
    for (index = 0; index < extension_length; index++) {
        output[position++] = extension[index];
    }
    output[position] = '\0';
    return 1;
}

static int safe_join_cover_path(
        const char *canonical_cover_directory,
        const char *name,
        char *output,
        size_t capacity) {
    size_t directory_length = safe_text_length(canonical_cover_directory);
    size_t name_length = safe_text_length(name);
    size_t index;
    size_t position = 0;
    if (directory_length == 0 || name_length == 0 ||
            directory_length + 1 + name_length + 1 > capacity) {
        return 0;
    }
    for (index = 0; index < directory_length; index++) {
        output[position++] = canonical_cover_directory[index];
    }
    output[position++] = '/';
    for (index = 0; index < name_length; index++) {
        output[position++] = name[index];
    }
    output[position] = '\0';
    return 1;
}

#ifdef SAFE_COVER_TESTING
#define SAFE_COVER_INTERNAL
#else
#define SAFE_COVER_INTERNAL static
#endif

SAFE_COVER_INTERNAL int safe_cover_write_bytes_with_ops(
        const char *files_directory,
        const char *extension,
        const unsigned char *data,
        size_t data_size,
        char *output_path,
        size_t output_capacity,
        const SafeCoverOps *ops) {
    char canonical_files[SAFE_COVER_PATH_CAPACITY];
    char expected_covers[SAFE_COVER_PATH_CAPACITY];
    char canonical_covers[SAFE_COVER_PATH_CAPACITY];
    char candidate_path[SAFE_COVER_PATH_CAPACITY];
    char name[SAFE_COVER_NAME_CAPACITY];
    unsigned char random[SAFE_COVER_RANDOM_BYTES];
    size_t written = 0;
    int files_fd = -1;
    int covers_fd = -1;
    int file_fd = -1;
    int created = 0;
    int success = 0;

    if (output_path != NULL && output_capacity > 0) {
        output_path[0] = '\0';
    }
    if (files_directory == NULL || !safe_extension_allowed(extension) ||
            data == NULL || data_size == 0 || output_path == NULL || output_capacity == 0 ||
            ops == NULL || ops->real_path == NULL || ops->open_directory == NULL ||
            ops->ensure_directory_at == NULL || ops->open_directory_at == NULL ||
            ops->random_bytes == NULL || ops->open_exclusive_file_at == NULL ||
            ops->write_bytes == NULL || ops->sync_file == NULL || ops->close_fd == NULL ||
            ops->delete_file_at == NULL) {
        return 0;
    }
    if (!ops->real_path(files_directory, canonical_files, sizeof(canonical_files))) {
        goto cleanup;
    }
    files_fd = ops->open_directory(canonical_files);
    if (files_fd < 0 || !ops->ensure_directory_at(files_fd, "covers") ||
            !safe_join_cover_directory(canonical_files, expected_covers, sizeof(expected_covers)) ||
            !ops->real_path(expected_covers, canonical_covers, sizeof(canonical_covers)) ||
            !safe_text_equal(expected_covers, canonical_covers)) {
        goto cleanup;
    }
    covers_fd = ops->open_directory_at(files_fd, "covers");
    if (covers_fd < 0 || !ops->random_bytes(random, sizeof(random)) ||
            !safe_make_name(random, extension, name, sizeof(name)) ||
            !safe_join_cover_path(canonical_covers, name, candidate_path, sizeof(candidate_path)) ||
            !safe_copy_text(output_path, output_capacity, candidate_path)) {
        output_path[0] = '\0';
        goto cleanup;
    }
    output_path[0] = '\0';
    file_fd = ops->open_exclusive_file_at(covers_fd, name);
    if (file_fd < 0) {
        goto cleanup;
    }
    created = 1;
    while (written < data_size) {
        long long count = ops->write_bytes(file_fd, data + written, data_size - written);
        if (count <= 0 || (size_t) count > data_size - written) {
            goto cleanup;
        }
        written += (size_t) count;
    }
    if (!ops->sync_file(file_fd)) {
        goto cleanup;
    }
    if (!ops->close_fd(file_fd)) {
        file_fd = -1;
        goto cleanup;
    }
    file_fd = -1;
    if (!safe_copy_text(output_path, output_capacity, candidate_path)) {
        goto cleanup;
    }
    success = 1;

cleanup:
    if (file_fd >= 0) {
        ops->close_fd(file_fd);
    }
    if (!success && created) {
        ops->delete_file_at(covers_fd, name);
    }
    if (covers_fd >= 0) {
        ops->close_fd(covers_fd);
    }
    if (files_fd >= 0) {
        ops->close_fd(files_fd);
    }
    if (!success) {
        output_path[0] = '\0';
    }
    return success;
}

#ifndef SAFE_COVER_TESTING

#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <unistd.h>

static int system_real_path(const char *input, char *output, size_t capacity) {
    char resolved[SAFE_COVER_PATH_CAPACITY];
    if (realpath(input, resolved) == NULL) {
        return 0;
    }
    return safe_copy_text(output, capacity, resolved);
}

static int system_open_directory(const char *path) {
    return open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
}

static int system_ensure_directory_at(int parent_fd, const char *name) {
    if (mkdirat(parent_fd, name, 0700) == 0) {
        return 1;
    }
    return errno == EEXIST;
}

static int system_open_directory_at(int parent_fd, const char *name) {
    return openat(parent_fd, name, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
}

static int system_random_bytes(unsigned char *output, size_t length) {
    size_t offset = 0;
    int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) {
        return 0;
    }
    while (offset < length) {
        ssize_t count = read(fd, output + offset, length - offset);
        if (count < 0 && errno == EINTR) {
            continue;
        }
        if (count <= 0) {
            close(fd);
            return 0;
        }
        offset += (size_t) count;
    }
    return close(fd) == 0;
}

static int system_open_exclusive_file_at(int directory_fd, const char *name) {
    return openat(
            directory_fd,
            name,
            O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW,
            0600);
}

static long long system_write_bytes(int file_fd, const unsigned char *data, size_t length) {
    ssize_t count;
    do {
        count = write(file_fd, data, length);
    } while (count < 0 && errno == EINTR);
    return (long long) count;
}

static int system_sync_file(int file_fd) {
    return fsync(file_fd) == 0;
}

static int system_close_fd(int fd) {
    return close(fd) == 0;
}

static int system_delete_file_at(int directory_fd, const char *name) {
    return unlinkat(directory_fd, name, 0) == 0;
}

static const SafeCoverOps system_ops = {
    system_real_path,
    system_open_directory,
    system_ensure_directory_at,
    system_open_directory_at,
    system_random_bytes,
    system_open_exclusive_file_at,
    system_write_bytes,
    system_sync_file,
    system_close_fd,
    system_delete_file_at,
};

int safe_cover_write_bytes(
        const char *files_directory,
        const char *extension,
        const unsigned char *data,
        size_t data_size,
        char *output_path,
        size_t output_capacity) {
    return safe_cover_write_bytes_with_ops(
            files_directory,
            extension,
            data,
            data_size,
            output_path,
            output_capacity,
            &system_ops);
}

#endif
