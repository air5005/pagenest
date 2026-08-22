#ifndef PAGENEST_EPUB_LOAD_TEST_SEAM_H
#define PAGENEST_EPUB_LOAD_TEST_SEAM_H

#ifdef EPUB_LOAD_TESTING

using epub_load_open_function = void *(*)(const char *);
using epub_load_close_function = int (*)(void *);

void epub_load_set_archive_functions_for_testing(
        epub_load_open_function open_archive,
        epub_load_close_function close_archive);

void epub_load_reset_archive_functions_for_testing();

#endif

#endif
