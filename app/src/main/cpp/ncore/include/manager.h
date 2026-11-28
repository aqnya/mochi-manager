// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <array>
#include <cstdint>
#include <string>

#include "abx.h"

namespace ncore::manager {

constexpr size_t CERT_HASH_LEN = 32;
constexpr const char* PACKAGES_XML_PATH = "/data/system/packages.xml";
constexpr const char* PACKAGES_LIST_PATH = "/data/system/packages.list";
constexpr uint64_t MAX_PACKAGES_XML_SIZE = 8 * 1024 * 1024;

using CertHash = std::array<uint8_t, CERT_HASH_LEN>;

bool parse_cert_hash(const std::string& hex, CertHash& out);
// 在 packages.xml 中找签名匹配的包；成功返回 true。
bool verify_package_signature(const std::string& packages_xml, const CertHash& hash, Package& out);
// 完整扫描：校验签名 + 取 uid + IOC_SET_MANAGER_UID。
bool run_manager_scan(const std::string& cert_hash, const std::string& packages_xml_override);
// 启动阶段的 best-effort 扫描（错误只记录）。
void run_boot_scan();
// 解析命令行传入或编译期固定的 cert hash。
bool resolve_cert_hash(const std::string& cli, std::string& out);

}  // namespace ncore::manager
