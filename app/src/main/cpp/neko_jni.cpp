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
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "uapi/ioctl.h"
#include "nksu_store.h"

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
        LOGW("get ctl fd failed op=%d err=%s (kernel module not loaded, or app is not manager)",
             NKSU_PRCTL_GET_CTL_FD, std::strerror(errno));
    }

    // 无论 prctl 的返回值如何都扫一遍：tracepoint 路径只是把 fd 挂上来，不改写返回值。
    const int fd = scan_ctl_fd();
    if (fd < 0) {
        LOGW("ctl channel unavailable path=%s", kCtlName);
        return -1;
    }
    g_ctl_fd = fd;
    LOGI("ctl fd acquired fd=%d", fd);
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
        LOGE("ioctl failed flag=%u size=%zu err=%s", flag, size, std::strerror(err));
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

// root 文件读取（对齐上游 neko/jni 的 read_file_impl）
// NekoSU 内核模块在线且本应用被认定为 manager 时，内核会给本 uid 放行
// setresuid 到 0；不在线时子进程提权必然失败，Kotlin 侧据此降级到 su。
constexpr size_t kReadChunk = 65536;

/// webroot 里正常只有网页资产；超过上限按失败处理，调用方走 su 流式读取兜底。
constexpr off_t kMaxRootFileBytes = 32LL * 1024 * 1024;
/// 等子进程退出，要求正常退出且码为 0；EINTR 自动重试。
bool wait_child(pid_t pid) {
    int status = 0;
    while (true) {
        if (waitpid(pid, &status, 0) >= 0) break;
        if (errno != EINTR) return false;
    }
    return WIFEXITED(status) && WEXITSTATUS(status) == 0;
}

/// fork 出的子进程主体：setresuid 临时提权到 root，把整个文件泵进管道。
/// 进程是多线程的，fork 之后只允许 async-signal-safe 的 libc 调用
/// （open/fstat/read/write/close/_exit），不碰 JNI 与日志。
/// 权限的「恢复」靠子进程退出完成——in-process 提权后 saved-uid 恒为 0，
/// 永远降不回去，还会把 Binder 的 uid 检查搞乱，所以不能在主进程里切。
[[noreturn]] void root_reader_child(const char* path, int pipe_write) {
    if (setresuid(0, 0, 0) != 0 || getuid() != 0) _exit(1);

    const int file = open(path, O_RDONLY | O_CLOEXEC);
    if (file < 0) _exit(2);

    struct stat st {};
    if (fstat(file, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size > kMaxRootFileBytes) {
        close(file);
        _exit(3);
    }

    uint8_t buffer[kReadChunk];
    while (true) {
        const ssize_t n = read(file, buffer, sizeof(buffer));
        if (n > 0) {
            ssize_t off = 0;
            while (off < n) {
                const ssize_t w = write(pipe_write, buffer + off, static_cast<size_t>(n - off));
                if (w > 0) {
                    off += w;
                } else if (w < 0 && errno == EINTR) {
                    continue;
                } else {
                    close(file);
                    _exit(4);
                }
            }
        } else if (n == 0) {
            break;
        } else if (errno != EINTR) {
            close(file);
            _exit(5);
        }
    }
    close(file);
    close(pipe_write);
    _exit(0);
}

/// fork 出的子进程主体：提权到 root 后调用 libstore 完成 store 操作。
/// ncore 跑在 root 进程里直接调同一份库；管理器进程不是 root，只能借 fork
/// + setresuid 借出一段临时提权。退出码：0 成功 / 1 提权失败 / 2 store 失败。
template <typename Op>
[[noreturn]] void store_child(Op op) {
    if (setresuid(0, 0, 0) != 0 || getuid() != 0) _exit(1);
    const int32_t rc = op();
    if (rc != NKSU_STORE_OK) {
        LOGE("libstore: %s (rc=%d)", nksu_store_last_error(), rc);
    }
    _exit(rc == NKSU_STORE_OK ? 0 : 2);
}

/// 把一条 profile 写进落盘存储（fork 子进程临时提权），成功返回 true。
/// libstore 是上游 neko/store 编出来的 cdylib，与 ncore 共用同一份实现。
bool persist_profile(uint32_t uid, uint32_t root_uid, uint64_t caps, int32_t ns, const char* domain) {
    // libstore 会在返回前把 domain 拷贝走，这里直接借调用方的指针即可。
    nksu_profile_entry entry = {};
    entry.uid = uid;
    entry.root_uid = root_uid;
    entry.caps = caps;
    entry.ns = ns;
    entry.domain = domain == nullptr ? "" : domain;

    const pid_t pid = fork();
    if (pid < 0) return false;
    if (pid == 0) store_child([&] { return nksu_store_profile_save(&entry); });
    return wait_child(pid);
}

/// 从落盘存储删一条 profile（幂等），成功返回 true。
bool remove_persisted_profile(uint32_t uid) {
    const pid_t pid = fork();
    if (pid < 0) return false;
    if (pid == 0) store_child([&] { return nksu_store_profile_delete(uid); });
    return wait_child(pid);
}

/// 把一个 feature 开关写进落盘存储（fork 子进程临时提权），成功返回 true。
bool persist_feature(int32_t id, uint64_t value) {
    nksu_feature_entry entry = {};
    entry.id = id;
    entry.value = value;

    const pid_t pid = fork();
    if (pid < 0) return false;
    if (pid == 0) store_child([&] { return nksu_store_feature_save(&entry); });
    return wait_child(pid);
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

/// 按 uid 删除整条 profile（内核表项 + 落盘条目一次完成）。
///
/// 上游统一 profile ioctl 后，授权表只由 IOC_SET_PROFILE（整条 upsert）和本函数
/// （按 uid 删）驱动，per-field 的 add/del/has uid ioctl 已退役。撤销是幂等的：
/// 内核对不存在的条目返回 -ENOENT，视为已撤销，仍要继续清落盘那份。
JNIEXPORT jint JNICALL
Java_com_nekosu_manager_neko_NekoNative_nativeRemoveProfile(JNIEnv*, jclass, jint uid) {
    if (uid < 0) {
        LOGE("remove profile: negative uid %d", uid);
        return -1;
    }
    uint8_t data[FMAC_DATA_UID] = {};
    put_u32(data, FMAC_OFF_UID, static_cast<uint32_t>(uid));
    const int ret = ioc(IOC_REMOVE_PROFILE, data, sizeof(data));
    if (ret < 0 && ret != -ENOENT) LOGE("remove profile failed uid=%d ret=%d", uid, ret);

    // 与上游 removeProfile 一致：内核表项和落盘条目一起清，管理器只调这一个函数。
    if (!remove_persisted_profile(static_cast<uint32_t>(uid))) {
        LOGE("remove profile persist failed uid=%d", uid);
        return -1;
    }
    return 0;
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

    // 拷到本地再释放：后面 fork 落盘时还要用这个字符串，不能指望 JNI 的指针一直有效
    // 长度与内核 UAPI 里的 domain 字段一致；libstore 会在返回前把它拷走。
    char domain_buf[FMAC_OFF_NS - FMAC_OFF_DOMAIN] = {};
    const char* domain_chars = env->GetStringUTFChars(domain, nullptr);
    if (domain_chars == nullptr) return -EINVAL;
    std::strncpy(domain_buf, domain_chars, sizeof(domain_buf) - 1);
    env->ReleaseStringUTFChars(domain, domain_chars);
    put_cstr(data + FMAC_OFF_DOMAIN, FMAC_OFF_NS - FMAC_OFF_DOMAIN, domain_buf);

    put_u32(data, FMAC_OFF_NS, static_cast<uint32_t>(ns));
    put_u32(data, FMAC_OFF_ROOT_UID, static_cast<uint32_t>(root_uid));

    const int ret = ioc(IOC_SET_PROFILE, data, sizeof(data));
    if (ret < 0) {
        // 与上游 set_profile 一致：内核写失败只记录，不中断——落盘那份仍然要写，
        // ncore 下次启动会按它重新灌回内核
        LOGE("set profile failed uid=%d ret=%d", uid, ret);
    }

    // 落盘与内核写合并在同一个 JNI 调用里（对齐上游 setProfileProto）：管理器只调一次，
    // 不必也不该再单独走一遍 saveProfile，否则两步之间失败就会留下半套状态。
    if (!persist_profile(static_cast<uint32_t>(uid), static_cast<uint32_t>(root_uid),
                         static_cast<uint64_t>(caps), ns, domain_buf)) {
        LOGE("persist profile failed uid=%d", uid);
        return -1;
    }
    return 0;
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

/// 写一个特性开关：内核 IOC_FEATURE_SET + 落盘，一次 JNI 调用完成。
/// 内核写失败只记录（与上游 set_feature 一致，下次 ncore 会按落盘值灌回），
/// 落盘失败才返回 -1。返回 0 成功。
JNIEXPORT jint JNICALL
Java_com_nekosu_manager_neko_NekoNative_nativeFeatureSet(JNIEnv*, jclass, jint id, jlong value) {
    uint8_t data[FMAC_DATA_FEATURE] = {};
    put_u32(data, 0, static_cast<uint32_t>(id));
    put_u64(data, FMAC_OFF_FEATURE_VALUE, static_cast<uint64_t>(value));

    const int ret = ioc(IOC_FEATURE_SET, data, sizeof(data));
    if (ret < 0) LOGE("set feature failed id=%d ret=%d", id, ret);

    if (!persist_feature(id, static_cast<uint64_t>(value))) {
        LOGE("persist feature failed id=%d", id);
        return -1;
    }
    return 0;
}

/// 以 root 读整个文件；失败（提权不了 / 打不开 / 不是普通文件 / 超上限）返回 null。
///
/// 每次调用 fork 一个子进程：setresuid(0,0,0) 临时提权 → open → 泵管道 → 退出。
/// 相比 `su -c cat` 少了 su 二进制 exec、shell 解析和 SELinux 域迁移，
/// 每次 WebUI 资源请求都省一大截；内核不在线时子进程提权失败，返回 null。
JNIEXPORT jbyteArray JNICALL
Java_com_nekosu_manager_neko_NekoNative_nativeReadRootFile(JNIEnv* env, jclass, jstring jpath) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (path == nullptr) return nullptr;

    // 子进程写管道、父进程先关读端时可能触发 SIGPIPE；忽略它，让 write 报错退出子进程
    static const bool sigpipe_ignored = [] {
        signal(SIGPIPE, SIG_IGN);
        return true;
    }();
    (void)sigpipe_ignored;

    int pipe_fd[2];
    if (pipe(pipe_fd) != 0) {
        env->ReleaseStringUTFChars(jpath, path);
        return nullptr;
    }

    const pid_t pid = fork();
    if (pid < 0) {
        close(pipe_fd[0]);
        close(pipe_fd[1]);
        env->ReleaseStringUTFChars(jpath, path);
        return nullptr;
    }
    if (pid == 0) {
        close(pipe_fd[0]);
        root_reader_child(path, pipe_fd[1]);
    }

    env->ReleaseStringUTFChars(jpath, path);
    close(pipe_fd[1]);

    std::vector<uint8_t> data;
    uint8_t buffer[kReadChunk];
    while (true) {
        const ssize_t n = read(pipe_fd[0], buffer, sizeof(buffer));
        if (n > 0) {
            data.insert(data.end(), buffer, buffer + n);
        } else if (n == 0) {
            break;
        } else if (errno != EINTR) {
            break;
        }
    }
    close(pipe_fd[0]);

    if (!wait_child(pid) || data.empty()) return nullptr;

    jbyteArray result = env->NewByteArray(static_cast<jsize>(data.size()));
    if (result == nullptr) return nullptr;
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(data.size()),
                            reinterpret_cast<const jbyte*>(data.data()));
    return result;
}

}  // extern "C"
