// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <map>
#include <string>

#include "module.h"

namespace ncore::metamodule {

bool is_metamodule(const std::map<std::string, std::string>& props);
bool has_metamodule();
std::string get_metamodule_path();
std::string get_metamodule_id();
// 返回 true 表示可以安装普通模块；false 表示被 metamodule 状态阻止。
// blocked 输出：true=metamodule 被禁用，false=其它不稳定状态。
bool check_install_safety(bool& blocked);
bool ensure_symlink(const std::string& module_path);
bool remove_symlink();
std::string get_install_script(bool is_metamodule, const std::string& installer_content,
                               const std::string& install_module_script);
bool exec_stage_script(const std::string& stage, const module::WaitDeadline& wait);
bool exec_mount_script(const std::string& module_dir);
bool exec_metauninstall_script(const std::string& module_id);

}  // namespace ncore::metamodule
