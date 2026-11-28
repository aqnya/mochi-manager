// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <map>
#include <string>

namespace ncore::config {

enum class ConfigType { Persist, Temp };

bool parse_bool_config(const std::string& value);
// 读取一个配置文件；文件不存在返回空 map。
std::map<std::string, std::string> load_config(const std::string& module_id, ConfigType type);
// 合并 persist + temp（temp 覆盖 persist）。
std::map<std::string, std::string> merge_configs(const std::string& module_id);
// 读取所有模块配置。
std::map<std::string, std::map<std::string, std::string>> get_all_module_configs();
bool clear_all_temp_configs();
bool clear_module_configs(const std::string& module_id);

}  // namespace ncore::config
