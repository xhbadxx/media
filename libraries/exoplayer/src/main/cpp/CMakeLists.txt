cmake_minimum_required(VERSION 3.18)
project(fplaydrm)

set(CMAKE_CXX_STANDARD 17)

set(MBEDTLS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/mbedtls)

add_library(fplaydrm SHARED
    fplay_drm_packer.cpp
    byte_buffer.cpp
    crypto.cpp
    obfuscation.cpp
    device_info.cpp
    fptwv_parser.cpp
    request_tracker.cpp
    ${MBEDTLS_DIR}/library/aes.c
    ${MBEDTLS_DIR}/library/sha256.c
    ${MBEDTLS_DIR}/library/md5.c
    ${MBEDTLS_DIR}/library/platform_util.c
)

target_include_directories(fplaydrm PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${MBEDTLS_DIR}/include
)

find_library(log-lib log)
target_link_libraries(fplaydrm ${log-lib})