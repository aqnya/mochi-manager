// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace ncore::unzip {

// 读取 zip 内指定文件的未压缩内容（如 module.prop）。失败抛 Error。
std::vector<uint8_t> extract_to_memory(const std::string& zip_path, const std::string& entry);
// 解压整个 zip 到目标目录，保持相对路径；跳过绝对路径与 .. 穿越。失败抛 Error。
void extract_all(const std::string& zip_path, const std::string& target_dir);
// 计算所有条目的未压缩总大小。
uint64_t uncompressed_size(const std::string& zip_path);

}  // namespace ncore::unzip
