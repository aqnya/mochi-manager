// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <cstdint>
#include <map>
#include <string>
#include <utility>
#include <vector>

namespace ncore::preinit {

// 授权一个路径：chown root:root、chmod、设置 SELinux context。
bool secure(const std::string& path, uint32_t mode, const std::string& con);

// 让 <ksu_dir>/preinit/ 镜像 modules 的 preinit 目录。
// modules: id -> module 目录（下一次启动启用的模块）。
bool sync(const std::string& ksu_dir, const std::vector<std::string>& stale_dirs,
          const std::map<std::string, std::string>& modules, const std::string& con);

void on_boot_completed();
// 读取 preinit 状态：id -> state。
std::map<std::string, std::string> module_states();
// 读取单个模块的 (state, message)。
std::pair<std::string, std::string> status_of(const std::string& id);

}  // namespace ncore::preinit
