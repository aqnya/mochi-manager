// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <string>

namespace ncore {

// 写内核日志（/dev/kmsg），对齐 Rust 版 klog。
void klog(const char* fmt, ...);

}  // namespace ncore
