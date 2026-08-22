#include "epub_cover_selection.h"

#include <algorithm>
#include <cstdlib>
#include <filesystem>
#include <sstream>
#include <string>

#include "bounded_zip_reader.h"
#include "publication_cover.h"
#include "tinyxml2.h"

namespace {

namespace fs = std::filesystem;

constexpr size_t kEpubMetadataMaxBytes = 4 * 1024 * 1024;

std::string attribute(const tinyxml2::XMLElement *element, const char *name) {
    if (element == nullptr) {
        return "";
    }
    const char *value = element->Attribute(name);
    return value == nullptr ? "" : value;
}

bool contains_token(const std::string &tokens, const std::string &expected) {
    std::istringstream stream(tokens);
    std::string token;
    while (stream >> token) {
        if (token == expected) {
            return true;
        }
    }
    return false;
}

std::string resolve_entry(const std::string &package_path, const std::string &relative_path) {
    if (relative_path.empty() || relative_path[0] == '/' ||
            relative_path.find('\\') != std::string::npos ||
            relative_path.find('?') != std::string::npos ||
            relative_path.find('#') != std::string::npos ||
            relative_path.find(':') != std::string::npos) {
        return "";
    }
    fs::path resolved = (fs::path(package_path).parent_path() / relative_path).lexically_normal();
    std::string value = resolved.generic_string();
    if (resolved.is_absolute() || value.empty() || value == ".." || value.rfind("../", 0) == 0) {
        return "";
    }
    return value;
}

const char *extension_for_media_type(const std::string &media_type) {
    if (media_type == "image/jpeg" || media_type == "image/jpg") {
        return "jpg";
    }
    if (media_type == "image/png") {
        return "png";
    }
    if (media_type == "image/gif") {
        return "gif";
    }
    if (media_type == "image/bmp") {
        return "bmp";
    }
    if (media_type == "image/webp") {
        return "webp";
    }
    return nullptr;
}

bool read_entry(unzFile archive, const char *name, std::string &output) {
    unsigned char *data = nullptr;
    size_t size = 0;
    output.clear();
    if (!bounded_zip_read_file(
            archive,
            name,
            kEpubMetadataMaxBytes,
            &data,
            &size)) {
        return false;
    }
    if (size > 0) {
        output.assign(reinterpret_cast<const char *>(data), size);
    }
    free(data);
    return true;
}

size_t count_entries(unzFile archive, const std::string &expected) {
    size_t matches = 0;
    int status = unzGoToFirstFile(archive);
    while (status == UNZ_OK) {
        unz_file_info info;
        char name[4096] = {0};
        if (unzGetCurrentFileInfo(
                archive,
                &info,
                name,
                sizeof(name),
                nullptr,
                0,
                nullptr,
                0) != UNZ_OK) {
            return 0;
        }
        if (expected == name) {
            matches++;
        }
        status = unzGoToNextFile(archive);
    }
    return status == UNZ_END_OF_LIST_OF_FILE ? matches : 0;
}

const tinyxml2::XMLElement *find_cover_item(
        const tinyxml2::XMLElement *metadata,
        const tinyxml2::XMLElement *manifest) {
    std::string cover_id;
    for (const tinyxml2::XMLElement *meta = metadata == nullptr
            ? nullptr
            : metadata->FirstChildElement("meta");
            meta != nullptr;
            meta = meta->NextSiblingElement("meta")) {
        if (attribute(meta, "name") == "cover") {
            cover_id = attribute(meta, "content");
            break;
        }
    }
    const tinyxml2::XMLElement *selected = nullptr;
    size_t matches = 0;
    for (const tinyxml2::XMLElement *item = manifest == nullptr
            ? nullptr
            : manifest->FirstChildElement("item");
            item != nullptr;
            item = item->NextSiblingElement("item")) {
        if ((!cover_id.empty() && attribute(item, "id") == cover_id) ||
                contains_token(attribute(item, "properties"), "cover-image")) {
            selected = item;
            matches++;
        }
    }
    return matches == 1 ? selected : nullptr;
}

}  // namespace

int epub_cover_extract_from_package(
        unzFile archive,
        const char *package_path,
        const char *package_data,
        size_t package_size,
        const char *files_directory,
        char *output_path,
        size_t output_capacity) {
    if (output_path != nullptr && output_capacity > 0) {
        output_path[0] = '\0';
    }
    if (archive == nullptr || package_path == nullptr || package_path[0] == '\0' ||
            package_data == nullptr || package_size == 0 || files_directory == nullptr ||
            output_path == nullptr || output_capacity == 0) {
        return 0;
    }
    tinyxml2::XMLDocument package_document;
    if (package_document.Parse(package_data, package_size) != tinyxml2::XML_SUCCESS) {
        return 0;
    }
    const tinyxml2::XMLElement *package_root = package_document.RootElement();
    const tinyxml2::XMLElement *metadata = package_root == nullptr
            ? nullptr
            : package_root->FirstChildElement("metadata");
    const tinyxml2::XMLElement *manifest = package_root == nullptr
            ? nullptr
            : package_root->FirstChildElement("manifest");
    const tinyxml2::XMLElement *cover_item = find_cover_item(metadata, manifest);
    if (cover_item == nullptr) {
        return 0;
    }
    const char *extension = extension_for_media_type(attribute(cover_item, "media-type"));
    std::string cover_entry = resolve_entry(package_path, attribute(cover_item, "href"));
    if (extension == nullptr || cover_entry.empty() || count_entries(archive, cover_entry) != 1) {
        return 0;
    }
    return publication_cover_write_epub_entry(
            archive,
            cover_entry.c_str(),
            extension,
            files_directory,
            output_path,
            output_capacity);
}

int epub_cover_extract_from_archive(
        unzFile archive,
        const char *files_directory,
        char *output_path,
        size_t output_capacity) {
    if (output_path != nullptr && output_capacity > 0) {
        output_path[0] = '\0';
    }
    if (archive == nullptr || files_directory == nullptr || output_path == nullptr ||
            output_capacity == 0) {
        return 0;
    }
    std::string container;
    if (!read_entry(archive, "META-INF/container.xml", container)) {
        return 0;
    }
    tinyxml2::XMLDocument container_document;
    if (container_document.Parse(container.data(), container.size()) != tinyxml2::XML_SUCCESS) {
        return 0;
    }
    const tinyxml2::XMLElement *container_root = container_document.RootElement();
    const tinyxml2::XMLElement *rootfiles = container_root == nullptr
            ? nullptr
            : container_root->FirstChildElement("rootfiles");
    const tinyxml2::XMLElement *rootfile = rootfiles == nullptr
            ? nullptr
            : rootfiles->FirstChildElement("rootfile");
    std::string package_path = attribute(rootfile, "full-path");
    if (package_path.empty() || count_entries(archive, package_path) != 1) {
        return 0;
    }
    std::string package;
    if (!read_entry(archive, package_path.c_str(), package)) {
        return 0;
    }
    return epub_cover_extract_from_package(
            archive,
            package_path.c_str(),
            package.data(),
            package.size(),
            files_directory,
            output_path,
            output_capacity);
}
