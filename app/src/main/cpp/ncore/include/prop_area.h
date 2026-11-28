// SPDX-License-Identifier: GPL-3.0-or-later
// 属性读写的底层，来自上游 manager/app/src/main/cpp/prop/src/main.cpp。
//
// 和 bionic 公开 API 的区别：__system_property_set() 走 property_service，
// 只能在属性第一次写入前生效（init 会拒绝第二次修改 "ro." 属性）；
// 这里自己以读写方式 mmap /dev/__properties__/<context>，按 bionic 的
// writer 协议（trie + prop_info + serial）直接改原始布局，所以 ro.* 也能改。
#pragma once

#include <map>
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

// 删除属性：把 trie 节点的 prop 指针清零并递增 area serial，bionic 之后
// 就查不到它了（`__system_property_find` 返回 nullptr）。
// prune 为 true 时会顺带回收因此变空的 trie 节点。
bool remove(const std::string& name, bool prune, std::string* error);

// 属性所属的 SELinux context，就是 `/dev/__properties__/<context>` 的文件名
// （形如 `u:object_r:build_prop:s0`）。属性不存在时返回 false。
bool context_of(const std::string& name, std::string* context);

// 把当前所有属性读进 out（名字 -> 值），不改动任何状态。
bool snapshot(std::map<std::string, std::string>* out, std::string* error);

// 按名字排序输出 `[name]: [value]`；with_context 时输出
// `[name]: [context]`（等价 resetprop 的 -Z）。
void list(bool with_context = false);

// 紧凑 property area，回收被更新/删除遗弃的 prop_info 与长值。
// context 为空时处理 /dev/__properties__ 下所有 area，否则只处理
// `<context>` 那个。原地重写：文件大小不变，只把 bytes_used 降回去。
bool compact(const std::string& context_filter, std::string* error);

}  // namespace ncore::prop
