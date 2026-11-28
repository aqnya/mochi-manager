// SPDX-License-Identifier: GPL-3.0-or-later
#include "utils.h"

#include <cstdlib>
#include <dirent.h>
#include <fcntl.h>
#include <sched.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

#include "assets.h"
#include "common.h"
#include "defs.h"
#include "klog.h"
#include "kzcall.h"
#include "restorecon.h"

namespace ncore::utils {

void umask(uint32_t mask) { ::umask(static_cast<mode_t>(mask)); }

bool ensure_clean_dir(const std::string& dir) {
    if (path_exists(dir)) remove_dir_all(dir);
    return mkdirs(dir, 0755);
}

bool ensure_file_exists(const std::string& file) {
    int fd = open(file.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0644);
    if (fd >= 0) {
        close(fd);
        return true;
    }
    if (errno == EEXIST && is_file(file)) return true;
    return false;
}

bool ensure_dir_exists(const std::string& dir) { return mkdirs(dir, 0755); }

bool ensure_binary(const std::string& path, const std::vector<uint8_t>& contents, bool ignore_if_exist) {
    if (ignore_if_exist && path_exists(path)) return true;
    size_t slash = path.rfind('/');
    if (slash != std::string::npos && !mkdirs(path.substr(0, slash), 0755)) return false;
    unlink(path.c_str());
    write_file_bytes(path, contents);
    chmod(path.c_str(), 0755);
    return true;
}

bool is_safe_mode() {
    bool safemode = getprop("persist.sys.safemode") == "1" || getprop("ro.sys.safemode") == "1";
    if (safemode) return true;
    try {
        return kzcall::check_kernel_safemode();
    } catch (const Error&) {
        return false;
    }
}

bool has_magisk() {
    // which("magisk")：在 PATH 中找可执行文件。
    std::string path = getprop("PATH");
    if (path.empty()) {
        const char* p = getenv("PATH");
        path = p != nullptr ? p : "";
    }
    for (const auto& dir : split(path, ':')) {
        std::string candidate = (dir.empty() ? "." : dir) + "/magisk";
        if (is_file(candidate) && access(candidate.c_str(), X_OK) == 0) return true;
    }
    return false;
}

std::string runtime_mode() {
    std::string buf;
    if (!read_file_to_string("/proc/modules", buf)) return "unknown";
    for (const auto& line : split(buf, '\n')) {
        auto parts = split(line, ' ');
        if (!parts.empty() && parts[0] == "nksu") return "lkm";
    }
    return "built-in";
}

bool switch_mnt_ns(int pid) {
    std::string path = "/proc/" + std::to_string(pid) + "/ns/mnt";
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    char cwd[4096] = {};
    bool has_cwd = getcwd(cwd, sizeof(cwd)) != nullptr;
    int rc = setns(fd, CLONE_NEWNS);
    close(fd);
    if (rc != 0) return false;
    if (has_cwd) chdir(cwd);
    return true;
}

namespace {
void switch_cgroup(const std::string& grp, pid_t pid) {
    std::string path = grp + "/cgroup.procs";
    if (!path_exists(path)) return;
    int fd = open(path.c_str(), O_WRONLY | O_APPEND | O_CLOEXEC);
    if (fd < 0) return;
    std::string val = std::to_string(pid);
    (void)write(fd, val.data(), val.size());
    close(fd);
}
}  // namespace

void switch_cgroups() {
    pid_t pid = getpid();
    switch_cgroup("/acct", pid);
    switch_cgroup("/dev/cg2_bpf", pid);
    switch_cgroup("/sys/fs/cgroup", pid);
    std::string memcg = getprop("ro.config.per_app_memcg");
    if (memcg.empty() || memcg != "false") switch_cgroup("/dev/memcg/apps", pid);
}

void detach_process_group(bool use_init_pgrp) {
    if (use_init_pgrp) {
        try {
            kzcall::set_init_pgrp();
            return;
        } catch (const Error&) {
            // fall through to setpgid
        }
    }
    setpgid(0, 0);
}

bool install() {
    if (!ensure_dir_exists(defs::ADB_DIR)) return false;
    unlink(defs::DAEMON_PATH);
    // 用 /proc/self/exe，不要解析真实路径：这样执行 /data/adb/ncore install 时
    // 不会把正在运行的自己删掉。
    int src = open("/proc/self/exe", O_RDONLY | O_CLOEXEC);
    if (src < 0) return false;
    int dst = open(defs::DAEMON_PATH, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0755);
    if (dst < 0) {
        close(src);
        return false;
    }
    char buf[8192];
    ssize_t n;
    bool ok = true;
    while ((n = read(src, buf, sizeof(buf))) > 0) {
        if (write(dst, buf, static_cast<size_t>(n)) != n) { ok = false; break; }
    }
    if (n < 0) ok = false;
    close(src);
    close(dst);
    if (!ok) return false;
    restorecon::lsetfilecon(defs::DAEMON_PATH, restorecon::KSU_CON);
    if (!assets::ensure_binaries(false)) return false;
    // link /data/adb/nksu/bin/ncore -> /data/adb/ncore
    if (path_exists(defs::DAEMON_PATH) && !path_exists(defs::DAEMON_LINK_PATH)) {
        symlink(defs::DAEMON_PATH, defs::DAEMON_LINK_PATH);
    }
    return true;
}

}  // namespace ncore::utils
