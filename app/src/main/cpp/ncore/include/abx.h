// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <cstdint>
#include <functional>
#include <string>
#include <vector>

namespace ncore {

struct Package {
    std::vector<uint8_t> cert;
    std::string name;
    uint32_t uid = 0;
    bool has_uid = false;
    std::string code_path;
    std::string native_lib_path;
};

enum class AbxOutcome { Found, NotFound, ParseError };

// 在 ABX (packages.xml) 中找第一个证书被 match_fn 接受的 <package>。
AbxOutcome find_package_by_cert(const std::vector<uint8_t>& buf,
                                const std::function<bool(const uint8_t*, size_t)>& match_fn,
                                Package& out);

}  // namespace ncore
