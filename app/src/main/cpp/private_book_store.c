#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <stdint.h>
#include <sys/stat.h>
#include <unistd.h>

static const char *get_utf(JNIEnv *env, jstring value) {
    return (*env)->GetStringUTFChars(env, value, NULL);
}

JNIEXPORT jint JNICALL
Java_com_air5005_pagenest_library_importing_AndroidPrivateBookStoreNative_openRoot(
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
Java_com_air5005_pagenest_library_importing_AndroidPrivateBookStoreNative_openPart(
        JNIEnv *env, jobject instance, jint root_descriptor, jstring name_value) {
    (void) instance;
    const char *name = get_utf(env, name_value);
    if (name == NULL) return -ENOMEM;
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
    const char *name = get_utf(env, name_value);
    if (name == NULL) return -ENOMEM;
    int descriptor = openat(
            root_descriptor,
            name,
            O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    int saved_errno = errno;
    (*env)->ReleaseStringUTFChars(env, name_value, name);
    return descriptor >= 0 ? descriptor : -saved_errno;
}

JNIEXPORT jint JNICALL
Java_com_air5005_pagenest_library_importing_AndroidPrivateBookStoreNative_link(
        JNIEnv *env,
        jobject instance,
        jint root_descriptor,
        jstring source_value,
        jstring target_value) {
    (void) instance;
    const char *source = get_utf(env, source_value);
    if (source == NULL) return ENOMEM;
    const char *target = get_utf(env, target_value);
    if (target == NULL) {
        (*env)->ReleaseStringUTFChars(env, source_value, source);
        return ENOMEM;
    }
    int result = linkat(root_descriptor, source, root_descriptor, target, 0);
    int saved_errno = errno;
    (*env)->ReleaseStringUTFChars(env, target_value, target);
    (*env)->ReleaseStringUTFChars(env, source_value, source);
    return result == 0 ? 0 : saved_errno;
}

JNIEXPORT jint JNICALL
Java_com_air5005_pagenest_library_importing_AndroidPrivateBookStoreNative_unlink(
        JNIEnv *env, jobject instance, jint root_descriptor, jstring name_value) {
    (void) instance;
    const char *name = get_utf(env, name_value);
    if (name == NULL) return -ENOMEM;
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
        JNIEnv *env, jobject instance, jint root_descriptor, jstring path_value) {
    (void) instance;
    struct stat descriptor_stat;
    if (fstat(root_descriptor, &descriptor_stat) != 0) return -errno;

    const char *path = get_utf(env, path_value);
    if (path == NULL) return -ENOMEM;
    struct stat path_stat;
    int result = lstat(path, &path_stat);
    int saved_errno = errno;
    (*env)->ReleaseStringUTFChars(env, path_value, path);
    if (result != 0) return -saved_errno;

    return S_ISDIR(descriptor_stat.st_mode) &&
                    S_ISDIR(path_stat.st_mode) &&
                    descriptor_stat.st_dev == path_stat.st_dev &&
                    descriptor_stat.st_ino == path_stat.st_ino
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
        jlong inode) {
    (void) instance;
    const char *name = get_utf(env, name_value);
    if (name == NULL) return -ENOMEM;
    struct stat entry_stat;
    int result = fstatat(root_descriptor, name, &entry_stat, AT_SYMLINK_NOFOLLOW);
    int saved_errno = errno;
    (*env)->ReleaseStringUTFChars(env, name_value, name);
    if (result != 0) return -saved_errno;

    return S_ISREG(entry_stat.st_mode) &&
                    entry_stat.st_dev == (dev_t) device &&
                    entry_stat.st_ino == (ino_t) inode
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
