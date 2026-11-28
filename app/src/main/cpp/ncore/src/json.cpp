// SPDX-License-Identifier: GPL-3.0-or-later
#include "json.h"

namespace ncore {

namespace {
std::string escape(const std::string& s) {
    std::string out;
    out.reserve(s.size() + 2);
    for (unsigned char c : s) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            case '\b': out += "\\b"; break;
            case '\f': out += "\\f"; break;
            default:
                if (c < 0x20) {
                    static const char* hex = "0123456789abcdef";
                    out += "\\u00";
                    out += hex[(c >> 4) & 0xf];
                    out += hex[c & 0xf];
                } else {
                    out += static_cast<char>(c);
                }
        }
    }
    return out;
}
}  // namespace

std::string json_array_pretty(const std::vector<JsonObject>& objects) {
    if (objects.empty()) return "[]";
    std::string out = "[\n";
    for (size_t i = 0; i < objects.size(); ++i) {
        const auto& obj = objects[i];
        out += "  {\n";
        for (size_t j = 0; j < obj.size(); ++j) {
            const auto& kv = obj[j];
            out += "    \"" + escape(kv.first) + "\": ";
            if (kv.second.kind == JsonValue::Kind::Bool) {
                out += kv.second.boolean ? "true" : "false";
            } else {
                out += "\"" + escape(kv.second.str) + "\"";
            }
            if (j + 1 < obj.size()) out += ",";
            out += "\n";
        }
        out += "  }";
        if (i + 1 < objects.size()) out += ",";
        out += "\n";
    }
    out += "]";
    return out;
}

}  // namespace ncore
