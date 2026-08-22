#ifndef PAGENEST_PRIVATE_BOOK_STORE_PUBLISH_H
#define PAGENEST_PRIVATE_BOOK_STORE_PUBLISH_H

#include <stdint.h>

#define PRIVATE_BOOK_STORE_RENAME_NOREPLACE 1U
#define PRIVATE_BOOK_STORE_EIO 5
#define PRIVATE_BOOK_STORE_EINTR 4
#define PRIVATE_BOOK_STORE_ESTALE 116

struct private_book_store_file_state {
    int regular;
    uint64_t device;
    uint64_t inode;
    int64_t size;
    int64_t modified_seconds;
    int64_t modified_nanoseconds;
    int64_t changed_seconds;
    int64_t changed_nanoseconds;
};

struct private_book_store_publish_operations {
    int (*lock)(void *context);
    int (*read_part_descriptor)(void *context, struct private_book_store_file_state *state);
    int (*read_part_entry)(void *context, struct private_book_store_file_state *state);
    int (*rename_no_replace)(void *context, unsigned int flags);
    int (*read_published_descriptor)(void *context, struct private_book_store_file_state *state);
    int (*read_final_entry)(void *context, struct private_book_store_file_state *state);
    int (*sync_root)(void *context);
    int (*unlock)(void *context);
};

struct private_book_store_publish_result {
    int operation_errno;
    int unlock_errno;
    int published;
};

struct private_book_store_publish_result private_book_store_publish_no_replace(
        const struct private_book_store_publish_operations *operations,
        void *context);

#endif
