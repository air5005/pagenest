#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <sys/stat.h>
#include <unistd.h>

#include "private_book_store_validation.h"

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
    int created = mkdir_result == 0;
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
        if (created) {
            (void) unlinkat(parent_descriptor, name, AT_REMOVEDIR);
            (void) fsync(parent_descriptor);
        }
        (*env)->ReleaseStringUTFChars(env, name_value, name);
        return -open_errno;
    }

    struct stat descriptor_stat;
    struct stat entry_stat;
    int stat_result = fstat(descriptor, &descriptor_stat);
    int stat_errno = errno;
    if (stat_result == 0) {
        stat_result = fstatat(parent_descriptor, name, &entry_stat, AT_SYMLINK_NOFOLLOW);
        stat_errno = errno;
    }
    if (stat_result == 0 &&
            (!S_ISDIR(descriptor_stat.st_mode) || !S_ISDIR(entry_stat.st_mode) ||
             descriptor_stat.st_dev != entry_stat.st_dev ||
             descriptor_stat.st_ino != entry_stat.st_ino)) {
        stat_result = -1;
        stat_errno = ENOTDIR;
    }
    if (stat_result == 0 && fsync(parent_descriptor) != 0) {
        stat_result = -1;
        stat_errno = errno;
    }
    if (stat_result != 0) {
        (void) close(descriptor);
        if (created) {
            (void) unlinkat(parent_descriptor, name, AT_REMOVEDIR);
            (void) fsync(parent_descriptor);
        }
        (*env)->ReleaseStringUTFChars(env, name_value, name);
        return -stat_errno;
    }
    (*env)->ReleaseStringUTFChars(env, name_value, name);
    return descriptor;
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

JNIEXPORT jint JNICALL
Java_com_air5005_pagenest_library_importing_AndroidPrivateBookStoreNative_linkOpenedFile(
        JNIEnv *env,
        jobject instance,
        jint part_descriptor,
        jint root_descriptor,
        jstring target_value) {
    (void) instance;
    const char *target;
    int validation_errno = get_valid_basename(env, target_value, &target);
    if (validation_errno != 0) return validation_errno;

    int result = linkat(part_descriptor, "", root_descriptor, target, AT_EMPTY_PATH);
    int saved_errno = errno;
    if (result != 0 &&
            (saved_errno == EPERM || saved_errno == EACCES || saved_errno == EINVAL ||
             saved_errno == ENOENT ||
             saved_errno == EOPNOTSUPP || saved_errno == ENOSYS)) {
        char descriptor_path[64];
        int length = snprintf(
                descriptor_path,
                sizeof(descriptor_path),
                "/proc/self/fd/%d",
                part_descriptor);
        if (length <= 0 || (size_t) length >= sizeof(descriptor_path)) {
            saved_errno = ENAMETOOLONG;
        } else {
            result = linkat(
                    AT_FDCWD,
                    descriptor_path,
                    root_descriptor,
                    target,
                    AT_SYMLINK_FOLLOW);
            saved_errno = errno;
        }
    }
    (*env)->ReleaseStringUTFChars(env, target_value, target);
    return result == 0 ? 0 : saved_errno;
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
