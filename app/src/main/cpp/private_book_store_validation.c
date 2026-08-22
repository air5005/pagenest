#include "private_book_store_validation.h"

int private_book_store_is_valid_basename(const char *value, size_t length) {
    if (value == NULL || length == 0) return 0;
    if (length == 1 && value[0] == '.') return 0;
    if (length == 2 && value[0] == '.' && value[1] == '.') return 0;
    for (size_t index = 0; index < length; ++index) {
        unsigned char current = (unsigned char) value[index];
        if (current == '\0' || current == '/' || current == '\\') return 0;
        if (current == 0xc0 && index + 1 < length &&
                (unsigned char) value[index + 1] == 0x80) {
            return 0;
        }
    }
    return 1;
}
