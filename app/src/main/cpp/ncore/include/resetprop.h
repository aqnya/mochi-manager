// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <string>
#include <vector>

namespace ncore::resetprop {

// resetprop multicall entry (argv[0] 应为 "resetprop")。正常返回后由调用方决定退出码。
// 返回进程退出码：0 成功，1 一般错误，2 等待超时。
int resetprop_main(const std::vector<std::string>& args);

// 加载 system.prop 文件（等价 resetprop -n --file），供模块启动阶段使用。
bool load_system_prop_file(const std::string& path);

}  // namespace ncore::resetprop
