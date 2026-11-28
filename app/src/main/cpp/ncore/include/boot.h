// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

namespace ncore::boot {

// post-fs-data 阶段：引导扫描、清理、恢复上下文、执行模块脚本。
bool on_post_fs_data();
// service 阶段：执行 service 脚本（不等待）。
void on_services();
// boot-completed 阶段：preinit 收尾、store 加载、上报内核、清除防砖计数。
void on_boot_completed();

}  // namespace ncore::boot
