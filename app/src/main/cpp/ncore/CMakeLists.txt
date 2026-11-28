# SPDX-License-Identifier: GPL-3.0-or-later
#
# NekoSU ncore 的 C++ 实现。
#
# 以 add_executable 构建，输出顶着 .so 名字的 PIE 可执行文件（libncore.so），
# 直接落进 jniLibs 由 Gradle 打包——内核在 boot 阶段从 nativeLibraryDir 找
# libncore.so 引导，因此名字必须与库一致，但它本身不是链接库。
#
# 取代原先的 build-ncore.sh（rustup + cargo + 拷 so + 追加签名）整条链路。

cmake_minimum_required(VERSION 3.22)
project(ncore LANGUAGES CXX ASM)

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
    "${EMBEDDED_S}"
)

add_executable(ncore ${NCORE_SOURCES})

target_include_directories(ncore PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../"   # 仓库根 uapi/*.h
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

# 顶着 .so 名字的可执行文件，输出到 jniLibs/<abi>/ 供 Gradle 打包。
set_target_properties(ncore PROPERTIES
    OUTPUT_NAME "ncore"
    PREFIX "lib"
    SUFFIX ".so"
    POSITION_INDEPENDENT_CODE ON
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/../../jniLibs/${ANDROID_ABI}"
)

# zlib：zip 解压（ inflate ）；zstd/lzma 模块 zip 极少用，store/deflate 覆盖绝大多数。
find_library(ZLIB z)
target_link_libraries(ncore PRIVATE ${ZLIB})

# 去掉符号表，减小 APK 体积（与旧 cargo 产物一致）。AGP 走 Gradle 时不会设置
# CMAKE_BUILD_TYPE，条件判断不生效，故直接无条件 strip。
# ncore 是 PIE 可执行文件（顶着 .so 名），strip 不影响其可执行性。
target_link_options(ncore PRIVATE -s)
