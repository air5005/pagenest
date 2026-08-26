#include "string_ext.h"

#include <iostream>
#include <string>

namespace {

int expect_count(const std::string &value, size_t expected, const char *name) {
    const size_t actual = string_ext::utf8Count(value);
    if (actual == expected) {
        return 0;
    }
    std::cerr << name << ": expected " << expected << ", got " << actual << '\n';
    return 1;
}

}  // namespace

int main() {
    int failures = 0;
    failures += expect_count("PageNest", 8, "ASCII text");
    failures += expect_count("\xE7\xA8\x8B\xE5\xBA\x8F\xE5\x91\x98", 3, "Chinese text");
    failures += expect_count(std::string("A\xFF" "B", 3), 3, "invalid UTF-8 byte");
    failures += expect_count(std::string(32 * 1024, 'x'), 32 * 1024, "long text");

    if (failures != 0) {
        return 1;
    }
    std::cout << "4 UTF-8 count fixtures passed\n";
    return 0;
}
