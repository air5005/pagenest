#include "private_book_store_publish.h"

static int same_regular_state(
        const struct private_book_store_file_state *first,
        const struct private_book_store_file_state *second) {
    return first->regular && second->regular &&
            first->device == second->device &&
            first->inode == second->inode &&
            first->size == second->size &&
            first->modified_seconds == second->modified_seconds &&
            first->modified_nanoseconds == second->modified_nanoseconds &&
            first->changed_seconds == second->changed_seconds &&
            first->changed_nanoseconds == second->changed_nanoseconds;
}

struct private_book_store_publish_result private_book_store_publish_no_replace(
        const struct private_book_store_publish_operations *operations,
        void *context) {
    struct private_book_store_publish_result result = {0, 0, 0};
    do {
        result.operation_errno = operations->lock(context);
    } while (result.operation_errno == PRIVATE_BOOK_STORE_EINTR);
    if (result.operation_errno != 0) return result;

    struct private_book_store_file_state descriptor_state;
    struct private_book_store_file_state entry_state;
    result.operation_errno = operations->read_part_descriptor(context, &descriptor_state);
    if (result.operation_errno == 0) {
        result.operation_errno = operations->read_part_entry(context, &entry_state);
    }
    if (result.operation_errno == 0 && !same_regular_state(&descriptor_state, &entry_state)) {
        result.operation_errno = PRIVATE_BOOK_STORE_ESTALE;
    }
    if (result.operation_errno == 0) {
        result.operation_errno = operations->rename_no_replace(
                context,
                PRIVATE_BOOK_STORE_RENAME_NOREPLACE);
    }
    if (result.operation_errno == 0) {
        result.published = 1;
        result.operation_errno = operations->read_published_descriptor(
                context,
                &descriptor_state);
    }
    if (result.operation_errno == 0) {
        result.operation_errno = operations->read_final_entry(context, &entry_state);
    }
    if (result.operation_errno == 0 && !same_regular_state(&descriptor_state, &entry_state)) {
        result.operation_errno = PRIVATE_BOOK_STORE_EIO;
    }
    if (result.operation_errno == 0) {
        result.operation_errno = operations->sync_root(context);
    }
    result.unlock_errno = operations->unlock(context);
    return result;
}
