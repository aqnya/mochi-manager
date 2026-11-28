# SPDX-License-Identifier: GPL-3.0-or-later
#
# NekoSU 自己的 JNI 桥。
#
# 上游 neko 包里带一个 Rust 写的 jni/ crate，导出整套 Java_me_nekosu_aqnya_core_Ncore_*。
# 这里不保留它：管理器只需要内核控制通道里的少数几个操作，直接用 C++ 按
# uapi/ioctl.h 实现，省掉一整层 Rust 绑定和对应的符号表。
cmake_minimum_required(VERSION 3.22)
project(nekosu CXX)

# libstore：profile 落盘存储的单一实现（格式与持久化语义对齐上游 neko/store）。
# JNI（fork + setresuid 临时提权后）与 Rust 侧（ncore 是 root，直接绑定同一份
# C ABI，见 store/store.h 尾部的绑定示例）共用这一个库。
add_library(store SHARED store/store.cpp)
target_compile_features(store PRIVATE cxx_std_20)
target_compile_options(
    store
    PRIVATE
        -Wall
        -Wextra
        -fno-exceptions
        -fno-rtti
)

add_library(neko SHARED neko_jni.cpp)

# 内核 UAPI 头只有一份，放在仓库根 uapi/，由 kernel 与 ncore 共用；
# 这里按相对路径指过去，避免复制出第二份容易漂移的副本。
target_include_directories(neko PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/../../../../")

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
