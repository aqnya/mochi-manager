// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <array>
#include <cstdint>
#include <vector>

namespace ncore {

// 计算 SHA-256，返回 32 字节摘要。
std::array<uint8_t, 32> sha256(const uint8_t* data, size_t len);
inline std::array<uint8_t, 32> sha256(const std::vector<uint8_t>& data) {
    return sha256(data.data(), data.size());
}

}  // namespace ncore
