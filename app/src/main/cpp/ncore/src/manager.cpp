// SPDX-License-Identifier: GPL-3.0-or-later
#include "manager.h"

#include <cstdlib>
#include <cstring>

#include "abx.h"
#include "common.h"
#include "defs.h"
#include "klog.h"
#include "kzcall.h"
#include "sha256.h"

namespace ncore::manager {

namespace {
int hex_nibble(char b) {
    if (b >= '0' && b <= '9') return b - '0';
    if (b >= 'a' && b <= 'f') return b - 'a' + 10;
    if (b >= 'A' && b <= 'F') return b - 'A' + 10;
    return -1;
}
}  // namespace

bool parse_cert_hash(const std::string& hex, CertHash& out) {
    if (hex.size() != CERT_HASH_LEN * 2) return false;
    for (size_t i = 0; i < CERT_HASH_LEN; ++i) {
        int hi = hex_nibble(hex[2 * i]);
        int lo = hex_nibble(hex[2 * i + 1]);
        if (hi < 0 || lo < 0) return false;
        out[i] = static_cast<uint8_t>((hi << 4) | lo);
    }
    return true;
}

bool verify_package_signature(const std::string& packages_xml, const CertHash& hash, Package& out) {
    struct stat st;
    if (stat(packages_xml.c_str(), &st) != 0) return false;
    uint64_t fsize = static_cast<uint64_t>(st.st_size);
    if (fsize < 4 || fsize > MAX_PACKAGES_XML_SIZE) return false;

    std::vector<uint8_t> buf;
    try {
        buf = read_file(packages_xml);
    } catch (const Error&) {
        return false;
    }

    AbxOutcome outcome = find_package_by_cert(
        buf,
        [&hash](const uint8_t* cert, size_t len) {
            auto digest = sha256(cert, len);
            return std::memcmp(digest.data(), hash.data(), CERT_HASH_LEN) == 0;
        },
        out);
    return outcome == AbxOutcome::Found;
}

static bool get_uid_from_packages_list(const std::string& packages_list, const std::string& name,
                                       uint32_t& out) {
    std::string buf;
    if (!read_file_to_string(packages_list, buf)) return false;
    std::vector<std::string> lines = split(buf, '\n');
    for (auto& line : lines) {
        auto parts = split(line, ' ');
        if (parts.empty() || parts[0] != name) continue;
        if (parts.size() >= 2) {
            char* end = nullptr;
            unsigned long uid = std::strtoul(parts[1].c_str(), &end, 10);
            if (*end == '\0') {
                out = static_cast<uint32_t>(uid);
                return true;
            }
        }
    }
    return false;
}

bool run_manager_scan(const std::string& cert_hash, const std::string& packages_xml_override) {
    CertHash hash;
    if (!parse_cert_hash(cert_hash, hash)) return false;

    std::string xml = packages_xml_override.empty() ? PACKAGES_XML_PATH : packages_xml_override;
    std::string list;
    size_t slash = xml.rfind('/');
    if (slash != std::string::npos) {
        list = xml.substr(0, slash) + "/packages.list";
    } else {
        list = PACKAGES_LIST_PATH;
    }

    Package pkg;
    if (!verify_package_signature(xml, hash, pkg)) return false;
    uint32_t uid = pkg.uid;
    if (!pkg.has_uid && !get_uid_from_packages_list(list, pkg.name, uid)) return false;

    klog("Manager package: %s uid=%u", pkg.name.c_str(), uid);
    kzcall::set_manager_uid(uid);
    return true;
}

bool resolve_cert_hash(const std::string& cli, std::string& out) {
    std::string v = trim(cli);
    if (!v.empty()) {
        out = v;
        return true;
    }
    std::string pinned = trim(defs::MANAGER_CERT_HASH);
    if (!pinned.empty()) {
        out = pinned;
        return true;
    }
    return false;
}

void run_boot_scan() {
    std::string hash;
    if (!resolve_cert_hash("", hash)) {
        klog("manager scan skipped: no cert hash");
        return;
    }
    if (run_manager_scan(hash, "")) {
        klog("manager authorized through the kernel");
    } else {
        klog("manager scan failed");
    }
}

}  // namespace ncore::manager
