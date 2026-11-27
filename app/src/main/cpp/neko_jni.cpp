// SPDX-License-Identifier: GPL-3.0-or-later
//
// NekoSU 管理器自己的 JNI 桥。
//
// 上游 neko 包用 Rust 的 jni/ crate 把整套内核接口一股脑导出成
// Java_me_nekosu_aqnya_core_Ncore_*。管理器实际只用到「读授权表 / 改授权 /
// 读内核版本」这几种，没必要拖进那层绑定，所以这里按 uapi/ioctl.h 直接实现，
// 只导出真正需要的符号。
//
// 控制 fd 的来源：内核只给被认定为 manager 的 uid 附加 [fmac_ctl]，
// 通过 prctl(NKSU_PRCTL_GET_CTL_FD) 触发，成功与否要看 /proc/self/fd
// （tracepoint 路径不会改写 prctl 的返回值）。

#include <android/log.h>
#include <dirent.h>
#include <jni.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <unistd.h>

#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "uapi/ioctl.h"

#define TAG "NekoSU/Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

constexpr const char* kCtlName = "[fmac_ctl]";
constexpr size_t kVersionBuf = 64;
constexpr size_t kFeatureBuf = NKSU_FEATURE_TEXT_MAX;

int g_ctl_fd = -1;

bool ends_with(const char* s, const char* suffix) {
    const size_t sl = std::strlen(s);
    const size_t xl = std::strlen(suffix);
    return sl >= xl && std::memcmp(s + sl - xl, suffix, xl) == 0;
}

int scan_ctl_fd() {
    DIR* dir = opendir("/proc/self/fd");
    if (dir == nullptr) return -1;

    int found = -1;
    while (dirent* entry = readdir(dir)) {
        if (entry->d_name[0] == '.') continue;

        char path[64];
        std::snprintf(path, sizeof(path), "/proc/self/fd/%s", entry->d_name);

        char target[256];
        const ssize_t len = readlink(path, target, sizeof(target) - 1);
        if (len <= 0) continue;
        target[len] = '\0';

        if (ends_with(target, kCtlName)) {
            found = std::atoi(entry->d_name);
            break;
        }
    }
    closedir(dir);
    return found;
}

/// 取控制 fd，取不到返回 -1。结果会缓存，fd 一直保持打开。
int ctl_fd() {
    if (g_ctl_fd >= 0) return g_ctl_fd;

    errno = 0;
    const int ret = prctl(NKSU_PRCTL_GET_CTL_FD, 0, 0, 0, 0);
    if (ret < 0 && errno != 0) {
        LOGW("prctl(%d) 失败: %s（设备可能没加载 NekoSU 内核模块，或本应用不是 manager）",
             NKSU_PRCTL_GET_CTL_FD, std::strerror(errno));
    }

    // 无论 prctl 的返回值如何都扫一遍：tracepoint 路径只是把 fd 挂上来，不改写返回值。
    const int fd = scan_ctl_fd();
    if (fd < 0) {
        LOGW("未找到 %s，内核控制通道不可用", kCtlName);
        return -1;
    }
    g_ctl_fd = fd;
    LOGI("已获取内核控制 fd=%d", fd);
    return fd;
}

/// 一次 IOC_CMD 调用。[data] 既是入参也是出参，长度必须与 flag 的线上布局一致。
int ioc(uint32_t flag, void* data, size_t size) {
    const int fd = ctl_fd();
    if (fd < 0) return -ENODEV;

    std::vector<uint8_t> buffer(8 + size);
    std::memcpy(buffer.data(), &flag, sizeof(flag));
    const auto len = static_cast<uint32_t>(size);
    std::memcpy(buffer.data() + 4, &len, sizeof(len));
    if (size != 0) std::memcpy(buffer.data() + 8, data, size);

    const int ret = ioctl(fd, IOC_CMD, buffer.data());
    if (ret < 0) {
        const int err = errno;
        LOGE("ioctl flag=%u size=%zu 失败: %s", flag, size, std::strerror(err));
        return -err;
    }
    if (size != 0) std::memcpy(data, buffer.data() + 8, size);
    return ret;
}

void put_u32(uint8_t* buf, size_t off, uint32_t value) { std::memcpy(buf + off, &value, sizeof(value)); }

void put_u64(uint8_t* buf, size_t off, uint64_t value) { std::memcpy(buf + off, &value, sizeof(value)); }

uint64_t get_u64(const uint8_t* buf, size_t off) {
    uint64_t value = 0;
    std::memcpy(&value, buf + off, sizeof(value));
    return value;
}

void put_cstr(uint8_t* field, size_t cap, const char* value) {
    if (cap == 0) return;
    const size_t len = std::strlen(value);
    const size_t n = len < cap - 1 ? len : cap - 1;
    std::memcpy(field, value, n);
    field[n] = '\0';
}

std::string cstr_to_string(const uint8_t* data, size_t size) {
    const size_t end = [&] {
        for (size_t i = 0; i < size; ++i) {
            if (data[i] == '\0') return i;
        }
        return size;
    }();
    return std::string(reinterpret_cast<const char*>(data), end);
}

jstring new_string(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

}  // namespace

extern "C" {

/// 内核版本串；拿不到（没加载模块 / 不是 manager）返回 null。
JNIEXPORT jstring JNICALL
Java_com_nekosu_manager_neko_NekoNative_nativeVersion(JNIEnv* env, jclass) {
    uint8_t data[kVersionBuf] = {};
    if (ioc(IOC_GET_VERSION, data, sizeof(data)) < 0) return nullptr;
    return new_string(env, cstr_to_string(data, sizeof(data)));
}

/// 控制通道是否真的可用（以能否读到内核版本为准）。
JNIEXPORT jboolean JNICALL
Java_com_nekosu_manager_neko_NekoNative_nativeAvailable(JNIEnv*, jclass) {
    uint8_t data[kVersionBuf] = {};
    return ioc(IOC_GET_VERSION, data, sizeof(data)) >= 0 ? JNI_TRUE : JNI_FALSE;
}

/// 1 已授权 / 0 未授权 / 负值 调用失败。
JNIEXPORT jint JNICALL
Java_com_nekosu_manager_neko_NekoNative_nativeHasUid(JNIEnv*, jclass, jint uid) {
    uint8_t data[FMAC_DATA_UID] = {};
    put_u32(data, FMAC_OFF_UID, static_cast<uint32_t>(uid));
    if (ioc(IOC_HAS_UID, data, sizeof(data)) < 0) return -1;
    uint32_t value = 0;
    std::memcpy(&value, data, sizeof(value));
    return value != 0 ? 1 : 0;
}

JNIEXPORT jint JNICALL
Java_com_nekosu_manager_neko_NekoNative_nativeAddUid(JNIEnv*, jclass, jint uid) {
    uint8_t data[FMAC_DATA_UID] = {};
    put_u32(data, FMAC_OFF_UID, static_cast<uint32_t>(uid));
    const int ret = ioc(IOC_ADD_UID, data, sizeof(data));
    return ret < 0 ? ret : 0;
}

JNIEXPORT jint JNICALL
Java_com_nekosu_manager_neko_NekoNative_nativeDelUid(JNIEnv*, jclass, jint uid) {
    uint8_t data[FMAC_DATA_UID] = {};
    put_u32(data, FMAC_OFF_UID, static_cast<uint32_t>(uid));
    const int ret = ioc(IOC_DEL_UID, data, sizeof(data));
    return ret < 0 ? ret : 0;
}

/// profile 表文本：每行 `<uid> <caps_hex> <ns> <domain>`；失败返回 null。
JNIEXPORT jstring JNICALL
Java_com_nekosu_manager_neko_NekoNative_nativeListProfiles(JNIEnv* env, jclass) {
    std::vector<uint8_t> data(NKSU_PROFILE_TEXT_MAX, 0);
    if (ioc(IOC_GET_PROFILES, data.data(), data.size()) < 0) return nullptr;
    return new_string(env, cstr_to_string(data.data(), data.size()));
}

/// 写一条 profile：uid + 能力位 + SELinux 域 + 命名空间 + root uid。
JNIEXPORT jint JNICALL
Java_com_nekosu_manager_neko_NekoNative_nativeSetProfile(
    JNIEnv* env, jclass, jint uid, jlong caps, jstring domain, jint ns, jint root_uid) {
    uint8_t data[FMAC_DATA_PROFILE] = {};
    put_u32(data, FMAC_OFF_UID, static_cast<uint32_t>(uid));
    put_u64(data, FMAC_OFF_CAPS, static_cast<uint64_t>(caps));

    const char* domain_chars = env->GetStringUTFChars(domain, nullptr);
    if (domain_chars == nullptr) return -EINVAL;
    put_cstr(data + FMAC_OFF_DOMAIN, FMAC_OFF_NS - FMAC_OFF_DOMAIN, domain_chars);
    env->ReleaseStringUTFChars(domain, domain_chars);

    put_u32(data, FMAC_OFF_NS, static_cast<uint32_t>(ns));
    put_u32(data, FMAC_OFF_ROOT_UID, static_cast<uint32_t>(root_uid));

    const int ret = ioc(IOC_SET_PROFILE, data, sizeof(data));
    return ret < 0 ? ret : 0;
}

/// 内核特性表文本：每行 `<id> <name> <value>`；失败返回 null。
JNIEXPORT jstring JNICALL
Java_com_nekosu_manager_neko_NekoNative_nativeFeatureList(JNIEnv* env, jclass) {
    std::vector<uint8_t> data(kFeatureBuf, 0);
    if (ioc(IOC_FEATURE_LIST, data.data(), data.size()) < 0) return nullptr;
    return new_string(env, cstr_to_string(data.data(), data.size()));
}

/// 读单个特性值；失败返回 -1。
JNIEXPORT jlong JNICALL
Java_com_nekosu_manager_neko_NekoNative_nativeFeatureGet(JNIEnv*, jclass, jint id) {
    uint8_t data[FMAC_DATA_FEATURE] = {};
    put_u32(data, 0, static_cast<uint32_t>(id));
    if (ioc(IOC_FEATURE_GET, data, sizeof(data)) < 0) return -1;
    return static_cast<jlong>(get_u64(data, FMAC_OFF_FEATURE_VALUE));
}

}  // extern "C"
