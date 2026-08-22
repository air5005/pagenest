#include "../../main/cpp/private_book_store_publish.h"

#define TEST_EEXIST 17
#define TEST_ENOSYS 38

struct fake_publish_context {
    struct private_book_store_file_state part_descriptor;
    struct private_book_store_file_state part_entry;
    struct private_book_store_file_state published_descriptor;
    struct private_book_store_file_state final_entry;
    int rename_errno;
    int unlock_errno;
    int interrupted_lock_count;
    int lock_errno;
    int flock_errno;
    int lock_count;
    unsigned int rename_flags;
    int sync_count;
};

static int fake_blocking_flock(void *context) {
    struct fake_publish_context *fake = context;
    fake->lock_count++;
    if (fake->interrupted_lock_count > 0) {
        fake->interrupted_lock_count--;
        fake->flock_errno = PRIVATE_BOOK_STORE_EINTR;
        return -1;
    }
    if (fake->lock_errno != 0) {
        fake->flock_errno = fake->lock_errno;
        return -1;
    }
    return 0;
}

static int fake_lock(void *context) {
    struct fake_publish_context *fake = context;
    return fake_blocking_flock(fake) == 0 ? 0 : fake->flock_errno;
}

static int copy_state(
        struct private_book_store_file_state *target,
        const struct private_book_store_file_state *source) {
    *target = *source;
    return 0;
}

static int fake_part_descriptor(void *context, struct private_book_store_file_state *state) {
    return copy_state(state, &((struct fake_publish_context *) context)->part_descriptor);
}

static int fake_part_entry(void *context, struct private_book_store_file_state *state) {
    return copy_state(state, &((struct fake_publish_context *) context)->part_entry);
}

static int fake_rename(void *context, unsigned int flags) {
    struct fake_publish_context *fake = context;
    fake->rename_flags = flags;
    return fake->rename_errno;
}

static int fake_published_descriptor(
        void *context,
        struct private_book_store_file_state *state) {
    return copy_state(state, &((struct fake_publish_context *) context)->published_descriptor);
}

static int fake_final_entry(void *context, struct private_book_store_file_state *state) {
    return copy_state(state, &((struct fake_publish_context *) context)->final_entry);
}

static int fake_sync(void *context) {
    ((struct fake_publish_context *) context)->sync_count++;
    return 0;
}

static int fake_unlock(void *context) {
    return ((struct fake_publish_context *) context)->unlock_errno;
}

static const struct private_book_store_publish_operations OPERATIONS = {
        fake_lock,
        fake_part_descriptor,
        fake_part_entry,
        fake_rename,
        fake_published_descriptor,
        fake_final_entry,
        fake_sync,
        fake_unlock,
};

static struct private_book_store_file_state state_with_ctime(int64_t seconds) {
    struct private_book_store_file_state state = {
            1, 2, 3, 4, 5, 6, seconds, 8,
    };
    return state;
}

int private_book_store_publish_self_test(void) {
    struct fake_publish_context winner = {0};
    winner.part_descriptor = state_with_ctime(10);
    winner.part_entry = state_with_ctime(10);
    winner.published_descriptor = state_with_ctime(20);
    winner.final_entry = state_with_ctime(20);
    struct private_book_store_publish_result result =
            private_book_store_publish_no_replace(&OPERATIONS, &winner);
    if (result.operation_errno != 0 || result.unlock_errno != 0 || !result.published) return 20;
    if (winner.rename_flags != PRIVATE_BOOK_STORE_RENAME_NOREPLACE) return 21;
    if (winner.sync_count != 1) return 22;

    struct fake_publish_context loser = winner;
    loser.rename_errno = TEST_EEXIST;
    loser.rename_flags = 0;
    loser.sync_count = 0;
    result = private_book_store_publish_no_replace(&OPERATIONS, &loser);
    if (result.operation_errno != TEST_EEXIST || result.published || loser.sync_count != 0) return 23;
    if (loser.rename_flags != PRIVATE_BOOK_STORE_RENAME_NOREPLACE) return 24;

    struct fake_publish_context unsupported = winner;
    unsupported.rename_errno = TEST_ENOSYS;
    unsupported.sync_count = 0;
    result = private_book_store_publish_no_replace(&OPERATIONS, &unsupported);
    if (result.operation_errno != TEST_ENOSYS || result.published || unsupported.sync_count != 0) {
        return 25;
    }

    struct fake_publish_context mutation = winner;
    mutation.part_entry.inode++;
    mutation.rename_flags = 0;
    mutation.sync_count = 0;
    result = private_book_store_publish_no_replace(&OPERATIONS, &mutation);
    if (result.operation_errno != PRIVATE_BOOK_STORE_ESTALE ||
            result.published || mutation.rename_flags != 0) return 26;

    struct fake_publish_context final_mutation = winner;
    final_mutation.final_entry.inode++;
    final_mutation.sync_count = 0;
    result = private_book_store_publish_no_replace(&OPERATIONS, &final_mutation);
    if (result.operation_errno != PRIVATE_BOOK_STORE_EIO ||
            !result.published || final_mutation.sync_count != 0) return 27;

    struct fake_publish_context unlock_failure = winner;
    unlock_failure.unlock_errno = PRIVATE_BOOK_STORE_EIO;
    result = private_book_store_publish_no_replace(&OPERATIONS, &unlock_failure);
    if (result.operation_errno != 0 ||
            result.unlock_errno != PRIVATE_BOOK_STORE_EIO || !result.published) return 28;

    struct fake_publish_context interrupted_lock = winner;
    interrupted_lock.interrupted_lock_count = 2;
    interrupted_lock.lock_count = 0;
    interrupted_lock.sync_count = 0;
    result = private_book_store_publish_no_replace(&OPERATIONS, &interrupted_lock);
    if (result.operation_errno != 0 || result.unlock_errno != 0 || !result.published ||
            interrupted_lock.lock_count != 3 || interrupted_lock.sync_count != 1) return 29;

    struct fake_publish_context failed_lock = winner;
    failed_lock.lock_errno = PRIVATE_BOOK_STORE_EIO;
    failed_lock.lock_count = 0;
    result = private_book_store_publish_no_replace(&OPERATIONS, &failed_lock);
    if (result.operation_errno != PRIVATE_BOOK_STORE_EIO || result.unlock_errno != 0 ||
            result.published || failed_lock.lock_count != 1) return 30;
    return 0;
}
