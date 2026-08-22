#ifndef PAGENEST_SAFE_COVER_WRITER_H
#define PAGENEST_SAFE_COVER_WRITER_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#ifdef SAFE_COVER_TESTING
#define SAFE_COVER_API
#else
#define SAFE_COVER_API __attribute__((visibility("hidden")))
#endif

typedef struct SafeCoverOps {
    int (*real_path)(const char *input, char *output, size_t capacity);
    int (*open_directory)(const char *path);
    int (*ensure_directory_at)(int parent_fd, const char *name);
    int (*open_directory_at)(int parent_fd, const char *name);
    int (*random_bytes)(unsigned char *output, size_t length);
    int (*open_exclusive_file_at)(int directory_fd, const char *name);
    long long (*write_bytes)(int file_fd, const unsigned char *data, size_t length);
    int (*sync_file)(int file_fd);
    int (*close_fd)(int fd);
    int (*delete_file_at)(int directory_fd, const char *name);
} SafeCoverOps;

SAFE_COVER_API const char *safe_cover_extension_from_bytes(
        const unsigned char *data,
        size_t data_size);

#ifdef SAFE_COVER_TESTING
int safe_cover_write_bytes_with_ops(
        const char *files_directory,
        const char *extension,
        const unsigned char *data,
        size_t data_size,
        char *output_path,
        size_t output_capacity,
        const SafeCoverOps *ops);
#endif

#ifndef SAFE_COVER_TESTING
SAFE_COVER_API int safe_cover_write_bytes(
        const char *files_directory,
        const char *extension,
        const unsigned char *data,
        size_t data_size,
        char *output_path,
        size_t output_capacity);
#endif

#ifdef __cplusplus
}
#endif

#undef SAFE_COVER_API

#endif
