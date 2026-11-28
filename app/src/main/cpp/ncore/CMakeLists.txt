# SPDX-License-Identifier: GPL-3.0-or-later
#
# NekoSU ncore 的 C++ 实现。
#
# 以 add_executable 构建，输出顶着 .so 名字的 PIE 可执行文件（libncore.so）。
# 构建产物由 AGP 收进 cxx/<config>/<hash>/obj/<abi>/，随 APK 打成 lib/<abi>/libncore.so
# ——内核在 boot 阶段从 nativeLibraryDir 找 libncore.so 引导，因此名字必须与库一致，
# 但它本身不是链接库。
#
# 取代原先的 build-ncore.sh（rustup + cargo + 拷 so + 追加签名）整条链路。

cmake_minimum_required(VERSION 3.22)
project(ncore LANGUAGES C CXX ASM)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# ---- 内嵌 assets：installer.sh / banner / busybox ----
# 用汇编 .incbin 把二进制流式嵌入只读段（比 file(READ HEX) 快得多、内存占用低）。
set(NCORE_ASSETS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../neko/ncore")
set(EMBEDDED_S "${CMAKE_CURRENT_BINARY_DIR}/assets_embedded.S")

set(INSTALLER_SH "${NCORE_ASSETS_DIR}/assets/installer.sh")
set(BANNER "${NCORE_ASSETS_DIR}/assets/banner")
set(BUSYBOX "${NCORE_ASSETS_DIR}/bin/aarch64/busybox")
foreach(f IN ITEMS INSTALLER_SH BANNER BUSYBOX)
    if(NOT EXISTS "${${f}}")
        message(FATAL_ERROR "ncore asset missing: ${${f}}")
    endif()
endforeach()

configure_file(
    "${CMAKE_CURRENT_SOURCE_DIR}/assets_embedded.S.in"
    "${EMBEDDED_S}"
    @ONLY
)

# ---- 版本信息（对齐 build.rs 的 git 计数 + describe） ----
execute_process(
    COMMAND git rev-list --count HEAD
    WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../"
    OUTPUT_VARIABLE GIT_COMMIT_COUNT
    OUTPUT_STRIP_TRAILING_WHITESPACE
    ERROR_QUIET
)
if(NOT GIT_COMMIT_COUNT)
    set(GIT_COMMIT_COUNT "0")
endif()
math(EXPR NKSU_VERSION_CODE "30000 + ${GIT_COMMIT_COUNT}")
execute_process(
    COMMAND git describe --tags --always
    WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../"
    OUTPUT_VARIABLE NKSU_VERSION_NAME_RAW
    OUTPUT_STRIP_TRAILING_WHITESPACE
    ERROR_QUIET
)
if(NOT NKSU_VERSION_NAME_RAW)
    set(NKSU_VERSION_NAME_RAW "unknown")
endif()
string(REGEX REPLACE "^v" "" NKSU_VERSION_NAME "${NKSU_VERSION_NAME_RAW}")

# 管理器签名证书 SHA-256，与旧 build-ncore.sh 的默认值保持一致。
# 换 keystore 时可通过 -DNKSU_MANAGER_CERTHASH=... 或环境变量覆盖。
if(DEFINED ENV{NKSU_MANAGER_CERTHASH} AND NOT "$ENV{NKSU_MANAGER_CERTHASH}" STREQUAL "")
    set(NKSU_MANAGER_CERTHASH "$ENV{NKSU_MANAGER_CERTHASH}")
elseif(NOT DEFINED NKSU_MANAGER_CERTHASH OR NKSU_MANAGER_CERTHASH STREQUAL "")
    set(NKSU_MANAGER_CERTHASH "ce69488700849f24df4ec9cbf7c88652d45749bc68df3981003308d221d5969f")
endif()

set(NCORE_SOURCES
    src/main.cpp
    src/common.cpp
    src/klog.cpp
    src/kzcall.cpp
    src/sha256.cpp
    src/abx.cpp
    src/manager.cpp
    src/sepolicy.cpp
    src/restorecon.cpp
    src/utils.cpp
    src/assets.cpp
    src/unzip.cpp
    src/properties.cpp
    src/prop_area.cpp
    src/json.cpp
    src/config.cpp
    src/metamodule.cpp
    src/hot_install.cpp
    src/preinit.cpp
    src/module.cpp
    src/boot.cpp
    src/antibrick.cpp
    src/su.cpp
    src/resetprop.cpp
    src/assets_data.cpp
    third_party/zstd/zstddeclib.c
    third_party/xz/xz_crc32.c
    third_party/xz/xz_crc64.c
    third_party/xz/xz_dec_stream.c
    third_party/xz/xz_dec_lzma2.c
    third_party/xz/xz_dec_bcj.c
    "${EMBEDDED_S}"
)

add_executable(ncore ${NCORE_SOURCES})

target_include_directories(ncore PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
    "${CMAKE_CURRENT_SOURCE_DIR}/third_party/zstd"   # zstd.h（单文件 decoder 的公开 API）
    "${CMAKE_CURRENT_SOURCE_DIR}/third_party/xz"     # xz.h（xz-embedded 的公开 API）
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../"   # 仓库根 uapi/*.h
)

# 第三方解码器，不参与本项目的警告基线。
set_source_files_properties(third_party/zstd/zstddeclib.c PROPERTIES COMPILE_OPTIONS "-w")
set_source_files_properties(
    third_party/xz/xz_crc32.c
    third_party/xz/xz_crc64.c
    third_party/xz/xz_dec_stream.c
    third_party/xz/xz_dec_lzma2.c
    third_party/xz/xz_dec_bcj.c
    PROPERTIES COMPILE_OPTIONS "-w"
)

target_compile_definitions(ncore PRIVATE
    NKSU_VERSION_CODE="${NKSU_VERSION_CODE}"
    NKSU_VERSION_NAME="${NKSU_VERSION_NAME}"
    NKSU_MANAGER_CERTHASH="${NKSU_MANAGER_CERTHASH}"
)

target_compile_options(ncore PRIVATE
    -Wall
    -Wno-unused-parameter
    -fno-rtti
)

# 顶着 .so 名字的可执行文件。
# 不要自己指定 RUNTIME_OUTPUT_DIRECTORY：AGP 会把 add_executable 的产物收到
# cxx/<config>/<hash>/obj/<abi>/ 并一起打进 APK 的 lib/<abi>/。
# 之前手动写到 src/main/jniLibs/<abi>/ 会让同一路径出现两份 libncore.so，
# release 打包时报 "2 files found with path 'lib/arm64-v8a/libncore.so'"。
set_target_properties(ncore PROPERTIES
    OUTPUT_NAME "ncore"
    PREFIX "lib"
    SUFFIX ".so"
    POSITION_INDEPENDENT_CODE ON
)

# zlib：zip 解压的 inflate（method 8）。这是 NDK 里唯一稳定可用的解压库。
# zstd（method 93）走 third_party/zstd 的单文件 decoder，xz（method 95）走
# third_party/xz 的 xz-embedded：NDK 既不导出 libzstd 也不导出 liblzma，
# 设备上的系统库也不保证存在，静态编进来最稳。
find_library(ZLIB z)
target_link_libraries(ncore PRIVATE ${ZLIB})

# 去掉符号表，减小 APK 体积（与旧 cargo 产物一致）。AGP 走 Gradle 时不会设置
# CMAKE_BUILD_TYPE，条件判断不生效，故直接无条件 strip。
# ncore 是 PIE 可执行文件（顶着 .so 名），strip 不影响其可执行性。
target_link_options(ncore PRIVATE -s)
