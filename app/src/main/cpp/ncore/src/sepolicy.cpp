// SPDX-License-Identifier: GPL-3.0-or-later
// SELinux policy rule parser + serializer — 对齐 neko/ncore/src/selinux/sepolicy.rs。
#include "sepolicy.h"

#include <cstdint>
#include <vector>

#include "common.h"
#include "kzcall.h"
#include "uapi/selinux.h"

namespace ncore::sepolicy {

namespace {

bool is_sepol_char(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
           (c >= '0' && c <= '9') || c == '_' || c == '-';
}

// 解析一个 sepolicy 对象：单个词 / { a b c } / *
// 返回消费的字符数（0 表示失败），并填充 out。
size_t parse_object(const std::string& s, std::vector<std::string>& out) {
    if (s.empty()) return 0;
    if (s[0] == '*') {
        out.push_back("*");
        return 1;
    }
    if (s[0] == '{') {
        size_t i = 1;
        std::string word;
        while (i < s.size() && s[i] != '}') {
            if (is_sepol_char(s[i])) {
                word += s[i];
            } else if (s[i] == ' ' || s[i] == '\t') {
                if (!word.empty()) { out.push_back(word); word.clear(); }
            } else {
                return 0;
            }
            ++i;
        }
        if (i >= s.size() || s[i] != '}') return 0;
        if (!word.empty()) out.push_back(word);
        if (out.empty()) return 0;
        return i + 1;
    }
    size_t i = 0;
    while (i < s.size() && is_sepol_char(s[i])) ++i;
    if (i == 0) return 0;
    out.push_back(s.substr(0, i));
    return i;
}

// 跳过空白，返回位置。
size_t skip_ws(const std::string& s, size_t pos) {
    while (pos < s.size() && (s[pos] == ' ' || s[pos] == '\t')) ++pos;
    return pos;
}

struct AtomicStatement {
    uint32_t cmd = 0;
    uint32_t subcmd = 0;
    std::vector<std::string> args;  // 最多 5 个，string 表示对象（"*" 通配，空串=None）
};

bool read_word(const std::string& s, size_t& pos, std::string& out) {
    pos = skip_ws(s, pos);
    size_t start = pos;
    while (pos < s.size() && is_sepol_char(s[pos])) ++pos;
    if (pos == start) return false;
    out = s.substr(start, pos - start);
    return true;
}

bool read_object(const std::string& s, size_t& pos, std::vector<std::string>& out) {
    pos = skip_ws(s, pos);
    size_t consumed = parse_object(s.substr(pos), out);
    if (consumed == 0) return false;
    pos += consumed;
    return true;
}

void push_atomic(std::vector<AtomicStatement>& out, uint32_t cmd, uint32_t subcmd,
                 const std::vector<std::string>& a, const std::vector<std::string>& b,
                 const std::vector<std::string>& c, const std::vector<std::string>& d,
                 const std::vector<std::string>& e) {
    AtomicStatement st;
    st.cmd = cmd;
    st.subcmd = subcmd;
    for (const auto* vec : {&a, &b, &c, &d, &e}) {
        if (vec->empty()) {
            st.args.push_back("");
        } else {
            for (const auto& v : *vec) st.args.push_back(v);
        }
    }
    out.push_back(std::move(st));
}

// 解析一条语句，展开为若干原子语句。成功返回 true。
bool parse_statement(const std::string& line, std::vector<AtomicStatement>& out) {
    size_t pos = 0;
    std::string op;
    if (!read_word(line, pos, op)) return false;

    // normal permission: allow/deny/auditallow/dontaudit
    if (op == "allow" || op == "deny" || op == "auditallow" || op == "dontaudit") {
        uint32_t subcmd = 0;
        if (op == "allow") subcmd = NKSU_SEPOLICY_SUBCMD_NORMAL_PERM_ALLOW;
        else if (op == "deny") subcmd = NKSU_SEPOLICY_SUBCMD_NORMAL_PERM_DENY;
        else if (op == "auditallow") subcmd = NKSU_SEPOLICY_SUBCMD_NORMAL_PERM_AUDITALLOW;
        else subcmd = NKSU_SEPOLICY_SUBCMD_NORMAL_PERM_DONTAUDIT;
        std::vector<std::string> src, tgt, cls, perm;
        if (!read_object(line, pos, src) || !read_object(line, pos, tgt) ||
            !read_object(line, pos, cls) || !read_object(line, pos, perm)) return false;
        for (const auto& s : src)
            for (const auto& t : tgt)
                for (const auto& c : cls)
                    for (const auto& p : perm)
                        push_atomic(out, NKSU_SEPOLICY_CMD_NORMAL_PERM, subcmd,
                                    {s}, {t}, {c}, {p}, {});
        return true;
    }
    // xperm
    if (op == "allowxperm" || op == "auditallowxperm" || op == "dontauditxperm") {
        uint32_t subcmd = 0;
        if (op == "allowxperm") subcmd = NKSU_SEPOLICY_SUBCMD_XPERM_ALLOW;
        else if (op == "auditallowxperm") subcmd = NKSU_SEPOLICY_SUBCMD_XPERM_AUDITALLOW;
        else subcmd = NKSU_SEPOLICY_SUBCMD_XPERM_DONTAUDIT;
        std::vector<std::string> src, tgt, cls, permset;
        std::string operation;
        if (!read_object(line, pos, src) || !read_object(line, pos, tgt) ||
            !read_object(line, pos, cls) || !read_word(line, pos, operation) ||
            !read_object(line, pos, permset)) return false;
        for (const auto& s : src)
            for (const auto& t : tgt)
                for (const auto& c : cls)
                    for (const auto& r : permset)
                        push_atomic(out, NKSU_SEPOLICY_CMD_XPERM, subcmd,
                                    {s}, {t}, {c}, {operation}, {r});
        return true;
    }
    // type state: permissive/enforce <type>
    if (op == "permissive" || op == "enforce") {
        uint32_t subcmd = op == "permissive" ? NKSU_SEPOLICY_SUBCMD_TYPE_STATE_PERMISSIVE
                                             : NKSU_SEPOLICY_SUBCMD_TYPE_STATE_ENFORCE;
        std::vector<std::string> types;
        if (!read_object(line, pos, types)) return false;
        for (const auto& t : types)
            push_atomic(out, NKSU_SEPOLICY_CMD_TYPE_STATE, subcmd, {t}, {}, {}, {}, {});
        return true;
    }
    // type <name> [attrs...] (default attr: domain)
    if (op == "type") {
        std::string name;
        if (!read_word(line, pos, name)) return false;
        std::vector<std::string> attrs;
        if (skip_ws(line, pos) >= line.size()) {
            attrs.push_back("domain");
        } else if (!read_object(line, pos, attrs)) {
            return false;
        }
        for (const auto& a : attrs)
            push_atomic(out, NKSU_SEPOLICY_CMD_TYPE, 0, {name}, {a}, {}, {}, {});
        return true;
    }
    // typeattribute/attradd <type> <attr>
    if (op == "typeattribute" || op == "attradd") {
        std::vector<std::string> types, attrs;
        if (!read_object(line, pos, types) || !read_object(line, pos, attrs)) return false;
        for (const auto& t : types)
            for (const auto& a : attrs)
                push_atomic(out, NKSU_SEPOLICY_CMD_TYPE_ATTR, 0, {t}, {a}, {}, {}, {});
        return true;
    }
    // attribute <attr>
    if (op == "attribute") {
        std::string attr;
        if (!read_word(line, pos, attr)) return false;
        push_atomic(out, NKSU_SEPOLICY_CMD_ATTR, 0, {attr}, {}, {}, {}, {});
        return true;
    }
    // type_transition/name_transition <src> <tgt> <class> <default> [object]
    if (op == "type_transition" || op == "name_transition") {
        std::string src, tgt, cls, def, obj;
        if (!read_word(line, pos, src) || !read_word(line, pos, tgt) ||
            !read_word(line, pos, cls) || !read_word(line, pos, def)) return false;
        std::string objstr;
        if (skip_ws(line, pos) < line.size() && read_word(line, pos, objstr)) obj = objstr;
        push_atomic(out, NKSU_SEPOLICY_CMD_TYPE_TRANSITION, 0,
                    {src}, {tgt}, {cls}, {def}, obj.empty() ? std::vector<std::string>{} : std::vector<std::string>{obj});
        return true;
    }
    // type_change/type_member <src> <tgt> <class> <default>
    if (op == "type_change" || op == "type_member") {
        uint32_t subcmd = op == "type_change" ? NKSU_SEPOLICY_SUBCMD_TYPE_CHANGE_CHANGE
                                              : NKSU_SEPOLICY_SUBCMD_TYPE_CHANGE_MEMBER;
        std::string src, tgt, cls, def;
        if (!read_word(line, pos, src) || !read_word(line, pos, tgt) ||
            !read_word(line, pos, cls) || !read_word(line, pos, def)) return false;
        push_atomic(out, NKSU_SEPOLICY_CMD_TYPE_CHANGE, subcmd,
                    {src}, {tgt}, {cls}, {def}, {});
        return true;
    }
    // genfscon <fs> <path> <context>
    if (op == "genfscon") {
        std::string fs, path, ctx;
        if (!read_word(line, pos, fs) || !read_word(line, pos, path) ||
            !read_word(line, pos, ctx)) return false;
        push_atomic(out, NKSU_SEPOLICY_CMD_GENFSCON, 0, {fs}, {path}, {ctx}, {}, {});
        return true;
    }
    return false;
}

size_t expected_argc(uint32_t cmd) {
    switch (cmd) {
        case NKSU_SEPOLICY_CMD_NORMAL_PERM:
        case NKSU_SEPOLICY_CMD_TYPE_CHANGE: return 4;
        case NKSU_SEPOLICY_CMD_XPERM:
        case NKSU_SEPOLICY_CMD_TYPE_TRANSITION: return 5;
        case NKSU_SEPOLICY_CMD_TYPE_STATE:
        case NKSU_SEPOLICY_CMD_ATTR: return 1;
        case NKSU_SEPOLICY_CMD_TYPE:
        case NKSU_SEPOLICY_CMD_TYPE_ATTR: return 2;
        case NKSU_SEPOLICY_CMD_GENFSCON: return 3;
        default: return 0;
    }
}

void encode_object(std::vector<uint8_t>& payload, const std::string& object) {
    // object == "" -> None, "*" -> All（两者都是 len=0）；其余写字节。
    size_t len = (object.empty() || object == "*") ? 0 : object.size();
    uint32_t l = static_cast<uint32_t>(len);
    payload.insert(payload.end(), reinterpret_cast<const uint8_t*>(&l),
                   reinterpret_cast<const uint8_t*>(&l) + 4);
    if (len > 0) payload.insert(payload.end(), object.begin(), object.end());
    payload.push_back(0);
}

bool serialize(const std::vector<AtomicStatement>& statements, std::vector<uint8_t>& payload) {
    for (const auto& st : statements) {
        size_t argc = expected_argc(st.cmd);
        if (argc == 0) return false;
        payload.insert(payload.end(), reinterpret_cast<const uint8_t*>(&st.cmd),
                       reinterpret_cast<const uint8_t*>(&st.cmd) + 4);
        payload.insert(payload.end(), reinterpret_cast<const uint8_t*>(&st.subcmd),
                       reinterpret_cast<const uint8_t*>(&st.subcmd) + 4);
        for (size_t i = 0; i < argc && i < st.args.size(); ++i) {
            encode_object(payload, st.args[i]);
        }
    }
    return true;
}

bool apply_rules(const std::string& policy, bool strict) {
    std::vector<AtomicStatement> statements;
    // 按 \n 和 ; 分段解析。
    std::string cur;
    auto flush = [&]() {
        std::string line = trim(cur);
        cur.clear();
        if (line.empty() || line[0] == '#') return;
        std::vector<AtomicStatement> parsed;
        if (!parse_statement(line, parsed) && strict) {
            throw Error("Failed to parse policy statement: " + line);
        }
        statements.insert(statements.end(), parsed.begin(), parsed.end());
    };
    for (char c : policy) {
        if (c == '\n' || c == ';') {
            flush();
        } else {
            cur += c;
        }
    }
    flush();

    if (statements.empty()) return true;
    std::vector<uint8_t> payload;
    if (!serialize(statements, payload)) return false;
    int applied = kzcall::set_sepolicy(payload);
    if (applied >= 0 && static_cast<size_t>(applied) < statements.size()) {
        if (strict) return false;
    }
    return true;
}
}  // namespace

bool live_patch(const std::string& policy) {
    try {
        return apply_rules(trim(policy), false);
    } catch (const Error&) {
        return false;
    }
}

bool apply_file(const std::string& path) {
    std::string input;
    if (!read_file_to_string(path, input)) return false;
    return live_patch(input);
}

bool check_rule(const std::string& policy) {
    std::string text = policy;
    if (path_exists(policy)) {
        if (!read_file_to_string(policy, text)) return false;
    }
    try {
        return apply_rules(trim(text), true);
    } catch (const Error&) {
        return false;
    }
}

}  // namespace ncore::sepolicy
