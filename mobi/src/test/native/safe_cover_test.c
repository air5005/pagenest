#include "safe_cover_writer.h"

static unsigned char written[32];
static size_t written_size;
static int random_failure;
static int write_failure;
static int close_failure;
static int sync_failure;
static int covers_escape;
static int opened_file;
static int deleted_file;
static int closed_file;

static size_t text_length(const char *text) {
    size_t length = 0;
    while (text[length] != '\0') {
        length++;
    }
    return length;
}

static int text_equal(const char *left, const char *right) {
    size_t index = 0;
    while (left[index] != '\0' && right[index] != '\0') {
        if (left[index] != right[index]) {
            return 0;
        }
        index++;
    }
    return left[index] == right[index];
}

static int text_contains(const char *text, const char *needle) {
    size_t index;
    size_t offset;
    size_t needle_length = text_length(needle);
    for (index = 0; text[index] != '\0'; index++) {
        for (offset = 0; offset < needle_length &&
                text[index + offset] == needle[offset]; offset++) {
        }
        if (offset == needle_length) {
            return 1;
        }
    }
    return 0;
}

static int copy_text(char *output, size_t capacity, const char *value) {
    size_t length = text_length(value);
    size_t index;
    if (length + 1 > capacity) {
        return 0;
    }
    for (index = 0; index <= length; index++) {
        output[index] = value[index];
    }
    return 1;
}

static int fake_real_path(const char *input, char *output, size_t capacity) {
    if (text_equal(input, "/trusted/files")) {
        return copy_text(output, capacity, "/trusted/files");
    }
    if (text_equal(input, "/trusted/files/covers")) {
        return copy_text(
                output,
                capacity,
                covers_escape ? "/attacker/covers" : "/trusted/files/covers");
    }
    return 0;
}

static int fake_open_directory(const char *path) {
    return text_equal(path, "/trusted/files") ? 10 : -1;
}

static int fake_ensure_directory_at(int parent_fd, const char *name) {
    return parent_fd == 10 && text_equal(name, "covers");
}

static int fake_open_directory_at(int parent_fd, const char *name) {
    return parent_fd == 10 && text_equal(name, "covers") ? 11 : -1;
}

static int fake_random_bytes(unsigned char *output, size_t length) {
    size_t index;
    if (random_failure) {
        return 0;
    }
    for (index = 0; index < length; index++) {
        output[index] = (unsigned char) index;
    }
    return 1;
}

static int fake_open_exclusive_file_at(int directory_fd, const char *name) {
    if (directory_fd != 11 || !text_equal(name, "cover_000102030405060708090a0b0c0d0e0f.png")) {
        return -1;
    }
    opened_file++;
    return 12;
}

static long long fake_write_bytes(int file_fd, const unsigned char *data, size_t length) {
    size_t index;
    size_t count = length > 3 ? 3 : length;
    if (file_fd != 12 || write_failure) {
        return -1;
    }
    for (index = 0; index < count; index++) {
        written[written_size++] = data[index];
    }
    return (long long) count;
}

static int fake_sync_file(int file_fd) {
    return file_fd == 12 && !sync_failure;
}

static int fake_close_fd(int fd) {
    if (fd == 12) {
        closed_file++;
    }
    return !(fd == 12 && close_failure);
}

static int fake_delete_file_at(int directory_fd, const char *name) {
    if (directory_fd == 11 &&
            text_equal(name, "cover_000102030405060708090a0b0c0d0e0f.png")) {
        deleted_file++;
        return 1;
    }
    return 0;
}

static const SafeCoverOps fake_ops = {
    fake_real_path,
    fake_open_directory,
    fake_ensure_directory_at,
    fake_open_directory_at,
    fake_random_bytes,
    fake_open_exclusive_file_at,
    fake_write_bytes,
    fake_sync_file,
    fake_close_fd,
    fake_delete_file_at,
};

static void reset_state(void) {
    written_size = 0;
    random_failure = 0;
    write_failure = 0;
    close_failure = 0;
    sync_failure = 0;
    covers_escape = 0;
    opened_file = 0;
    deleted_file = 0;
    closed_file = 0;
}

static int successful_write_uses_only_the_internal_generated_path(void) {
    static const unsigned char data[] = {1, 2, 3, 4, 5, 6, 7};
    const char *untrusted_publication_title = "../escape/owned";
    char output[128];
    size_t index;
    reset_state();

    if (!safe_cover_write_bytes_with_ops(
            "/trusted/files", "png", data, sizeof(data), output, sizeof(output), &fake_ops)) {
        return 0;
    }
    if (!text_equal(
            output,
            "/trusted/files/covers/cover_000102030405060708090a0b0c0d0e0f.png") ||
            text_contains(output, untrusted_publication_title) ||
            opened_file != 1 || deleted_file != 0 || written_size != sizeof(data)) {
        return 0;
    }
    for (index = 0; index < sizeof(data); index++) {
        if (written[index] != data[index]) {
            return 0;
        }
    }
    return 1;
}

static int rejected_extensions_never_create_a_file(void) {
    static const unsigned char data[] = {1};
    char output[128];
    reset_state();
    if (safe_cover_write_bytes_with_ops(
            "/trusted/files", "svg", data, sizeof(data), output, sizeof(output), &fake_ops)) {
        return 0;
    }
    if (safe_cover_write_bytes_with_ops(
            "/trusted/files", "../png", data, sizeof(data), output, sizeof(output), &fake_ops)) {
        return 0;
    }
    return opened_file == 0 && output[0] == '\0';
}

static int path_truncation_never_creates_a_file(void) {
    static const unsigned char data[] = {1};
    char output[16];
    reset_state();
    return !safe_cover_write_bytes_with_ops(
            "/trusted/files", "png", data, sizeof(data), output, sizeof(output), &fake_ops) &&
            opened_file == 0 && output[0] == '\0';
}

static int random_failure_never_creates_a_file(void) {
    static const unsigned char data[] = {1};
    char output[128];
    reset_state();
    random_failure = 1;
    return !safe_cover_write_bytes_with_ops(
            "/trusted/files", "png", data, sizeof(data), output, sizeof(output), &fake_ops) &&
            opened_file == 0 && output[0] == '\0';
}

static int escaped_canonical_cover_directory_is_rejected(void) {
    static const unsigned char data[] = {1};
    char output[128];
    reset_state();
    covers_escape = 1;
    return !safe_cover_write_bytes_with_ops(
            "/trusted/files", "png", data, sizeof(data), output, sizeof(output), &fake_ops) &&
            opened_file == 0 && output[0] == '\0';
}

static int write_failure_removes_the_partial_file(void) {
    static const unsigned char data[] = {1, 2, 3};
    char output[128];
    reset_state();
    write_failure = 1;
    return !safe_cover_write_bytes_with_ops(
            "/trusted/files", "png", data, sizeof(data), output, sizeof(output), &fake_ops) &&
            opened_file == 1 && deleted_file == 1 && output[0] == '\0';
}

static int close_failure_removes_the_written_file(void) {
    static const unsigned char data[] = {1, 2, 3};
    char output[128];
    reset_state();
    close_failure = 1;
    return !safe_cover_write_bytes_with_ops(
            "/trusted/files", "png", data, sizeof(data), output, sizeof(output), &fake_ops) &&
            opened_file == 1 && deleted_file == 1 && output[0] == '\0';
}

static int sync_failure_closes_and_removes_the_written_file(void) {
    static const unsigned char data[] = {1, 2, 3};
    char output[128];
    reset_state();
    sync_failure = 1;
    return !safe_cover_write_bytes_with_ops(
            "/trusted/files", "png", data, sizeof(data), output, sizeof(output), &fake_ops) &&
            opened_file == 1 && closed_file == 1 && deleted_file == 1 && output[0] == '\0';
}

static int image_magic_maps_only_to_allowlisted_extensions(void) {
    static const unsigned char jpeg[] = {0xff, 0xd8, 0xff, 0x00};
    static const unsigned char png[] = {0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    static const unsigned char gif[] = {0x47, 0x49, 0x46, 0x38};
    static const unsigned char bmp[] = {0x42, 0x4d, 0x06, 0x00, 0x00, 0x00};
    static const unsigned char webp[] = {
        0x52, 0x49, 0x46, 0x46, 0x04, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50
    };
    static const unsigned char unknown[] = {0x3c, 0x73, 0x76, 0x67};
    return text_equal(safe_cover_extension_from_bytes(jpeg, sizeof(jpeg)), "jpg") &&
            text_equal(safe_cover_extension_from_bytes(png, sizeof(png)), "png") &&
            text_equal(safe_cover_extension_from_bytes(gif, sizeof(gif)), "gif") &&
            text_equal(safe_cover_extension_from_bytes(bmp, sizeof(bmp)), "bmp") &&
            text_equal(safe_cover_extension_from_bytes(webp, sizeof(webp)), "webp") &&
            safe_cover_extension_from_bytes(unknown, sizeof(unknown)) == 0;
}

__declspec(dllexport) int run_safe_cover_tests(void) {
    int failures = 0;
    failures += !successful_write_uses_only_the_internal_generated_path();
    failures += !rejected_extensions_never_create_a_file();
    failures += !path_truncation_never_creates_a_file();
    failures += !random_failure_never_creates_a_file();
    failures += !escaped_canonical_cover_directory_is_rejected();
    failures += !write_failure_removes_the_partial_file();
    failures += !close_failure_removes_the_written_file();
    failures += !sync_failure_closes_and_removes_the_written_file();
    failures += !image_magic_maps_only_to_allowlisted_extensions();
    return failures;
}
