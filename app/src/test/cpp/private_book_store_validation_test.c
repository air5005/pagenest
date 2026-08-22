#include <stddef.h>

#include "../../main/cpp/private_book_store_validation.h"

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
    return 0;
}

#ifdef _WIN32
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
