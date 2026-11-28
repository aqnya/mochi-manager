// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <string>
#include <vector>

namespace ncore {

// 轻量 JSON 值：字符串或布尔。
struct JsonValue {
    enum class Kind { String, Bool } kind = Kind::String;
    std::string str;
    bool boolean = false;

    static JsonValue string_value(std::string s) {
        JsonValue v;
        v.kind = Kind::String;
        v.str = std::move(s);
        return v;
    }
    static JsonValue bool_value(bool b) {
        JsonValue v;
        v.kind = Kind::Bool;
        v.boolean = b;
        return v;
    }
};

using JsonObject = std::vector<std::pair<std::string, JsonValue>>;

// 生成 pretty JSON 数组，格式对齐 serde_json::to_string_pretty（2 空格缩进）。
std::string json_array_pretty(const std::vector<JsonObject>& objects);

}  // namespace ncore
