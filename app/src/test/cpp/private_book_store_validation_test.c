#include <stddef.h>

#include "../../main/cpp/private_book_store_validation.h"

int private_book_store_publish_self_test(void);

int main(void) {
    static const char embedded_nul[] = {'n', 'u', 'l', '\0', 'x'};
    static const char modified_utf_nul[] = {'n', 'u', 'l', (char) 0xc0, (char) 0x80, 'x'};

    if (!private_book_store_is_valid_basename("book.part", 9)) return 1;
    if (private_book_store_is_valid_basename("", 0)) return 2;
    if (private_book_store_is_valid_basename(".", 1)) return 3;
    if (private_book_store_is_valid_basename("..", 2)) return 4;
    if (private_book_store_is_valid_basename("nested/book", 11)) return 5;
    if (private_book_store_is_valid_basename("nested\\book", 11)) return 6;
    if (private_book_store_is_valid_basename(embedded_nul, sizeof(embedded_nul))) return 7;
    if (private_book_store_is_valid_basename(modified_utf_nul, sizeof(modified_utf_nul))) return 8;
    return private_book_store_publish_self_test();
}

#ifdef _WIN32
void *memcpy(void *target, const void *source, size_t length) {
    unsigned char *output = target;
    const unsigned char *input = source;
    for (size_t index = 0; index < length; ++index) output[index] = input[index];
    return target;
}

void *memset(void *target, int value, size_t length) {
    unsigned char *output = target;
    for (size_t index = 0; index < length; ++index) output[index] = (unsigned char) value;
    return target;
}

void __main(void) {}

__declspec(dllexport) int private_book_store_validation_self_test(void) {
    return main();
}

int DllMainCRTStartup(void *module, unsigned long reason, void *reserved) {
    (void) module;
    (void) reason;
    (void) reserved;
    return 1;
}
#endif
