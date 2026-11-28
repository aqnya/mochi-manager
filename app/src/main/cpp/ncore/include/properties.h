// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <map>
#include <string>

namespace ncore {

// 解析 java .properties / module.prop 文本（处理 key=value、key:value、续行、转义）。
std::map<std::string, std::string> parse_properties(const std::string& content);

}  // namespace ncore
