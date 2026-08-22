#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <stdint.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#include "private_book_store_publish.h"
#include "private_book_store_validation.h"

#define PUBLISH_LOCK_NAME ".pagenest-publish.lock"
#define PUBLISH_RESULT_PUBLISHED (UINT64_C(1) << 31)

static const char *get_utf(JNIEnv *env, jstring value) {
    return (*env)->GetStringUTFChars(env, value, NULL);
}

static int get_valid_basename(JNIEnv *env, jstring value, const char **result) {
    if (value == NULL) return EINVAL;
    jsize length = (*env)->GetStringUTFLength(env, value);
    const char *name = get_utf(env, value);
    if (name == NULL) return ENOMEM;
    if (!private_book_store_is_valid_basename(name, (size_t) length)) {
        (*env)->ReleaseStringUTFChars(env, value, name);
        return EINVAL;
    }
    *result = name;
    return 0;
}

static int same_regular_state(const struct stat *first, const struct stat *second) {
    return S_ISREG(first->st_mode) && S_ISREG(second->st_mode) &&
            first->st_dev == second->st_dev &&
            first->st_ino == second->st_ino &&
            first->st_size == second->st_size &&
            first->st_mtim.tv_sec == second->st_mtim.tv_sec &&
            first->st_mtim.tv_nsec == second->st_mtim.tv_nsec &&
            first->st_ctim.tv_sec == second->st_ctim.tv_sec &&
            first->st_ctim.tv_nsec == second->st_ctim.tv_nsec;
}

struct native_publish_context {
    int root_descriptor;
    int lock_descriptor;
    int part_descriptor;
    const char *part;
    const char *target;
};

static void normalize_state(
        const struct stat *source,
        struct private_book_store_file_state *target) {
    target->regular = S_ISREG(source->st_mode);
    target->device = (uint64_t) source->st_dev;
    target->inode = (uint64_t) source->st_ino;
    target->size = source->st_size;
    target->modified_seconds = source->st_mtim.tv_sec;
    target->modified_nanoseconds = source->st_mtim.tv_nsec;
    target->changed_seconds = source->st_ctim.tv_sec;
    target->changed_nanoseconds = source->st_ctim.tv_nsec;
}

static int native_publish_lock(void *context) {
    struct native_publish_context *native = context;
    return flock(native->lock_descriptor, LOCK_EX) == 0 ? 0 : errno;
}

static int native_read_part_descriptor(
        void *context,
        struct private_book_store_file_state *state) {
    struct native_publish_context *native = context;
    struct stat value;
    if (fstat(native->part_descriptor, &value) != 0) return errno;
    normalize_state(&value, state);
    return 0;
}

static int native_read_part_entry(
        void *context,
        struct private_book_store_file_state *state) {
    struct native_publish_context *native = context;
    struct stat value;
    if (fstatat(
            native->root_descriptor,
            native->part,
            &value,
            AT_SYMLINK_NOFOLLOW) != 0) {
        return errno;
    }
    normalize_state(&value, state);
    return 0;
}

static int native_rename_no_replace(void *context, unsigned int flags) {
    struct native_publish_context *native = context;
    return syscall(
            __NR_renameat2,
            native->root_descriptor,
            native->part,
            native->root_descriptor,
            native->target,
            flags) == 0
            ? 0
            : errno;
}

static int native_read_final_entry(
        void *context,
        struct private_book_store_file_state *state) {
    struct native_publish_context *native = context;
    struct stat value;
    if (fstatat(
            native->root_descriptor,
            native->target,
            &value,
            AT_SYMLINK_NOFOLLOW) != 0) {
        return errno;
    }
    normalize_state(&value, state);
    return 0;
}

static int native_sync_root(void *context) {
    struct native_publish_context *native = context;
    return fsync(native->root_descriptor) == 0 ? 0 : errno;
}

static int native_publish_unlock(void *context) {
    struct native_publish_context *native = context;
    return flock(native->lock_descriptor, LOCK_UN) == 0 ? 0 : errno;
}

static const struct private_book_store_publish_operations PUBLISH_OPERATIONS = {
        native_publish_lock,
        native_read_part_descriptor,
        native_read_part_entry,
        native_rename_no_replace,
        native_read_part_descriptor,
        native_read_final_entry,
        native_sync_root,
        native_publish_unlock,
};

JNIEXPORT jint JNICALL
Java_com_air5005_pagenest_library_importing_AndroidPrivateBookStoreNative_openTrustedParent(
        JNIEnv *env, jobject instance, jstring path_value) {
    (void) instance;
    const char *path = get_utf(env, path_value);
    if (path == NULL) return -ENOMEM;
    int descriptor = open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    int saved_errno = errno;
    (*env)->ReleaseStringUTFChars(env, path_value, path);
    return descriptor >= 0 ? descriptor : -saved_errno;
}

JNIEXPORT jint JNICALL
Java_com_air5005_pagenest_library_importing_AndroidPrivateBookStoreNative_openOrCreateRoot(
        JNIEnv *env,
        jobject instance,
        jint parent_descriptor,
        jstring name_value) {
    (void) instance;
    const char *name;
    int validation_errno = get_valid_basename(env, name_value, &name);
    if (validation_errno != 0) return -validation_errno;

    int mkdir_result = mkdirat(parent_descriptor, name, S_IRWXU);
    int mkdir_errno = errno;
    if (mkdir_result != 0 && mkdir_errno != EEXIST) {
        (*env)->ReleaseStringUTFChars(env, name_value, name);
        return -mkdir_errno;
    }

    int descriptor = openat(
            parent_descriptor,
            name,
            O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    int open_errno = errno;
    if (descriptor < 0) {
        (*env)->ReleaseStringUTFChars(env, name_value, name);
        return -open_errno;
    }
    (*env)->ReleaseStringUTFChars(env, name_value, name);
    return descriptor;
}

JNIEXPORT jint JNICALL
Java_com_air5005_pagenest_library_importing_AndroidPrivateBookStoreNative_openPublishLock(
        JNIEnv *env, jobject instance, jint root_descriptor) {
    (void) env;
    (void) instance;
    int descriptor = openat(
            root_descriptor,
            PUBLISH_LOCK_NAME,
            O_RDWR | O_CREAT | O_CLOEXEC | O_NOFOLLOW,
            S_IRUSR | S_IWUSR);
    return descriptor >= 0 ? descriptor : -errno;
}

JNIEXPORT jint JNICALL
Java_com_air5005_pagenest_library_importing_AndroidPrivateBookStoreNative_verifyPublishLock(
        JNIEnv *env, jobject instance, jint root_descriptor, jint lock_descriptor) {
    (void) env;
    (void) instance;
    struct stat descriptor_stat;
    struct stat entry_stat;
    if (fstat(lock_descriptor, &descriptor_stat) != 0) return -errno;
    if (fstatat(root_descriptor, PUBLISH_LOCK_NAME, &entry_stat, AT_SYMLINK_NOFOLLOW) != 0) {
        return -errno;
    }
    return same_regular_state(&descriptor_stat, &entry_stat) ? 1 : 0;
}

JNIEXPORT jint JNICALL
Java_com_air5005_pagenest_library_importing_AndroidPrivateBookStoreNative_openPart(
        JNIEnv *env, jobject instance, jint root_descriptor, jstring name_value) {
    (void) instance;
    const char *name;
    int validation_errno = get_valid_basename(env, name_value, &name);
    if (validation_errno != 0) return -validation_errno;
    int descriptor = openat(
            root_descriptor,
            name,
            O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW,
            S_IRUSR | S_IWUSR);
    int saved_errno = errno;
    (*env)->ReleaseStringUTFChars(env, name_value, name);
    return descriptor >= 0 ? descriptor : -saved_errno;
}

JNIEXPORT jint JNICALL
Java_com_air5005_pagenest_library_importing_AndroidPrivateBookStoreNative_openExisting(
        JNIEnv *env, jobject instance, jint root_descriptor, jstring name_value) {
    (void) instance;
    const char *name;
    int validation_errno = get_valid_basename(env, name_value, &name);
    if (validation_errno != 0) return -validation_errno;
    int descriptor = openat(
            root_descriptor,
            name,
            O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    int saved_errno = errno;
    (*env)->ReleaseStringUTFChars(env, name_value, name);
    return descriptor >= 0 ? descriptor : -saved_errno;
}

JNIEXPORT jlong JNICALL
Java_com_air5005_pagenest_library_importing_AndroidPrivateBookStoreNative_publishNoReplace(
        JNIEnv *env,
        jobject instance,
        jint root_descriptor,
        jint lock_descriptor,
        jint part_descriptor,
        jstring part_value,
        jstring target_value) {
    (void) instance;
    const char *part;
    int validation_errno = get_valid_basename(env, part_value, &part);
    if (validation_errno != 0) return (jlong) (uint32_t) validation_errno;
    const char *target;
    validation_errno = get_valid_basename(env, target_value, &target);
    if (validation_errno != 0) {
        (*env)->ReleaseStringUTFChars(env, part_value, part);
        return (jlong) (uint32_t) validation_errno;
    }

    struct native_publish_context context = {
            root_descriptor,
            lock_descriptor,
            part_descriptor,
            part,
            target,
    };
    struct private_book_store_publish_result publish_result =
            private_book_store_publish_no_replace(&PUBLISH_OPERATIONS, &context);
    uint64_t result = (uint32_t) publish_result.operation_errno |
            ((uint64_t) (uint32_t) publish_result.unlock_errno << 32);
    if (publish_result.published) result |= PUBLISH_RESULT_PUBLISHED;
    (*env)->ReleaseStringUTFChars(env, target_value, target);
    (*env)->ReleaseStringUTFChars(env, part_value, part);
    return (jlong) result;
}

JNIEXPORT jint JNICALL
Java_com_air5005_pagenest_library_importing_AndroidPrivateBookStoreNative_unlink(
        JNIEnv *env, jobject instance, jint root_descriptor, jstring name_value) {
    (void) instance;
    const char *name;
    int validation_errno = get_valid_basename(env, name_value, &name);
    if (validation_errno != 0) return -validation_errno;
    int result = unlinkat(root_descriptor, name, 0);
    int saved_errno = errno;
    (*env)->ReleaseStringUTFChars(env, name_value, name);
    if (result == 0) return 1;
    if (saved_errno == ENOENT) return 0;
    return -saved_errno;
}

JNIEXPORT jint JNICALL
Java_com_air5005_pagenest_library_importing_AndroidPrivateBookStoreNative_sync(
        JNIEnv *env, jobject instance, jint root_descriptor) {
    (void) env;
    (void) instance;
    return fsync(root_descriptor) == 0 ? 0 : errno;
}

JNIEXPORT jint JNICALL
Java_com_air5005_pagenest_library_importing_AndroidPrivateBookStoreNative_verifyRoot(
        JNIEnv *env,
        jobject instance,
        jint parent_descriptor,
        jint root_descriptor,
        jstring name_value) {
    (void) instance;
    struct stat descriptor_stat;
    if (fstat(root_descriptor, &descriptor_stat) != 0) return -errno;

    const char *name;
    int validation_errno = get_valid_basename(env, name_value, &name);
    if (validation_errno != 0) return -validation_errno;
    struct stat entry_stat;
    int result = fstatat(parent_descriptor, name, &entry_stat, AT_SYMLINK_NOFOLLOW);
    int saved_errno = errno;
    (*env)->ReleaseStringUTFChars(env, name_value, name);
    if (result != 0) return -saved_errno;

    return S_ISDIR(descriptor_stat.st_mode) &&
                    S_ISDIR(entry_stat.st_mode) &&
                    descriptor_stat.st_dev == entry_stat.st_dev &&
                    descriptor_stat.st_ino == entry_stat.st_ino
            ? 1
            : 0;
}

JNIEXPORT jint JNICALL
Java_com_air5005_pagenest_library_importing_AndroidPrivateBookStoreNative_verifyEntry(
        JNIEnv *env,
        jobject instance,
        jint root_descriptor,
        jstring name_value,
        jlong device,
        jlong inode,
        jlong size,
        jlong modified_seconds,
        jlong modified_nanoseconds,
        jlong changed_seconds,
        jlong changed_nanoseconds) {
    (void) instance;
    const char *name;
    int validation_errno = get_valid_basename(env, name_value, &name);
    if (validation_errno != 0) return -validation_errno;
    struct stat entry_stat;
    int result = fstatat(root_descriptor, name, &entry_stat, AT_SYMLINK_NOFOLLOW);
    int saved_errno = errno;
    (*env)->ReleaseStringUTFChars(env, name_value, name);
    if (result != 0) return -saved_errno;

    return S_ISREG(entry_stat.st_mode) &&
                    entry_stat.st_dev == (dev_t) device &&
                    entry_stat.st_ino == (ino_t) inode &&
                    entry_stat.st_size == (off_t) size &&
                    entry_stat.st_mtim.tv_sec == (time_t) modified_seconds &&
                    entry_stat.st_mtim.tv_nsec == modified_nanoseconds &&
                    entry_stat.st_ctim.tv_sec == (time_t) changed_seconds &&
                    entry_stat.st_ctim.tv_nsec == changed_nanoseconds
            ? 1
            : 0;
}

JNIEXPORT jint JNICALL
Java_com_air5005_pagenest_library_importing_AndroidPrivateBookStoreNative_closeDescriptor(
        JNIEnv *env, jobject instance, jint descriptor) {
    (void) env;
    (void) instance;
    return close(descriptor) == 0 ? 0 : errno;
}
