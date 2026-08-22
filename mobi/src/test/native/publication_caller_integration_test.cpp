#include "bounded_zip_reader.h"
#include "epub_cover_selection.h"
#include "mobi_cover_selection.h"
#include "publication_cover.h"
#include "zip_archive_runner.h"

#include <dirent.h>
#include <ftw.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

extern "C" {
#include "mobi.h"
#include "zip.h"
}

namespace {

constexpr unsigned char kDecodedPng[] = {
        0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x04, 0x00, 0x00, 0x00, 0xb5, 0x1c, 0x0c,
        0x02, 0x00, 0x00, 0x00, 0x0b, 0x49, 0x44, 0x41,
        0x54, 0x78, 0xda, 0x63, 0x64, 0xf8, 0x0f, 0x00,
        0x01, 0x05, 0x01, 0x01, 0x27, 0x18, 0xe3, 0x66,
        0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4e, 0x44,
        0xae, 0x42, 0x60, 0x82
};

constexpr char kContainerXml[] =
        "<?xml version=\"1.0\"?>"
        "<container><rootfiles><rootfile full-path=\"OPS/package.opf\"/>"
        "</rootfiles></container>";

std::string opf_xml(const char *media_type) {
    return std::string("<?xml version=\"1.0\"?>") +
            "<package><metadata><dc:title>../../../untrusted-title</dc:title>"
            "<meta name=\"cover\" content=\"cover-id\"/></metadata><manifest>"
            "<item id=\"chapter\" href=\"text/chapter.xhtml\" "
            "media-type=\"application/xhtml+xml\"/>"
            "<item id=\"cover-id\" href=\"images/../images/cover.png\" media-type=\"" +
            media_type + "\"/></manifest></package>";
}

bool add_zip_entry(
        zipFile archive,
        const char *name,
        const unsigned char *data,
        size_t size) {
    if (zipOpenNewFileInZip(
            archive,
            name,
            nullptr,
            nullptr,
            0,
            nullptr,
            0,
            nullptr,
            Z_DEFLATED,
            Z_BEST_COMPRESSION) != ZIP_OK) {
        return false;
    }
    bool success = size == 0 ||
            zipWriteInFileInZip(archive, data, static_cast<unsigned>(size)) == ZIP_OK;
    return zipCloseFileInZip(archive) == ZIP_OK && success;
}

bool add_repeated_zip_entry(zipFile archive, const char *name, size_t size) {
    unsigned char zeros[8192] = {0};
    if (zipOpenNewFileInZip(
            archive,
            name,
            nullptr,
            nullptr,
            0,
            nullptr,
            0,
            nullptr,
            Z_DEFLATED,
            Z_BEST_COMPRESSION) != ZIP_OK) {
        return false;
    }
    while (size > 0) {
        size_t chunk = size > sizeof(zeros) ? sizeof(zeros) : size;
        if (zipWriteInFileInZip(archive, zeros, static_cast<unsigned>(chunk)) != ZIP_OK) {
            zipCloseFileInZip(archive);
            return false;
        }
        size -= chunk;
    }
    return zipCloseFileInZip(archive) == ZIP_OK;
}

bool write_epub(
        const char *path,
        const unsigned char *cover,
        size_t cover_size,
        size_t repeated_cover_size,
        const char *media_type) {
    zipFile archive = zipOpen(path, APPEND_STATUS_CREATE);
    std::string package = opf_xml(media_type);
    if (archive == nullptr) {
        return false;
    }
    bool success = repeated_cover_size > 0
            ? add_repeated_zip_entry(archive, "OPS/images/cover.png", repeated_cover_size)
            : add_zip_entry(archive, "OPS/images/cover.png", cover, cover_size);
    success = success && add_zip_entry(
            archive,
            "META-INF/container.xml",
            reinterpret_cast<const unsigned char *>(kContainerXml),
            sizeof(kContainerXml) - 1);
    success = success && add_zip_entry(
            archive,
            "OPS/package.opf",
            reinterpret_cast<const unsigned char *>(package.data()),
            package.size());
    return zipClose(archive, nullptr) == ZIP_OK && success;
}

long find_signature(FILE *file, uint32_t signature) {
    unsigned char window[4] = {0};
    long offset = 0;
    int value;
    while ((value = fgetc(file)) != EOF) {
        window[offset % 4] = static_cast<unsigned char>(value);
        if (offset >= 3) {
            uint32_t found = static_cast<uint32_t>(window[(offset - 3) % 4]) |
                    (static_cast<uint32_t>(window[(offset - 2) % 4]) << 8) |
                    (static_cast<uint32_t>(window[(offset - 1) % 4]) << 16) |
                    (static_cast<uint32_t>(window[offset % 4]) << 24);
            if (found == signature) {
                return offset - 3;
            }
        }
        offset++;
    }
    return -1;
}

bool patch_first_central_field(const char *path, long field_offset, uint32_t value) {
    FILE *file = fopen(path, "r+b");
    unsigned char bytes[4] = {
            static_cast<unsigned char>(value),
            static_cast<unsigned char>(value >> 8),
            static_cast<unsigned char>(value >> 16),
            static_cast<unsigned char>(value >> 24)
    };
    if (file == nullptr) {
        return false;
    }
    long central = find_signature(file, 0x02014b50U);
    bool success = central >= 0 && fseek(file, central + field_offset, SEEK_SET) == 0 &&
            fwrite(bytes, 1, sizeof(bytes), file) == sizeof(bytes);
    return fclose(file) == 0 && success;
}

uint32_t read_be32(const unsigned char *value) {
    return (static_cast<uint32_t>(value[0]) << 24) |
            (static_cast<uint32_t>(value[1]) << 16) |
            (static_cast<uint32_t>(value[2]) << 8) |
            static_cast<uint32_t>(value[3]);
}

bool decode_png_independently(const char *path) {
    FILE *file = fopen(path, "rb");
    std::vector<unsigned char> bytes;
    unsigned char buffer[4096];
    size_t count;
    if (file == nullptr) {
        return false;
    }
    while ((count = fread(buffer, 1, sizeof(buffer), file)) > 0) {
        bytes.insert(bytes.end(), buffer, buffer + count);
    }
    if (fclose(file) != 0 || bytes.size() < 8 ||
            memcmp(bytes.data(), kDecodedPng, 8) != 0) {
        return false;
    }
    size_t offset = 8;
    bool saw_header = false;
    bool saw_end = false;
    std::vector<unsigned char> compressed;
    while (offset + 12 <= bytes.size()) {
        uint32_t length = read_be32(bytes.data() + offset);
        if (length > bytes.size() - offset - 12) {
            return false;
        }
        const unsigned char *type = bytes.data() + offset + 4;
        const unsigned char *data = bytes.data() + offset + 8;
        uint32_t expected_crc = read_be32(data + length);
        uLong crc = crc32(0, type, 4);
        crc = crc32(crc, data, length);
        if (crc != expected_crc) {
            return false;
        }
        if (memcmp(type, "IHDR", 4) == 0) {
            saw_header = length == 13 && read_be32(data) == 1 && read_be32(data + 4) == 1 &&
                    data[8] == 8 && data[9] == 4;
        } else if (memcmp(type, "IDAT", 4) == 0) {
            compressed.insert(compressed.end(), data, data + length);
        } else if (memcmp(type, "IEND", 4) == 0) {
            saw_end = length == 0;
            offset += 12;
            break;
        }
        offset += 12 + length;
    }
    unsigned char decoded[3] = {0};
    uLongf decoded_size = sizeof(decoded);
    return saw_header && saw_end && offset == bytes.size() && !compressed.empty() &&
            uncompress(decoded, &decoded_size, compressed.data(), compressed.size()) == Z_OK &&
            decoded_size == sizeof(decoded) && decoded[0] <= 4;
}

int count_entries(const char *path) {
    DIR *directory = opendir(path);
    int result = 0;
    if (directory == nullptr) {
        return 0;
    }
    while (dirent *entry = readdir(directory)) {
        if (strcmp(entry->d_name, ".") != 0 && strcmp(entry->d_name, "..") != 0) {
            result++;
        }
    }
    closedir(directory);
    return result;
}

bool make_directory(const char *root, const char *name, char *output, size_t capacity) {
    return snprintf(output, capacity, "%s/%s", root, name) > 0 && mkdir(output, 0700) == 0;
}

bool extract_epub(const char *path, const char *files, char *output, size_t capacity) {
    unzFile archive = unzOpen(path);
    if (archive == nullptr) {
        return false;
    }
    int result = epub_cover_extract_from_archive(archive, files, output, capacity);
    return unzClose(archive) == UNZ_OK && result == 1;
}

bool legal_epub_uses_container_manifest_and_decodes(const char *root) {
    char epub[512];
    char files[512];
    char output[1024] = {0};
    snprintf(epub, sizeof(epub), "%s/legal.epub", root);
    return make_directory(root, "epub-files", files, sizeof(files)) &&
            write_epub(epub, kDecodedPng, sizeof(kDecodedPng), 0, "image/png") &&
            extract_epub(epub, files, output, sizeof(output)) &&
            strstr(output, "untrusted-title") == nullptr &&
            decode_png_independently(output);
}

bool invalid_epub_leaves_no_cover(
        const char *root,
        const char *stem,
        size_t repeated_size,
        long patch_offset,
        uint32_t patch_value,
        const char *media_type) {
    char epub[512];
    char files[512];
    char covers[1024];
    char output[1024] = "not-cleared";
    snprintf(epub, sizeof(epub), "%s/%s.epub", root, stem);
    std::string directory_name = std::string(stem) + "-files";
    if (!make_directory(root, directory_name.c_str(), files, sizeof(files)) ||
            !write_epub(epub, kDecodedPng, sizeof(kDecodedPng), repeated_size, media_type) ||
            (patch_offset >= 0 && !patch_first_central_field(epub, patch_offset, patch_value))) {
        return false;
    }
    snprintf(covers, sizeof(covers), "%s/covers", files);
    return !extract_epub(epub, files, output, sizeof(output)) && output[0] == '\0' &&
            count_entries(covers) == 0;
}

bool build_mobi_fixture(
        const char *seed,
        const char *path,
        const unsigned char *cover,
        size_t cover_size) {
    MOBIData *data = mobi_init();
    MOBI_RET load_result = data == nullptr ? MOBI_INIT_FAILED : mobi_load_filename(data, seed);
    if (data == nullptr || load_result != MOBI_SUCCESS) {
        fprintf(stderr, "fixture seed load failed: %d\n", static_cast<int>(load_result));
        mobi_free(data);
        return false;
    }
    // The committed clear KF8 seed has a literal cover record at sequence 38.
    MOBIPdbRecord *cover_record = mobi_get_record_by_seqnumber(data, 38);
    unsigned char *replacement = static_cast<unsigned char *>(malloc(cover_size));
    if (cover_record == nullptr || replacement == nullptr) {
        fprintf(stderr, "fixture literal record 38 unavailable\n");
        free(replacement);
        mobi_free(data);
        return false;
    }
    memcpy(replacement, cover, cover_size);
    free(cover_record->data);
    cover_record->data = replacement;
    cover_record->size = cover_size;
    FILE *file = fopen(path, "wb");
    MOBI_RET write_result = file == nullptr ? MOBI_WRITE_FAILED : mobi_write_file(file, data);
    int close_result = file == nullptr ? EOF : fclose(file);
    bool success = file != nullptr && write_result == MOBI_SUCCESS && close_result == 0;
    if (!success) {
        fprintf(stderr, "fixture write failed: %d\n", static_cast<int>(write_result));
    }
    mobi_free(data);
    return success;
}

bool extract_mobi(const char *path, const char *files, char *output, size_t capacity) {
    MOBIData *data = mobi_init();
    MOBI_RET load_result = data == nullptr ? MOBI_INIT_FAILED : mobi_load_filename(data, path);
    if (data == nullptr || load_result != MOBI_SUCCESS) {
        fprintf(stderr, "generated fixture reload failed: %d\n", static_cast<int>(load_result));
        mobi_free(data);
        return false;
    }
    int result = mobi_cover_extract_from_data(data, files, output, capacity);
    mobi_free(data);
    return result == 1;
}

bool legal_mobi_uses_exth_offset_and_decodes(const char *root, const char *seed) {
    char mobi[512];
    char files[512];
    char output[1024] = {0};
    snprintf(mobi, sizeof(mobi), "%s/legal.azw3", root);
    return make_directory(root, "mobi-files", files, sizeof(files)) &&
            build_mobi_fixture(seed, mobi, kDecodedPng, sizeof(kDecodedPng)) &&
            extract_mobi(mobi, files, output, sizeof(output)) &&
            decode_png_independently(output);
}

bool invalid_mobi_leaves_no_cover(
        const char *root,
        const char *seed,
        const char *stem,
        const unsigned char *cover,
        size_t cover_size) {
    char mobi[512];
    char files[512];
    char covers[1024];
    char output[1024] = "not-cleared";
    snprintf(mobi, sizeof(mobi), "%s/%s.azw3", root, stem);
    std::string directory_name = std::string(stem) + "-files";
    if (!make_directory(root, directory_name.c_str(), files, sizeof(files)) ||
            !build_mobi_fixture(seed, mobi, cover, cover_size)) {
        return false;
    }
    snprintf(covers, sizeof(covers), "%s/covers", files);
    return !extract_mobi(mobi, files, output, sizeof(output)) && output[0] == '\0' &&
            count_entries(covers) == 0;
}

int tracked_closes = 0;

void *tracked_open(const char *path) {
    return unzOpen(path);
}

int tracked_close(void *archive) {
    tracked_closes++;
    return unzClose(archive);
}

bool malformed_production_archive_path_closes_once(const char *root) {
    char epub[512];
    char files[512];
    char output[1024] = "not-cleared";
    snprintf(epub, sizeof(epub), "%s/malformed.epub", root);
    static const unsigned char malformed[] = "not a container";
    if (!make_directory(root, "malformed-files", files, sizeof(files))) {
        return false;
    }
    zipFile archive = zipOpen(epub, APPEND_STATUS_CREATE);
    if (archive == nullptr ||
            !add_zip_entry(archive, "wrong.xml", malformed, sizeof(malformed) - 1) ||
            zipClose(archive, nullptr) != ZIP_OK) {
        return false;
    }
    tracked_closes = 0;
    int result = with_zip_archive(
            epub,
            tracked_open,
            tracked_close,
            [&](void *opened) {
                return epub_cover_extract_from_archive(
                        opened,
                        files,
                        output,
                        sizeof(output));
            });
    return result == 0 && output[0] == '\0' && tracked_closes == 1;
}

int remove_tree_entry(const char *path, const struct stat *, int, struct FTW *) {
    return remove(path);
}

}  // namespace

int main(int argc, char **argv) {
    if (argc != 2) {
        fprintf(stderr, "expected clear KF8 seed path\n");
        return 1;
    }
    char root[] = "/tmp/pagenest-caller-XXXXXX";
    if (mkdtemp(root) == nullptr) {
        return 1;
    }
    int failures = 0;
#define RUN(fixture) do { if (!(fixture)) { fprintf(stderr, "%s failed\n", #fixture); failures++; } } while (0)
    RUN(legal_epub_uses_container_manifest_and_decodes(root));
    RUN(invalid_epub_leaves_no_cover(
            root,
            "epub-oversized",
            PUBLICATION_COVER_MAX_BYTES + 1,
            -1,
            0,
            "image/png"));
    RUN(invalid_epub_leaves_no_cover(root, "epub-crc", 0, 16, 0, "image/png"));
    RUN(invalid_epub_leaves_no_cover(root, "epub-partial", 0, 24, 100, "image/png"));
    RUN(invalid_epub_leaves_no_cover(root, "epub-mime", 0, -1, 0, "image/jpeg"));
    RUN(legal_mobi_uses_exth_offset_and_decodes(root, argv[1]));
    std::vector<unsigned char> oversized(PUBLICATION_COVER_MAX_BYTES + 1, 0);
    memcpy(oversized.data(), kDecodedPng, sizeof(kDecodedPng));
    RUN(invalid_mobi_leaves_no_cover(
            root,
            argv[1],
            "mobi-oversized",
            oversized.data(),
            oversized.size()));
    unsigned char corrupt_png[sizeof(kDecodedPng)];
    memcpy(corrupt_png, kDecodedPng, sizeof(kDecodedPng));
    corrupt_png[0] = 0;
    RUN(invalid_mobi_leaves_no_cover(
            root,
            argv[1],
            "mobi-corrupt",
            corrupt_png,
            sizeof(corrupt_png)));
    RUN(malformed_production_archive_path_closes_once(root));
#undef RUN
    if (nftw(root, remove_tree_entry, 16, FTW_DEPTH | FTW_PHYS) != 0) {
        failures++;
    }
    return failures;
}
