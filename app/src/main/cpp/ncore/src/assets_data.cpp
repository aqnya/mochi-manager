// SPDX-License-Identifier: GPL-3.0-or-later
// 由 assets_embedded.S 提供的 .incbin 起止符号。
#include "assets.h"

#include <cstddef>

extern "C" {
extern const unsigned char ncore_installer_sh_start[];
extern const unsigned char ncore_installer_sh_end[];
extern const unsigned char ncore_banner_start[];
extern const unsigned char ncore_banner_end[];
extern const unsigned char ncore_busybox_start[];
extern const unsigned char ncore_busybox_end[];
}

namespace ncore::assets {

AssetData installer_sh() {
    return {ncore_installer_sh_start,
            static_cast<size_t>(ncore_installer_sh_end - ncore_installer_sh_start)};
}
AssetData banner() {
    return {ncore_banner_start, static_cast<size_t>(ncore_banner_end - ncore_banner_start)};
}
AssetData busybox() {
    return {ncore_busybox_start, static_cast<size_t>(ncore_busybox_end - ncore_busybox_start)};
}

}  // namespace ncore::assets
