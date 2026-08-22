cmake_minimum_required(VERSION 3.22.1)

project(pagenest_storage LANGUAGES C)

add_library(pagenest_storage SHARED private_book_store.c)
target_compile_options(pagenest_storage PRIVATE -Wall -Wextra -Werror)
