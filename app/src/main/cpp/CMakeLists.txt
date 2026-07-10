cmake_minimum_required(VERSION 3.22.1)
project(furigana_zinnia LANGUAGES CXX)

add_library(
    furigana_zinnia
    SHARED
    native_zinnia.cpp
    zinnia/character.cpp
    zinnia/feature.cpp
    zinnia/recognizer.cpp
    zinnia/sexp.cpp
)

target_include_directories(furigana_zinnia PRIVATE zinnia)
target_compile_definitions(
    furigana_zinnia
    PRIVATE
    HAVE_SYS_TYPES_H
    HAVE_SYS_STAT_H
    HAVE_FCNTL_H
    HAVE_STRING_H
    HAVE_SYS_MMAN_H
    HAVE_UNISTD_H
    HAVE_MMAP
    WORDS_LITENDIAN
)
target_compile_options(
    furigana_zinnia
    PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
    "-ffile-prefix-map=${CMAKE_SOURCE_DIR}=."
    "-fdebug-prefix-map=${CMAKE_SOURCE_DIR}=."
)
target_link_options(
    furigana_zinnia
    PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)

find_library(log-lib log)
target_link_libraries(furigana_zinnia ${log-lib})
