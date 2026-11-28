// SPDX-License-Identifier: GPL-3.0-or-later
#include "properties.h"

#include "common.h"

namespace ncore {

std::map<std::string, std::string> parse_properties(const std::string& content) {
    std::map<std::string, std::string> out;
    std::vector<std::string> lines = split(content, '\n');
    std::string pending;
    for (auto raw : lines) {
        if (!raw.empty() && raw.back() == '\r') raw.pop_back();
        // 续行：以奇数个反斜杠结尾。
        bool continued = false;
        size_t bs = 0;
        for (size_t i = raw.size(); i > 0 && raw[i - 1] == '\\'; --i) ++bs;
        if (bs % 2 == 1) {
            continued = true;
            raw.pop_back();
        }
        pending += raw;
        if (continued) continue;

        std::string line = trim(pending);
        pending.clear();
        if (line.empty() || line[0] == '#' || line[0] == '!') continue;

        // 找第一个未转义的 = 或 :。
        size_t sep = std::string::npos;
        for (size_t i = 0; i < line.size(); ++i) {
            if (line[i] == '\\') { ++i; continue; }
            if (line[i] == '=' || line[i] == ':') { sep = i; break; }
        }
        std::string key = sep == std::string::npos ? line : line.substr(0, sep);
        std::string value = sep == std::string::npos ? "" : line.substr(sep + 1);
        key = trim(key);
        value = trim(value);

        // 解析转义序列。
        std::string unesc;
        for (size_t i = 0; i < value.size(); ++i) {
            if (value[i] == '\\' && i + 1 < value.size()) {
                char n = value[++i];
                switch (n) {
                    case 'n': unesc += '\n'; break;
                    case 't': unesc += '\t'; break;
                    case 'r': unesc += '\r'; break;
                    default: unesc += n; break;
                }
            } else {
                unesc += value[i];
            }
        }
        if (!key.empty()) out[key] = unesc;
    }
    return out;
}

}  // namespace ncore
