// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <cstdint>
#include <string>

namespace ncore::assets {

struct AssetData {
    const unsigned char* data;
    size_t size;
};

// 由 CMake 生成的 assets_data.cpp 提供。
AssetData installer_sh();
AssetData banner();
AssetData busybox();

constexpr const char* RESETPROP_PATH = "/data/adb/nksu/bin/resetprop";
constexpr const char* BUSYBOX_PATH = "/data/adb/nksu/bin/busybox";

// 把内嵌的 busybox/installer.sh 等解压到 BINARY_DIR（跳过 .ko）。
bool ensure_binaries(bool ignore_if_exist);

}  // namespace ncore::assets
