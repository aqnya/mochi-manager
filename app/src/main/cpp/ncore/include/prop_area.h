// SPDX-License-Identifier: GPL-3.0-or-later
// 属性读写的底层，来自上游 manager/app/src/main/cpp/prop/src/main.cpp。
//
// 和 bionic 公开 API 的区别：__system_property_set() 走 property_service，
// 只能在属性第一次写入前生效（init 会拒绝第二次修改 "ro." 属性）；
// 这里自己以读写方式 mmap /dev/__properties__/<context>，按 bionic 的
// writer 协议（trie + prop_info + serial）直接改原始布局，所以 ro.* 也能改。
#pragma once

#include <string>

namespace ncore::prop {

// 读属性。长值（> PROP_VALUE_MAX 的 ro.*）也能完整读出。
bool read(const std::string& name, std::string* value);

// 写属性。非 ro.* 默认交给 property_service（init 的 SELinux 检查与
// property trigger 照常生效）；ro.* 直写 property area。
// force_direct 为 true 时一律直写（等价 Magisk resetprop 的 -n）。
// 成功时会把值读回来核对一遍。
bool write(const std::string& name, const std::string& value, bool force_direct,
           std::string* error);

// 按名字排序输出 `[name]: [value]`。
void list();

}  // namespace ncore::prop
