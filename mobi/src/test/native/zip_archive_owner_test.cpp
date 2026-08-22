#include "zip_archive_owner.h"

static int close_count;

static int observable_close(void *archive) {
    if (archive == reinterpret_cast<void *>(1)) {
        close_count++;
    }
    return 0;
}

static int representative_malformed_epub_early_return() {
    zip_archive_owner archive(reinterpret_cast<void *>(1), observable_close);
    if (archive.get() == nullptr) {
        return 1;
    }
    return 0;
}

extern "C" __declspec(dllexport) int run_zip_archive_owner_tests() {
    close_count = 0;
    if (representative_malformed_epub_early_return() != 0) {
        return 1;
    }
    return close_count == 1 ? 0 : 1;
}
