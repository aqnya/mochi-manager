// SPDX-License-Identifier: GPL-3.0-or-later
// 持久化属性（persist.*）的后备存储，语义对齐 Magisk resetprop 的 -p/-P。
//
// 两种格式：
//   - Android 8+：/data/property/persistent_properties，init 的
//     PersistentProperties protobuf（repeated PersistentPropertyRecord，
//     记录按 name 排序，字段 name=1 / value=2）。
//   - 更老的布局：一个属性一个文件，/data/property/<name>，内容就是值。
//
// 我们只读写这一份存储，不改 init 的内存状态；调用方负责同时更新属性区。
#pragma once

#include <map>
#include <string>

namespace ncore::prop::persist {

// 读一个持久化属性。不存在返回 false。
bool read(const std::string &name, std::string *value);

// 写一个持久化属性（存在则覆盖）。
bool write(const std::string &name, const std::string &value, std::string *error);

// 删除一个持久化属性。本来就不存在时也返回 true。
bool remove(const std::string &name, std::string *error);

// 列出所有持久化属性。out 中已有的键不会被覆盖（内存里的实时值优先）。
bool list(std::map<std::string, std::string> *out, std::string *error);

}  // namespace ncore::prop::persist
