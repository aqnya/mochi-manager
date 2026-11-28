// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace ncore::kzcall {

// 找内核控制 fd（扫描 /proc/self/fd 中指向 [fmac_ctl] 的项），失败抛 Error。
int driver_fd();
// 执行一次 ioctl，data 既入又出（回写）。返回 ioctl 的返回值。
int call(uint32_t flag, std::vector<uint8_t>& data);
// 只入不出的 ioctl。
int call_in(uint32_t flag, const std::vector<uint8_t>& data);

void set_manager_uid(uint32_t uid);
int set_sepolicy(const std::vector<uint8_t>& payload);
void report_boot_complete();
void set_init_pgrp();
bool check_kernel_safemode();
void set_profile(uint32_t uid, uint64_t caps, const std::string& domain, int32_t ns, uint32_t root_uid);
void remove_profile(uint32_t uid);
void add_selinux_rule(const std::string& src, const std::string& tgt, const std::string& cls,
                      const std::string& perm, int32_t effect, bool invert);
bool list_profiles(std::string& out);
bool module_version(std::string& out);

}  // namespace ncore::kzcall
