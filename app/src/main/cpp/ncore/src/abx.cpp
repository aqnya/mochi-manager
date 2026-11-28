// SPDX-License-Identifier: GPL-3.0-or-later
// Android Binary XML (ABX) reader — ncore manager scan 的用户态移植。
#include "abx.h"

#include <algorithm>
#include <cstring>

namespace ncore {

namespace {
constexpr uint8_t TOK_START_DOCUMENT = 0, TOK_END_DOCUMENT = 1, TOK_START_TAG = 2, TOK_END_TAG = 3;
constexpr uint8_t TOK_TEXT = 4;
constexpr uint8_t TOK_ATTRIBUTE = 15;
constexpr uint8_t TYPE_NULL = 0x10, TYPE_STRING = 0x20, TYPE_STRING_INTERNED = 0x30;
constexpr uint8_t TYPE_BYTES_HEX = 0x40, TYPE_BYTES_BASE64 = 0x50;
constexpr uint8_t TYPE_INT = 0x60, TYPE_INT_HEX = 0x70;
constexpr uint8_t TYPE_LONG = 0x80, TYPE_LONG_HEX = 0x90;
constexpr uint8_t TYPE_FLOAT = 0xa0, TYPE_DOUBLE = 0xb0;
constexpr uint8_t TYPE_BOOL_TRUE = 0xc0, TYPE_BOOL_FALSE = 0xd0;
constexpr uint16_t INTERNED_SENTINEL = 0xffff;
constexpr size_t MAX_INTERNED = 512;
const uint8_t ABX_MAGIC[4] = {'A', 'B', 'X', 0};

struct Reader {
    const std::vector<uint8_t>& buf;
    size_t pos = 4;
    std::vector<std::vector<uint8_t>> interned;

    explicit Reader(const std::vector<uint8_t>& b) : buf(b) {}

    bool read_byte(uint8_t& out) {
        if (pos >= buf.size()) return false;
        out = buf[pos++];
        return true;
    }
    bool read_u16(uint16_t& out) {
        if (pos + 2 > buf.size()) return false;
        out = (static_cast<uint16_t>(buf[pos]) << 8) | buf[pos + 1];
        pos += 2;
        return true;
    }
    bool read_u64(uint64_t& out) {
        if (pos + 8 > buf.size()) return false;
        out = 0;
        for (int i = 0; i < 8; ++i) out = (out << 8) | buf[pos + i];
        pos += 8;
        return true;
    }
    bool read_utf(std::vector<uint8_t>& out) {
        uint16_t n = 0;
        if (!read_u16(n) || pos + n > buf.size()) return false;
        out.assign(buf.begin() + pos, buf.begin() + pos + n);
        pos += n;
        return true;
    }
    bool read_interned(std::vector<uint8_t>& out) {
        uint16_t r = 0;
        if (!read_u16(r)) return false;
        if (r == INTERNED_SENTINEL) {
            std::vector<uint8_t> s;
            if (!read_utf(s) || interned.size() >= MAX_INTERNED) return false;
            interned.push_back(std::move(s));
            out = interned.back();
        } else {
            if (r >= interned.size()) return false;
            out = interned[r];
        }
        return true;
    }
};

bool eq(const std::vector<uint8_t>& v, const char* s) {
    size_t n = std::strlen(s);
    return v.size() == n && std::memcmp(v.data(), s, n) == 0;
}

std::string truncate_to_string(const std::vector<uint8_t>& data, size_t max) {
    size_t n = std::min(data.size(), max);
    return std::string(reinterpret_cast<const char*>(data.data()), n);
}

}  // namespace

AbxOutcome find_package_by_cert(const std::vector<uint8_t>& buf,
                                const std::function<bool(const uint8_t*, size_t)>& match_fn,
                                Package& out) {
    if (buf.size() < 4 || std::memcmp(buf.data(), ABX_MAGIC, 4) != 0) return AbxOutcome::ParseError;
    Reader r(buf);

    int depth = 0, pkg_depth = -1, sigs_depth = -1;
    bool cert_checked = false, document_complete = false;
    std::vector<uint8_t> cert;
    bool has_cert = false;

    while (r.pos < r.buf.size()) {
        uint8_t ev = 0;
        if (!r.read_byte(ev)) break;
        uint8_t tok = ev & 0x0f, typ = ev & 0xf0;

        if (tok == TOK_START_DOCUMENT) continue;
        if (tok == TOK_END_DOCUMENT) {
            document_complete = (depth == 0);
            break;
        }
        if (tok == TOK_START_TAG) {
            std::vector<uint8_t> tag;
            if (!r.read_interned(tag)) return AbxOutcome::ParseError;
            depth++;
            bool is_sigs = pkg_depth > 0 && sigs_depth < 0 && depth == pkg_depth + 1 && eq(tag, "sigs");
            bool is_cert = sigs_depth > 0 && depth == sigs_depth + 1 && eq(tag, "cert");

            std::vector<uint8_t> name_attr, code_attr, nlib_attr;
            uint32_t shared_uid = 0, own_uid = 0;
            bool shared_seen = false, own_seen = false;

            while (true) {
                size_t save = r.pos;
                uint8_t ev2 = 0;
                if (!r.read_byte(ev2)) { r.pos = save; break; }
                if ((ev2 & 0x0f) != TOK_ATTRIBUTE) { r.pos = save; break; }
                uint8_t typ2 = ev2 & 0xf0;
                std::vector<uint8_t> aname;
                if (!r.read_interned(aname)) return AbxOutcome::ParseError;

                if (typ2 == TYPE_BYTES_HEX || typ2 == TYPE_BYTES_BASE64) {
                    uint16_t blen = 0;
                    if (!r.read_u16(blen) || r.pos + blen > r.buf.size()) return AbxOutcome::ParseError;
                    if (is_cert && eq(aname, "key")) {
                        cert.assign(r.buf.begin() + r.pos, r.buf.begin() + r.pos + blen);
                        has_cert = true;
                    }
                    r.pos += blen;
                } else if (typ2 == TYPE_STRING || typ2 == TYPE_STRING_INTERNED) {
                    std::vector<uint8_t> s;
                    bool ok = typ2 == TYPE_STRING ? r.read_utf(s) : r.read_interned(s);
                    if (!ok) return AbxOutcome::ParseError;
                    if (eq(aname, "name")) name_attr = s;
                    else if (eq(aname, "codePath")) code_attr = s;
                    else if (eq(aname, "nativeLibraryPath")) nlib_attr = s;
                } else if (typ2 == TYPE_INT || typ2 == TYPE_INT_HEX) {
                    if (r.pos + 4 > r.buf.size()) return AbxOutcome::ParseError;
                    uint32_t v = (static_cast<uint32_t>(r.buf[r.pos]) << 24) |
                                 (static_cast<uint32_t>(r.buf[r.pos + 1]) << 16) |
                                 (static_cast<uint32_t>(r.buf[r.pos + 2]) << 8) | r.buf[r.pos + 3];
                    r.pos += 4;
                    if (eq(aname, "sharedUserId")) { shared_uid = v; shared_seen = true; }
                    else if (eq(aname, "userId")) { own_uid = v; own_seen = true; }
                } else if (typ2 == TYPE_FLOAT) {
                    if (r.pos + 4 > r.buf.size()) return AbxOutcome::ParseError;
                    r.pos += 4;
                } else if (typ2 == TYPE_LONG || typ2 == TYPE_LONG_HEX || typ2 == TYPE_DOUBLE) {
                    uint64_t tmp = 0;
                    if (!r.read_u64(tmp)) return AbxOutcome::ParseError;
                } else if (typ2 == TYPE_BOOL_TRUE || typ2 == TYPE_BOOL_FALSE || typ2 == TYPE_NULL) {
                    // no-op
                } else {
                    return AbxOutcome::ParseError;
                }
            }

            bool is_pkg = pkg_depth < 0 && eq(tag, "package");
            if (is_pkg) {
                pkg_depth = depth;
                cert.clear();
                has_cert = false;
                cert_checked = false;
                out = Package{};
                out.name = truncate_to_string(name_attr, 255);
                out.code_path = truncate_to_string(code_attr, 255);
                out.native_lib_path = truncate_to_string(nlib_attr, 255);
                out.uid = shared_seen ? shared_uid : own_uid;
                out.has_uid = shared_seen || own_seen;
            }
            if (is_sigs) sigs_depth = depth;

            if (has_cert && !cert_checked) {
                cert_checked = true;
                if (match_fn(cert.data(), cert.size())) {
                    out.cert = cert;
                    return AbxOutcome::Found;
                }
            }
        } else if (tok == TOK_END_TAG) {
            std::vector<uint8_t> tmp;
            if (!r.read_interned(tmp)) return AbxOutcome::ParseError;
            if (depth == pkg_depth) { pkg_depth = -1; cert.clear(); has_cert = false; }
            if (depth == sigs_depth) sigs_depth = -1;
            depth--;
            if (depth < 0) return AbxOutcome::ParseError;
        } else if (tok == TOK_TEXT || tok == 5 || tok == 6 || tok == 7 || tok == 8 || tok == 9 || tok == 10) {
            if (typ == TYPE_STRING) {
                uint16_t n = 0;
                if (!r.read_u16(n) || r.pos + n > r.buf.size()) return AbxOutcome::ParseError;
                r.pos += n;
            } else if (typ == TYPE_STRING_INTERNED) {
                std::vector<uint8_t> tmp;
                if (!r.read_interned(tmp)) return AbxOutcome::ParseError;
            } else if (typ != TYPE_NULL) {
                return AbxOutcome::ParseError;
            }
        } else {
            return AbxOutcome::ParseError;
        }
    }

    return document_complete ? AbxOutcome::NotFound : AbxOutcome::ParseError;
}

}  // namespace ncore
