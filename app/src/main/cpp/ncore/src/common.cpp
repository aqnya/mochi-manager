// SPDX-License-Identifier: GPL-3.0-or-later
#include "common.h"

#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <dirent.h>
#include <fcntl.h>
#include <sys/system_properties.h>
#include <sys/stat.h>
#include <unistd.h>

namespace ncore {

std::vector<uint8_t> read_file(const std::string& path) {
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) throw sys_error("open " + path);
    std::vector<uint8_t> data;
    char buf[8192];
    ssize_t n;
    while ((n = read(fd, buf, sizeof(buf))) > 0) {
        data.insert(data.end(), buf, buf + n);
    }
    int saved = errno;
    close(fd);
    if (n < 0) throw Error("read " + path, std::strerror(saved));
    return data;
}

bool read_file_to_string(const std::string& path, std::string& out) {
    try {
        auto data = read_file(path);
        out.assign(reinterpret_cast<const char*>(data.data()), data.size());
        return true;
    } catch (const Error&) {
        return false;
    }
}

void write_file_bytes(const std::string& path, const std::vector<uint8_t>& data) {
    int fd = open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (fd < 0) throw sys_error("open " + path);
    size_t off = 0;
    while (off < data.size()) {
        ssize_t n = write(fd, data.data() + off, data.size() - off);
        if (n <= 0) {
            int saved = errno;
            close(fd);
            throw Error("write " + path, std::strerror(saved));
        }
        off += static_cast<size_t>(n);
    }
    close(fd);
}

void write_file(const std::string& path, const std::string& content) {
    write_file_bytes(path, std::vector<uint8_t>(content.begin(), content.end()));
}

bool path_exists(const std::string& path) {
    struct stat st;
    return stat(path.c_str(), &st) == 0;
}

bool is_dir(const std::string& path) {
    struct stat st;
    return stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
}

bool is_file(const std::string& path) {
    struct stat st;
    return stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

bool is_symlink(const std::string& path) {
    struct stat st;
    return lstat(path.c_str(), &st) == 0 && S_ISLNK(st.st_mode);
}

bool mkdirs(const std::string& path, mode_t mode) {
    std::string cur;
    size_t i = 0;
    // 保留开头斜杠。
    if (!path.empty() && path[0] == '/') {
        cur = "/";
        i = 1;
    }
    while (i <= path.size()) {
        size_t slash = path.find('/', i);
        std::string part = path.substr(i, slash == std::string::npos ? std::string::npos : slash - i);
        if (!part.empty()) {
            if (cur != "/" && !cur.empty()) cur += "/";
            cur += part;
            if (mkdir(cur.c_str(), mode) != 0 && errno != EEXIST) return false;
        }
        if (slash == std::string::npos) break;
        i = slash + 1;
    }
    struct stat st;
    return stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
}

bool remove_dir_all(const std::string& path) {
    DIR* dir = opendir(path.c_str());
    if (dir != nullptr) {
        struct dirent* ent;
        while ((ent = readdir(dir)) != nullptr) {
            std::string name = ent->d_name;
            if (name == "." || name == "..") continue;
            std::string child = path + "/" + name;
            struct stat st;
            if (lstat(child.c_str(), &st) == 0 && S_ISDIR(st.st_mode)) {
                remove_dir_all(child);
            } else {
                unlink(child.c_str());
            }
        }
        closedir(dir);
    }
    return rmdir(path.c_str()) == 0;
}

bool write_atomic(const std::string& path, const std::string& content) {
    std::string tmp = path + ".tmp";
    try {
        write_file(tmp, content);
    } catch (const Error&) {
        return false;
    }
    int fd = open(tmp.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        fsync(fd);
        close(fd);
    }
    if (rename(tmp.c_str(), path.c_str()) != 0) {
        unlink(tmp.c_str());
        return false;
    }
    return true;
}

std::vector<std::string> split(const std::string& s, char delim) {
    std::vector<std::string> out;
    size_t start = 0;
    for (size_t i = 0; i <= s.size(); ++i) {
        if (i == s.size() || s[i] == delim) {
            out.push_back(s.substr(start, i - start));
            start = i + 1;
        }
    }
    return out;
}

std::string trim(const std::string& s) {
    size_t b = 0, e = s.size();
    while (b < e && std::isspace(static_cast<unsigned char>(s[b]))) ++b;
    while (e > b && std::isspace(static_cast<unsigned char>(s[e - 1]))) --e;
    return s.substr(b, e - b);
}

std::string to_lower(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return s;
}

std::string getprop(const std::string& name) {
    char value[92] = {};
    int len = __system_property_get(name.c_str(), value);
    if (len <= 0) return {};
    return std::string(value, static_cast<size_t>(len));
}

}  // namespace ncore
