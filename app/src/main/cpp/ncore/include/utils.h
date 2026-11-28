// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <string>
#include <vector>

namespace ncore::utils {

void umask(uint32_t mask);
bool ensure_clean_dir(const std::string& dir);
bool ensure_file_exists(const std::string& file);
bool ensure_dir_exists(const std::string& dir);
bool ensure_binary(const std::string& path, const std::vector<uint8_t>& contents, bool ignore_if_exist);

bool is_safe_mode();
bool has_magisk();
std::string runtime_mode();

// 切换挂载命名空间到 pid 1（init）。成功返回 true。
bool switch_mnt_ns(int pid);
void switch_cgroups();
void detach_process_group(bool use_init_pgrp);

// 安装 ncore 用户态组件（install 命令）。
bool install();

// antibrick 计数。
uint64_t write_antibrick_count(uint64_t value, bool increment);
bool check_antibrick_count(uint64_t threshold);
void on_boot_completed_antibrick();

}  // namespace ncore::utils
