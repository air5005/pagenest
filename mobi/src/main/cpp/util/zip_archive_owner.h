#ifndef PAGENEST_ZIP_ARCHIVE_OWNER_H
#define PAGENEST_ZIP_ARCHIVE_OWNER_H

class zip_archive_owner {
public:
    using close_function = int (*)(void *);

    explicit zip_archive_owner(void *archive, close_function close_archive)
        : archive_(archive), close_archive_(close_archive) {
    }

    ~zip_archive_owner() {
        if (archive_ != nullptr && close_archive_ != nullptr) {
            close_archive_(archive_);
        }
    }

    zip_archive_owner(const zip_archive_owner &) = delete;
    zip_archive_owner &operator=(const zip_archive_owner &) = delete;

    void *get() const {
        return archive_;
    }

private:
    void *archive_;
    close_function close_archive_;
};

#endif
