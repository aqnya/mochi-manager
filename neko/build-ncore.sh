#!/usr/bin/env sh
# SPDX-License-Identifier: GPL-3.0-or-later
#
# 交叉编译 ncore（neko 包的用户态运行时），产物直接落到 JNI 目录：
#   app/src/main/jniLibs/arm64-v8a/libncore.so
#
# 为什么叫 .so：内核从 PackageManager 解包出来的 native 库目录里找
# libncore.so（/data/app/<pkg>/lib/arm64-v8a/libncore.so）做引导，
# 名字必须一致；它本身是可执行文件（PIE），不是链接库。
#
# 环境变量：
#   ANDROID_NDK_HOME        NDK 路径（默认 /opt/android-sdk/ndk/27.2.12479018）
#   NKSU_MANAGER_CERTHASH   管理器签名证书 SHA-256（64 位 hex）。ncore 在 boot 阶段
#                           拿它在 packages.xml 里找管理器包，再把 uid 交给内核；
#                           管理器自己不参与认证。默认值是本仓库 release keystore 的
#                           证书指纹，换签名时覆盖它并重编。
#   SIGNING_KEY / SIGNING_PASSWORD
#                           可选：ncore 镜像的 RSA-4096 私钥与口令。只有 release 内核
#                           才会验这层签名；debug 内核（CONFIG_NKSU_DEBUG）跳过镜像、
#                           spawn、caller 全部 ncore 验签，所以不签也能被加载。
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
NDK=${ANDROID_NDK_HOME:-/opt/android-sdk/ndk/27.2.12479018}
API=${NKSU_MIN_SDK:-31}
TARGET=aarch64-linux-android
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
CC="$TOOLCHAIN/bin/${TARGET}${API}-clang"
AR="$TOOLCHAIN/bin/llvm-ar"

if [ ! -x "$CC" ]; then
    echo "找不到 Android 交叉编译器：$CC" >&2
    exit 1
fi

SYSROOT="$TOOLCHAIN/sysroot"

export CC_aarch64_linux_android="$CC"
export CXX_aarch64_linux_android="$TOOLCHAIN/bin/${TARGET}${API}-clang++"
export AR_aarch64_linux_android="$AR"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC"
# bindgen 生成 uapi 绑定时要用 Android 的 sysroot，否则会去翻宿主机的
# /usr/include/linux/ioctl.h，接着找不到 asm/ioctl.h（架构头在 NDK 里）。
export BINDGEN_EXTRA_CLANG_ARGS_aarch64_linux_android="--target=${TARGET}${API} --sysroot=$SYSROOT -I$SYSROOT/usr/include/$TARGET"
export NKSU_MANAGER_CERTHASH=${NKSU_MANAGER_CERTHASH:-ce69488700849f24df4ec9cbf7c88652d45749bc68df3981003308d221d5969f}

rustup target add "$TARGET" >/dev/null 2>&1 || true

cd "$ROOT/neko"
cargo build --release --target "$TARGET" -p ncore --target-dir "$ROOT/neko/target"

mkdir -p "$OUT"
cp "$ROOT/neko/target/$TARGET/release/ncore" "$OUT/libncore.so"
chmod 644 "$OUT/libncore.so"

if [ -n "${SIGNING_KEY:-}" ] && [ -f "${SIGNING_KEY}" ] && [ -n "${SIGNING_PASSWORD:-}" ]; then
    openssl dgst -sha256 -sign "$SIGNING_KEY" -passin "env:SIGNING_PASSWORD" \
        -out "$ROOT/neko/target/ncore.sig" "$OUT/libncore.so"
    size=$(wc -c < "$ROOT/neko/target/ncore.sig" | tr -d ' ')
    if [ "$size" -ne 512 ]; then
        echo "签名长度 $size 不是 512 字节，确认 SIGNING_KEY 是 RSA-4096" >&2
        exit 1
    fi
    cat "$ROOT/neko/target/ncore.sig" >> "$OUT/libncore.so"
    rm -f "$ROOT/neko/target/ncore.sig"
    echo "已追加 512 字节签名"
else
    echo "未提供签名私钥：产物未带签名，仅 debug 内核（CONFIG_NKSU_DEBUG）能加载"
fi

ls -l "$OUT/libncore.so"
