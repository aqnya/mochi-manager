// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <string>

namespace ncore::hot_install {

struct Request {
    bool valid = false;
    std::string run_script;
};

// 读取并删除 staged 目录中的 hot-install marker。
Request take_request(const std::string& staged_dir);
// 尝试立即应用；返回 false 表示回退到重启生效路径。
bool apply(const std::string& module_id, bool is_metamodule, const Request& request);
// 清理上次热安装替换留下的旧目录。
void clean_trash();

}  // namespace ncore::hot_install
