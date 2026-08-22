#ifndef PAGENEST_ZIP_ARCHIVE_RUNNER_H
#define PAGENEST_ZIP_ARCHIVE_RUNNER_H

#include "zip_archive_owner.h"

using zip_archive_open_function = void *(*)(const char *);

template<typename Body>
int with_zip_archive(
        const char *path,
        zip_archive_open_function open_archive,
        zip_archive_owner::close_function close_archive,
        Body body) {
    if (path == nullptr || open_archive == nullptr || close_archive == nullptr) {
        return 0;
    }
    zip_archive_owner archive(open_archive(path), close_archive);
    if (archive.get() == nullptr) {
        return 0;
    }
    return body(archive.get());
}

#endif
