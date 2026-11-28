// SPDX-License-Identifier: GPL-3.0-or-later
#include "restorecon.h"

#include <dirent.h>
#include <functional>
#include <sys/xattr.h>

#include "common.h"
#include "defs.h"
#include "klog.h"

namespace ncore::restorecon {

namespace {
constexpr const char* SELINUX_XATTR = "security.selinux";

void walk(const std::string& dir, const std::function<void(const std::string&)>& fn) {
    DIR* d = opendir(dir.c_str());
    if (d == nullptr) {
        fn(dir);  // 文件本身
        return;
    }
    fn(dir);
    struct dirent* ent;
    while ((ent = readdir(d)) != nullptr) {
        std::string name = ent->d_name;
        if (name == "." || name == "..") continue;
        std::string child = dir;
        if (!child.empty() && child.back() == '/') child += name;
        else child += "/" + name;
        struct stat st;
        if (lstat(child.c_str(), &st) == 0 && S_ISDIR(st.st_mode)) {
            walk(child, fn);
        } else {
            fn(child);
        }
    }
    closedir(d);
}
}  // namespace

bool lsetfilecon(const std::string& path, const std::string& con) {
    return lsetxattr(path.c_str(), SELINUX_XATTR, con.c_str(), con.size() + 1, 0) == 0;
}

bool lgetfilecon(const std::string& path, std::string& out) {
    char buf[512] = {};
    ssize_t n = lgetxattr(path.c_str(), SELINUX_XATTR, buf, sizeof(buf) - 1);
    if (n < 0) return false;
    buf[n] = '\0';
    out = buf;
    return true;
}

bool setsyscon(const std::string& path) {
    return lsetfilecon(path, SYSTEM_CON);
}

bool restore_syscon(const std::string& dir) {
    bool ok = true;
    walk(dir, [&](const std::string& p) {
        if (!setsyscon(p)) ok = false;
    });
    return ok;
}

static bool restore_syscon_if_unlabeled(const std::string& dir) {
    bool ok = true;
    walk(dir, [&](const std::string& p) {
        std::string con;
        if (lgetfilecon(p, con) && (con == UNLABEL_CON || con.empty())) {
            if (!setsyscon(p)) ok = false;
        }
    });
    return ok;
}

bool restorecon() {
    bool ok = lsetfilecon(defs::DAEMON_PATH, KSU_CON);
    if (!restore_syscon_if_unlabeled(defs::MODULE_DIR)) ok = false;
    return ok;
}

}  // namespace ncore::restorecon
