// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <cctype>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <stdexcept>
#include <string>
#include <sys/stat.h>
#include <system_error>
#include <vector>

namespace ncore {

// 轻量错误类型，携带 errno 或纯文本信息，对齐 anyhow 的 with_context 语义。
class Error : public std::runtime_error {
   public:
    explicit Error(const std::string& msg) : std::runtime_error(msg) {}
    Error(const std::string& ctx, const std::string& msg) : std::runtime_error(ctx + ": " + msg) {}
};

// 基于 errno 的错误（带上下文）。
inline Error sys_error(const std::string& ctx) {
    return Error(ctx, std::strerror(errno));
}

// 读取小文件到字节串；失败抛 Error。
std::vector<uint8_t> read_file(const std::string& path);
// 读取小文件为字符串；失败返回 false。
bool read_file_to_string(const std::string& path, std::string& out);
// 写文件（覆盖）；失败抛 Error。
void write_file(const std::string& path, const std::string& content);
void write_file_bytes(const std::string& path, const std::vector<uint8_t>& data);
// 目录/文件存在性。
bool path_exists(const std::string& path);
bool is_dir(const std::string& path);
bool is_file(const std::string& path);
bool is_symlink(const std::string& path);
// 递归创建目录（mode）。成功返回 true。
bool mkdirs(const std::string& path, mode_t mode = 0755);
// 递归删除目录。
bool remove_dir_all(const std::string& path);
// 原子写：临时文件 + fsync + rename。
bool write_atomic(const std::string& path, const std::string& content);
// 字符串分割（按单字符），保留空段由调用方处理。
std::vector<std::string> split(const std::string& s, char delim);
// 去空白。
std::string trim(const std::string& s);
// 转小写。
std::string to_lower(std::string s);
// 读系统属性（bionic __system_property_get）。
std::string getprop(const std::string& name);

}  // namespace ncore
