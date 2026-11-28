// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <string>

namespace ncore::sepolicy {

// 解析并应用一条/一批 sepolicy 规则（非严格模式，解析失败的行跳过）。
bool live_patch(const std::string& policy);
// 从文件读取并应用。
bool apply_file(const std::string& path);
// 严格校验规则合法性（不应用）；path 存在时读文件，否则当作内联规则。
bool check_rule(const std::string& policy);

}  // namespace ncore::sepolicy
