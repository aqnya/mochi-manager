# SPDX-License-Identifier: GPL-3.0-or-later
#
# NekoSU 自己的 JNI 桥。
#
# 上游 neko 包里带一个 Rust 写的 jni/ crate，导出整套 Java_me_nekosu_aqnya_core_Ncore_*。
# 这里不保留它：管理器只需要内核控制通道里的少数几个操作，直接用 C++ 按
# uapi/ioctl.h 实现，省掉一整层 Rust 绑定和对应的符号表。
cmake_minimum_required(VERSION 3.22)
project(nekosu CXX)

# libstore：profile/feature 落盘存储直接用上游 neko/store 编出来的 cdylib。
# 之前这里有一份按 db.rs 格式手写的 C++ 实现，格式一旦漂移两边就会写出互相
# 读不懂的文件；现在 C ABI（neko/store/include/nksu_store.h）是唯一契约，
# 管理器与 ncore 共用同一份实现。
set(NEKO_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../neko")
set(STORE_SO "${NEKO_DIR}/target/aarch64-linux-android/release/libstore.so")

find_program(CARGO_EXECUTABLE cargo REQUIRED)

# cargo 要用 NDK 的 clang 当 linker；bindgen（uapi crate）还要 sysroot 才找得到
# asm/ioctl.h。CMAKE_C_COMPILER / CMAKE_SYSROOT 由 AGP 的 toolchain 文件给出，
# 这里原样转给 cargo，避免再维护一套 NDK 路径推导。
string(REPLACE "android-" "" ANDROID_API_LEVEL "${ANDROID_PLATFORM}")
if(ANDROID_API_LEVEL STREQUAL "")
    set(ANDROID_API_LEVEL "31")
endif()

# AGP 给的 CMAKE_C_COMPILER 是通用 clang（目标由 --target 参数决定），cargo
# 只能传一个可执行路径，所以换成 NDK 同名目录下的 target wrapper：它自带
# --target 与 sysroot，-llog / -lc 才找得到。
get_filename_component(NDK_BIN_DIR "${CMAKE_C_COMPILER}" DIRECTORY)
set(STORE_LINKER "${NDK_BIN_DIR}/aarch64-linux-android${ANDROID_API_LEVEL}-clang")
if(NOT EXISTS "${STORE_LINKER}")
    set(STORE_LINKER "${CMAKE_C_COMPILER}")
endif()

add_custom_command(
    OUTPUT "${STORE_SO}"
    COMMAND
        ${CMAKE_COMMAND} -E env
        "CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=${STORE_LINKER}"
        "CC_aarch64_linux_android=${STORE_LINKER}"
        "AR_aarch64_linux_android=${CMAKE_AR}"
        "BINDGEN_EXTRA_CLANG_ARGS_aarch64-linux-android=--sysroot=${CMAKE_SYSROOT} -target aarch64-linux-android${ANDROID_API_LEVEL}"
        # rustc 默认不给 cdylib 写 SONAME，链接方就会把绝对路径记进 DT_NEEDED，
        # 到了设备上直接找不到库。显式补一个，DT_NEEDED 才是 libstore.so。
        "RUSTFLAGS=-C link-arg=-Wl,-soname,libstore.so"
        "${CARGO_EXECUTABLE}" build --release -p store --target aarch64-linux-android
    WORKING_DIRECTORY "${NEKO_DIR}"
    DEPENDS
        "${NEKO_DIR}/Cargo.toml"
        "${NEKO_DIR}/Cargo.lock"
        "${NEKO_DIR}/store/Cargo.toml"
        "${NEKO_DIR}/store/include/nksu_store.h"
        "${NEKO_DIR}/proto/proto/profile.proto"
        "${NEKO_DIR}/proto/proto/feature.proto"
        "${NEKO_DIR}/store/src/cabi.rs"
        "${NEKO_DIR}/store/src/db.rs"
        "${NEKO_DIR}/store/src/feature.rs"
        "${NEKO_DIR}/store/src/lib.rs"
        "${NEKO_DIR}/store/src/profile.rs"
        "${NEKO_DIR}/store/src/root.rs"
        "${NEKO_DIR}/proto/src/lib.rs"
    COMMENT "Building libstore.so (cargo, neko/store)"
    VERBATIM
)

add_custom_target(store_rust DEPENDS "${STORE_SO}")

add_library(store SHARED IMPORTED)
set_target_properties(store PROPERTIES IMPORTED_LOCATION "${STORE_SO}")

add_library(neko SHARED neko_jni.cpp)
add_dependencies(neko store_rust)

# 内核 UAPI 头只有一份，放在仓库根 uapi/，由 kernel 与 ncore 共用；
# 这里按相对路径指过去，避免复制出第二份容易漂移的副本。
target_include_directories(
    neko
    PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/../../../../"
        "${NEKO_DIR}/store/include"
)

target_compile_features(neko PRIVATE cxx_std_20)
target_compile_options(
    neko
    PRIVATE
        -Wall
        -Wextra
        -Wno-unused-parameter
        -fvisibility=hidden
        -fno-exceptions
        -fno-rtti
)

# 只导出 JNI 符号，别把静态链接进来的 libc++ 符号也挂出去
target_link_options(neko PRIVATE -Wl,--exclude-libs,ALL)

target_link_libraries(neko PRIVATE store log)
