// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <chrono>
#include <functional>
#include <map>
#include <string>
#include <vector>

#include "json.h"

namespace ncore::module {

enum class ModuleType { All, Active, Updated };
enum class ScriptWait { NoWait, Forever, Until };

struct WaitDeadline {
    ScriptWait kind = ScriptWait::NoWait;
    std::chrono::steady_clock::time_point deadline{};
};

bool validate_module_id(const std::string& id);

// 遍历模块目录，对每个目录调用 fn（过滤由 type 决定）。返回是否全部成功。
bool foreach_module(ModuleType type, const std::function<bool(const std::string&)>& fn);

// 脚本执行环境。
std::map<std::string, std::string> get_common_script_envs(const std::string& module_id);

bool exec_script(const std::string& path, const WaitDeadline& wait);
bool exec_stage_script(const std::string& stage, const WaitDeadline& wait);
bool exec_common_scripts(const std::string& dir, const WaitDeadline& wait);

bool load_sepolicy_rule();
bool load_system_prop();
bool prune_modules();
bool disable_all_modules();

std::map<std::string, std::string> read_module_prop(const std::string& module_path);

// 各命令。
bool install_module(const std::string& zip);
bool undo_uninstall_module(const std::string& id);
bool uninstall_module(const std::string& id);
bool enable_module(const std::string& id);
bool disable_module(const std::string& id);
bool run_action(const std::string& id);
bool list_modules();

// 供其它模块调用的辅助。
const char* preinit_ksu_dir();
const char* preinit_con(const std::string& dir);
bool regenerate_preinit();
bool handle_updated_modules();

}  // namespace ncore::module
