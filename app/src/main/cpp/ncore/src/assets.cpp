// SPDX-License-Identifier: GPL-3.0-or-later
#include "assets.h"

#include <unistd.h>

#include "common.h"
#include "defs.h"

namespace ncore::assets {

bool ensure_binaries(bool ignore_if_exist) {
    struct Item {
        AssetData (*get)();
        const char* name;
        bool make_exec;
    };
    const Item items[] = {
        {busybox, "busybox", true},
    };

    if (!mkdirs(defs::BINARY_DIR, 0755)) return false;

    for (const auto& item : items) {
        AssetData data = item.get();
        std::string path = std::string(defs::BINARY_DIR) + item.name;
        if (ignore_if_exist && path_exists(path)) continue;
        if (!mkdirs(defs::BINARY_DIR, 0755)) return false;
        std::vector<uint8_t> bytes(data.data, data.data + data.size);
        write_file_bytes(path, bytes);
        if (item.make_exec) chmod(path.c_str(), 0755);
    }

    // resetprop -> ncore 软链接（resetprop 已内建进 ncore）。
    std::string link = RESETPROP_PATH;
    unlink(link.c_str());
    if (symlink(defs::DAEMON_PATH, link.c_str()) != 0) return false;
    return true;
}

}  // namespace ncore::assets
