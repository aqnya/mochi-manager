// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <string>

namespace ncore::restorecon {

constexpr const char* SYSTEM_CON = "u:object_r:system_file:s0";
constexpr const char* KSU_CON = "u:object_r:nksu_file:s0";
constexpr const char* UNLABEL_CON = "u:object_r:unlabeled:s0";

bool lsetfilecon(const std::string& path, const std::string& con);
bool lgetfilecon(const std::string& path, std::string& out);
bool setsyscon(const std::string& path);
bool restore_syscon(const std::string& dir);
bool restorecon();

}  // namespace ncore::restorecon
