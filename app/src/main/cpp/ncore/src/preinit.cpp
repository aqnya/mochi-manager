// SPDX-License-Identifier: GPL-3.0-or-later
// Preinit: 把启用模块的 preinit/ 目录同步到 /metadata/<...>/nksu/preinit/，
// 下次启动的 init wrapper 会在 SELinux policy 加载前执行它们的 preinit.conf。
#include "preinit.h"

#include <algorithm>
#include <cerrno>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <unistd.h>

#include "common.h"
#include "defs.h"
#include "klog.h"
#include "module.h"
#include "restorecon.h"

namespace ncore::preinit {

namespace {
constexpr uint64_t BLOCK = 4096;
constexpr uint64_t LIMIT_TOTAL = 4ULL << 20;
constexpr uint64_t RESERVE = 8ULL << 20;
constexpr uint64_t BOOTCOUNT_LIMIT = 3;

constexpr const char* STATE_OK = "ok";
constexpr const char* STATE_NO_SPACE = "no_space";
constexpr const char* STATE_ERROR = "error";

struct Entry {
    std::string rel;
    bool is_dir = false;
    uint64_t size = 0;
    uint32_t mode = 0;
};

struct Status {
    std::string id;
    std::string state;
    std::string message;
};

uint64_t round_up(uint64_t size) { return ((size + BLOCK - 1) / BLOCK) * BLOCK; }

void scan(const std::string& root, const std::string& rel, std::vector<Entry>& out) {
    std::string dir = root.empty() ? root : root + "/" + rel;
    DIR* d = opendir(dir.c_str());
    if (d == nullptr) throw Error("Failed to read " + dir);
    std::vector<std::string> names;
    struct dirent* ent;
    while ((ent = readdir(d)) != nullptr) {
        std::string n = ent->d_name;
        if (n == "." || n == "..") continue;
        names.push_back(n);
    }
    closedir(d);
    std::sort(names.begin(), names.end());
    for (const auto& n : names) {
        std::string child_rel = rel.empty() ? n : rel + "/" + n;
        std::string full = root + "/" + child_rel;
        struct stat st;
        if (lstat(full.c_str(), &st) != 0) continue;
        Entry e;
        e.rel = child_rel;
        e.is_dir = S_ISDIR(st.st_mode);
        e.size = S_ISREG(st.st_mode) ? static_cast<uint64_t>(st.st_size) : 0;
        e.mode = st.st_mode & 07777;
        out.push_back(e);
        if (e.is_dir) scan(root, child_rel, out);
    }
}

uint64_t usage(const std::vector<Entry>& entries) {
    uint64_t total = BLOCK;
    for (const auto& e : entries) total += e.is_dir ? BLOCK : round_up(e.size);
    return total;
}

uint64_t disk_usage(const std::string& path) {
    struct stat st;
    if (lstat(path.c_str(), &st) != 0) return 0;
    uint64_t total = static_cast<uint64_t>(st.st_blocks) * 512;
    if (S_ISDIR(st.st_mode)) {
        DIR* d = opendir(path.c_str());
        if (d != nullptr) {
            struct dirent* ent;
            while ((ent = readdir(d)) != nullptr) {
                std::string n = ent->d_name;
                if (n == "." || n == "..") continue;
                total += disk_usage(path + "/" + n);
            }
            closedir(d);
        }
    }
    return total;
}

uint64_t available(const std::string& path) {
    struct statvfs st;
    if (statvfs(path.c_str(), &st) != 0) return 0;
    return static_cast<uint64_t>(st.f_bavail) * st.f_frsize;
}

bool same_copy(const std::string& dst, const std::string& src, const std::vector<Entry>& entries) {
    std::vector<Entry> existing;
    try {
        scan(dst, "", existing);
    } catch (const Error&) {
        return false;
    }
    if (existing.size() != entries.size()) return false;
    for (size_t i = 0; i < entries.size(); ++i) {
        const auto& want = entries[i];
        const auto& have = existing[i];
        if (want.rel != have.rel || want.is_dir != have.is_dir ||
            (want.mode & 0755) != (have.mode & 0755)) return false;
        if (!want.is_dir) {
            if (want.size != have.size) return false;
            auto a = read_file(src + "/" + want.rel);
            auto b = read_file(dst + "/" + have.rel);
            if (a != b) return false;
        }
    }
    return true;
}

bool install(const std::string& preinit_dir, const std::string& id, const std::string& src,
             const std::vector<Entry>& entries, const std::string& con) {
    std::string tmp = preinit_dir + "/." + id + ".tmp";
    if (path_exists(tmp)) remove_dir_all(tmp);
    if (!mkdirs(tmp, 0755)) return false;
    if (!secure(tmp, 0755, con)) return false;
    for (const auto& e : entries) {
        std::string to = tmp + "/" + e.rel;
        if (e.is_dir) {
            mkdirs(to, 0755);
        } else {
            auto content = read_file(src + "/" + e.rel);
            write_file_bytes(to, content);
            int fd = open(to.c_str(), O_RDONLY);
            if (fd >= 0) { fsync(fd); close(fd); }
        }
        secure(to, e.mode, con);
    }
    std::string dst = preinit_dir + "/" + id;
    std::string old = preinit_dir + "/." + id + ".old";
    if (path_exists(dst)) {
        if (path_exists(old)) remove_dir_all(old);
        rename(dst.c_str(), old.c_str());
    }
    if (rename(tmp.c_str(), dst.c_str()) != 0) return false;
    if (path_exists(old)) remove_dir_all(old);
    return true;
}

std::string bootcount_path() {
    return std::string(module::preinit_ksu_dir()) + defs::PREINIT_BOOTCOUNT_FILE;
}

uint64_t read_bootcount() {
    std::string s;
    if (!read_file_to_string(bootcount_path(), s)) return 0;
    char* end = nullptr;
    unsigned long v = std::strtoul(trim(s).c_str(), &end, 10);
    return *end == '\0' ? static_cast<uint64_t>(v) : 0;
}

void write_status(const std::vector<Status>& status) {
    std::string content;
    for (const auto& s : status) content += s.id + " " + s.state + " " + s.message + "\n";
    if (mkdirs(defs::WORKING_DIR, 0755)) write_atomic(defs::PREINIT_STATUS_PATH, content);
}

void remove_all(const std::string& ksu_dir) {
    std::string preinit_dir = ksu_dir + "/" + defs::PREINIT_SUBDIR;
    if (path_exists(preinit_dir)) remove_dir_all(preinit_dir);
    unlink((ksu_dir + "/" + defs::PREINIT_BOOTCOUNT_FILE).c_str());
}

bool remove_unlisted(const std::string& preinit_dir, const std::vector<std::string>& keep, bool& changed) {
    DIR* d = opendir(preinit_dir.c_str());
    if (d == nullptr) return false;
    struct dirent* ent;
    while ((ent = readdir(d)) != nullptr) {
        std::string name = ent->d_name;
        if (name == "." || name == ".." || name == defs::PREINIT_MANIFEST_FILE ||
            std::find(keep.begin(), keep.end(), name) != keep.end()) continue;
        std::string p = preinit_dir + "/" + name;
        bool removed = is_dir(p) ? remove_dir_all(p) : (unlink(p.c_str()) == 0);
        if (removed && name[0] != '.') changed = true;
    }
    closedir(d);
    return true;
}
}  // namespace

bool secure(const std::string& path, uint32_t mode, const std::string& con) {
    if (chown(path.c_str(), 0, 0) != 0) return false;
    if (chmod(path.c_str(), mode & 0755) != 0) return false;
    restorecon::lsetfilecon(path, con);  // best effort
    return true;
}

bool sync(const std::string& ksu_dir, const std::vector<std::string>& stale_dirs,
          const std::map<std::string, std::string>& modules, const std::string& con) {
    for (const auto& stale : stale_dirs) remove_all(stale);

    std::vector<Status> status;
    struct Candidate {
        std::string id;
        std::string src;
        std::vector<Entry> entries;
        uint64_t need = 0;
    };
    std::vector<Candidate> candidates;

    for (const auto& kv : modules) {
        const std::string& id = kv.first;
        std::string src = kv.second + "/" + defs::MODULE_PREINIT_DIR;
        if (!is_dir(src)) continue;
        if (!is_file(src + "/" + defs::PREINIT_CONF)) {
            status.push_back({id, STATE_ERROR, "preinit.conf not found"});
            continue;
        }
        Candidate c;
        c.id = id;
        c.src = src;
        try {
            scan(src, "", c.entries);
        } catch (const Error& e) {
            status.push_back({id, STATE_ERROR, e.what()});
            continue;
        }
        c.need = usage(c.entries);
        candidates.push_back(std::move(c));
    }

    if (candidates.empty()) {
        remove_all(ksu_dir);
        write_status(status);
        return true;
    }

    std::string preinit_dir = ksu_dir + "/" + defs::PREINIT_SUBDIR;
    mkdirs(ksu_dir, 0755);
    secure(ksu_dir, 0755, con);
    mkdirs(preinit_dir, 0755);
    secure(preinit_dir, 0755, con);

    uint64_t free_space = available(ksu_dir) + disk_usage(preinit_dir);
    uint64_t budget = std::min(LIMIT_TOTAL, free_space > RESERVE ? free_space - RESERVE : 0);
    uint64_t used = 2 * BLOCK;

    std::vector<Candidate> fit;
    for (auto& c : candidates) {
        if (used + c.need > budget) {
            status.push_back({c.id, STATE_NO_SPACE, "need " + std::to_string(c.need) + ", available " +
                                                         std::to_string(budget > used ? budget - used : 0)});
            continue;
        }
        used += c.need;
        fit.push_back(std::move(c));
    }

    std::vector<std::string> wanted;
    for (const auto& c : fit) wanted.push_back(c.id);
    bool changed = false;
    remove_unlisted(preinit_dir, wanted, changed);

    std::vector<std::string> keep;
    for (const auto& c : fit) {
        std::string dst = preinit_dir + "/" + c.id;
        bool unchanged = is_dir(dst) && same_copy(dst, c.src, c.entries);
        if (!unchanged) {
            if (!install(preinit_dir, c.id, c.src, c.entries, con)) {
                status.push_back({c.id, STATE_ERROR, "install failed"});
                continue;
            }
            changed = true;
        }
        keep.push_back(c.id);
        status.push_back({c.id, STATE_OK, ""});
    }
    remove_unlisted(preinit_dir, keep, changed);

    if (changed) unlink(bootcount_path().c_str());

    std::sort(status.begin(), status.end(), [](const Status& a, const Status& b) { return a.id < b.id; });
    write_status(status);
    return true;
}

void on_boot_completed() {
    uint64_t count = read_bootcount();
    if (count > BOOTCOUNT_LIMIT) {
        klog("preinit was disabled after %llu failed boots, delete %s to retry",
             static_cast<unsigned long long>(BOOTCOUNT_LIMIT), bootcount_path().c_str());
        return;
    }
    if (count > 0) unlink(bootcount_path().c_str());
}

std::map<std::string, std::string> module_states() {
    std::map<std::string, std::string> out;
    std::string content;
    if (!read_file_to_string(defs::PREINIT_STATUS_PATH, content)) return out;
    bool bootloop = read_bootcount() > BOOTCOUNT_LIMIT;
    for (const auto& line : split(content, '\n')) {
        auto parts = split(line, ' ');
        if (parts.size() < 2) continue;
        std::string state = parts[1];
        if (bootloop && state == STATE_OK) state = "bootloop";
        out[parts[0]] = state;
    }
    return out;
}

std::pair<std::string, std::string> status_of(const std::string& id) {
    std::string content;
    if (!read_file_to_string(defs::PREINIT_STATUS_PATH, content)) return {};
    for (const auto& line : split(content, '\n')) {
        auto parts = split(line, ' ');
        if (parts.size() >= 2 && parts[0] == id) {
            std::string msg = parts.size() >= 3 ? trim(parts[2]) : "";
            return {parts[1], msg};
        }
    }
    return {};
}

}  // namespace ncore::preinit
