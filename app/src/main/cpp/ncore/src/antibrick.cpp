// SPDX-License-Identifier: GPL-3.0-or-later
// 防变砖计数：每次 post-fs-data 累加，连续超过阈值即进入安全模式；
// boot-complete 后清除。移植自 neko/ncore/src/boot/antibrick.rs。
#include "utils.h"

#include <cstdlib>
#include <unistd.h>

#include "common.h"
#include "defs.h"

namespace ncore::utils {

uint64_t write_antibrick_count(uint64_t value, bool increment) {
    uint64_t nv = value;
    if (increment) {
        std::string s;
        if (read_file_to_string(defs::ANTIBRICK_PATH, s)) {
            char* end = nullptr;
            unsigned long v = std::strtoul(trim(s).c_str(), &end, 10);
            if (*end == '\0') nv = static_cast<uint64_t>(v);
        }
        nv = nv >= UINT64_MAX ? UINT64_MAX : nv + 1;
    }
    size_t slash = std::string(defs::ANTIBRICK_PATH).rfind('/');
    if (slash != std::string::npos) mkdirs(std::string(defs::ANTIBRICK_PATH).substr(0, slash), 0755);
    std::string tmp = std::string(defs::ANTIBRICK_PATH) + ".tmp";
    try {
        write_file(tmp, std::to_string(nv));
    } catch (const Error&) {
        unlink(tmp.c_str());
        return nv;
    }
    if (rename(tmp.c_str(), defs::ANTIBRICK_PATH) != 0) unlink(tmp.c_str());
    return nv;
}

bool check_antibrick_count(uint64_t threshold) {
    std::string s;
    if (!read_file_to_string(defs::ANTIBRICK_PATH, s)) return false;
    char* end = nullptr;
    unsigned long v = std::strtoul(trim(s).c_str(), &end, 10);
    if (*end != '\0') return false;
    return static_cast<uint64_t>(v) >= threshold;
}

void on_boot_completed_antibrick() { unlink(defs::ANTIBRICK_PATH); }

}  // namespace ncore::utils
