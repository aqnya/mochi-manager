// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

namespace ncore::su {

// 内核把 /system/bin/su 的 execve 重定向到本程序（argv[0] 保持为 "su"）。
// 解析 su 参数、切换身份/上下文后 exec 目标 shell；正常情况不返回。
void root_shell(int argc, char** argv);

}  // namespace ncore::su
