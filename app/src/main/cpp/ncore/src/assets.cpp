// SPDX-License-Identifier: GPL-3.0-or-later
#include "assets.h"

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstring>
#include <string>
#include <vector>

#include "common.h"
#include "defs.h"
#include "klog.h"

namespace ncore::assets {

namespace {

// 目标文件是否已经是这份资源：一致就别重写。
// 除了省掉每次装模块 1MB 级的无谓写入，更关键的是绕开 ETXTBSY——
// 只要还有进程在跑 busybox（KSU 的 root shell 往往就是 busybox sh），
// 以 O_TRUNC 打开同一个文件会直接失败："Text file busy"。
bool content_equals(const std::string& path, const unsigned char* data, size_t size) {
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    struct stat st {};
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || static_cast<size_t>(st.st_size) != size) {
        close(fd);
        return false;
    }
    bool same = true;
    size_t off = 0;
    char buf[16 * 1024];
    while (off < size) {
        ssize_t n = read(fd, buf, sizeof(buf));
        if (n <= 0) {
            same = false;
            break;
        }
        const size_t chunk = static_cast<size_t>(n);
        if (chunk > size - off || std::memcmp(buf, data + off, chunk) != 0) {
            same = false;
            break;
        }
        off += chunk;
    }
    close(fd);
    return same;
}

// 原子替换：先写同目录临时文件，再 rename 覆盖。
// rename 只改目录项，不碰正在运行的 inode，因此即使目标正被 exec 也不会
// ETXTBSY；顺带保证不会出现「写到一半被杀、留下半截 busybox」的情况。
bool replace_file(const std::string& path, const std::vector<uint8_t>& bytes, mode_t mode) {
    const std::string tmp = path + ".tmp." + std::to_string(getpid());
    unlink(tmp.c_str());
    try {
        write_file_bytes(tmp, bytes);
    } catch (const Error& e) {
        klog("write %s failed: %s", tmp.c_str(), e.what());
        unlink(tmp.c_str());
        return false;
    }
    if (chmod(tmp.c_str(), mode) != 0 || rename(tmp.c_str(), path.c_str()) != 0) {
        unlink(tmp.c_str());
        return false;
    }
    return true;
}

}  // namespace

bool ensure_binaries(bool ignore_if_exist) {
    struct Item {
        AssetData (*get)();
        const char* name;
        bool make_exec;
    };
    const Item items[] = {
        {busybox, "busybox", true},
    };

    if (!mkdirs(defs::BINARY_DIR, 0755)) return false;

    for (const auto& item : items) {
        const AssetData data = item.get();
        const std::string path = std::string(defs::BINARY_DIR) + item.name;
        const mode_t mode = item.make_exec ? 0755 : 0644;

        if (content_equals(path, data.data, data.size)) {
            // 内容已经对得上，只补权限：chmod 不涉及文件内容，不会 ETXTBSY。
            if (item.make_exec && chmod(path.c_str(), mode) != 0) return false;
            continue;
        }
        if (ignore_if_exist && path_exists(path)) continue;

        const std::vector<uint8_t> bytes(data.data, data.data + data.size);
        if (!replace_file(path, bytes, mode)) return false;
    }

    // resetprop -> ncore 软链接（resetprop 已内建进 ncore）。
    const std::string link = RESETPROP_PATH;
    unlink(link.c_str());
    if (symlink(defs::DAEMON_PATH, link.c_str()) != 0) return false;
    return true;
}

}  // namespace ncore::assets
