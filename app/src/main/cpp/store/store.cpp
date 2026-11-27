// SPDX-License-Identifier: GPL-3.0-or-later
//
// libstore 实现，见 store.h 的格式说明与 Rust 绑定示例。

#include "store.h"

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>
#include <algorithm>
#include <string>
#include <vector>

namespace {

constexpr uint8_t kMagic[8] = {'N', 'K', 'S', 'U', 'P', 'R', 'F', 0};
constexpr uint32_t kVersion = 1;
constexpr size_t kHeaderSize = 24;
constexpr size_t kIndexSize = 16;

struct ProfileEntry {
    uint32_t uid = 0;
    uint32_t root_uid = 0;
    uint64_t caps = 0;
    int32_t ns = 0;
    std::string domain;
};

struct FeatureEntry {
    int32_t id = 0;
    uint64_t value = 0;
};

struct Store {
    std::vector<ProfileEntry> profiles;
    std::vector<FeatureEntry> features;
};

void put_le32(uint8_t* buf, uint32_t v) {
    buf[0] = static_cast<uint8_t>(v);
    buf[1] = static_cast<uint8_t>(v >> 8);
    buf[2] = static_cast<uint8_t>(v >> 16);
    buf[3] = static_cast<uint8_t>(v >> 24);
}

void put_le64(uint8_t* buf, uint64_t v) {
    for (int i = 0; i < 8; ++i) buf[i] = static_cast<uint8_t>(v >> (8 * i));
}

uint32_t get_le32(const uint8_t* buf) {
    return static_cast<uint32_t>(buf[0]) | static_cast<uint32_t>(buf[1]) << 8 |
           static_cast<uint32_t>(buf[2]) << 16 | static_cast<uint32_t>(buf[3]) << 24;
}

uint64_t get_le64(const uint8_t* buf) {
    uint64_t v = 0;
    for (int i = 7; i >= 0; --i) v = v << 8 | buf[i];
    return v;
}

constexpr uint32_t kWireVarint = 0;
constexpr uint32_t kWireLen = 2;

void write_varint(std::vector<uint8_t>& out, uint64_t v) {
    while (v >= 0x80) {
        out.push_back(static_cast<uint8_t>(v & 0x7F) | 0x80);
        v >>= 7;
    }
    out.push_back(static_cast<uint8_t>(v));
}

bool read_varint(const uint8_t* data, size_t len, size_t& pos, uint64_t& out) {
    uint64_t result = 0;
    int shift = 0;
    while (shift < 64) {
        if (pos >= len) return false;
        const uint8_t b = data[pos++];
        result |= static_cast<uint64_t>(b & 0x7F) << shift;
        if ((b & 0x80) == 0) {
            out = result;
            return true;
        }
        shift += 7;
    }
    return false;
}

// proto3 varint 字段：默认值不编码（与 prost 一致）
void write_varint_field(std::vector<uint8_t>& out, uint32_t field, uint64_t value) {
    if (value == 0) return;
    write_varint(out, static_cast<uint64_t>(field) << 3 | kWireVarint);
    write_varint(out, value);
}

void write_len_field(std::vector<uint8_t>& out, uint32_t field, const uint8_t* data, size_t len) {
    write_varint(out, static_cast<uint64_t>(field) << 3 | kWireLen);
    write_varint(out, len);
    out.insert(out.end(), data, data + len);
}

std::vector<uint8_t> encode_profile(const ProfileEntry& e) {
    std::vector<uint8_t> out;
    write_varint_field(out, 1, e.uid);
    write_varint_field(out, 2, e.root_uid);
    write_varint_field(out, 3, e.caps);
    // proto3 的 int32 按符号扩展成 64 位再编 varint（负数 10 字节）
    write_varint_field(out, 4, static_cast<uint64_t>(static_cast<int64_t>(e.ns)));
    if (!e.domain.empty()) {
        write_len_field(out, 5, reinterpret_cast<const uint8_t*>(e.domain.data()), e.domain.size());
    }
    return out;
}

std::vector<uint8_t> encode_feature(const FeatureEntry& e) {
    std::vector<uint8_t> out;
    write_varint_field(out, 1, static_cast<uint64_t>(static_cast<uint32_t>(e.id)));
    write_varint_field(out, 2, e.value);
    return out;
}

/// 通用 proto3 解析：逐字段回调，未知字段按 wire type 跳过。
/// 回调返回 false 表示格式错误。
template <typename OnField>
bool parse_fields(const uint8_t* data, size_t len, OnField on_field) {
    size_t pos = 0;
    while (pos < len) {
        uint64_t tag = 0;
        if (!read_varint(data, len, pos, tag)) return false;
        const uint32_t field = static_cast<uint32_t>(tag >> 3);
        const uint32_t wire = static_cast<uint32_t>(tag & 0x7);
        if (wire == kWireVarint) {
            uint64_t value = 0;
            if (!read_varint(data, len, pos, value)) return false;
            if (!on_field(field, wire, &value, nullptr, 0)) return false;
        } else if (wire == kWireLen) {
            uint64_t l = 0;
            if (!read_varint(data, len, pos, l)) return false;
            if (pos + l > len) return false;
            if (!on_field(field, wire, nullptr, data + pos, static_cast<size_t>(l))) return false;
            pos += static_cast<size_t>(l);
        } else {
            return false; // 不认识的 wire type
        }
    }
    return true;
}

bool decode_profile(const uint8_t* data, size_t len, ProfileEntry& out) {
    return parse_fields(data, len, [&](uint32_t field, uint32_t wire, const uint64_t* v, const uint8_t* bytes, size_t blen) {
        switch (field) {
        case 1:
            if (wire != kWireVarint) return false;
            out.uid = static_cast<uint32_t>(*v);
            break;
        case 2:
            if (wire != kWireVarint) return false;
            out.root_uid = static_cast<uint32_t>(*v);
            break;
        case 3:
            if (wire != kWireVarint) return false;
            out.caps = *v;
            break;
        case 4:
            if (wire != kWireVarint) return false;
            out.ns = static_cast<int32_t>(static_cast<int64_t>(*v));
            break;
        case 5:
            if (wire != kWireLen) return false;
            out.domain.assign(reinterpret_cast<const char*>(bytes), blen);
            break;
        default:
            break; // 未知字段跳过（proto 前向兼容）
        }
        return true;
    });
}

bool decode_feature(const uint8_t* data, size_t len, FeatureEntry& out) {
    return parse_fields(data, len, [&](uint32_t field, uint32_t wire, const uint64_t* v, const uint8_t*, size_t) {
        switch (field) {
        case 1:
            if (wire != kWireVarint) return false;
            out.id = static_cast<int32_t>(static_cast<uint32_t>(*v));
            break;
        case 2:
            if (wire != kWireVarint) return false;
            out.value = *v;
            break;
        default:
            break;
        }
        return true;
    });
}

bool read_all(int fd, std::vector<uint8_t>& out) {
    uint8_t buf[65536];
    while (true) {
        const ssize_t n = read(fd, buf, sizeof(buf));
        if (n > 0) {
            out.insert(out.end(), buf, buf + n);
        } else if (n == 0) {
            return true;
        } else if (errno != EINTR) {
            return false;
        }
    }
}

int load_store(Store& store) {
    const int fd = open(NKSU_STORE_PATH, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        // 文件不存在 = 空表（与上游一致）
        return errno == ENOENT ? 0 : -errno;
    }
    std::vector<uint8_t> bytes;
    const bool ok = read_all(fd, bytes);
    const int close_rc = close(fd);
    if (!ok || close_rc != 0) return -EIO;

    if (bytes.empty()) return 0;

    if (bytes.size() < kHeaderSize || std::memcmp(bytes.data(), kMagic, sizeof(kMagic)) != 0) {
        return -EINVAL;
    }
    if (get_le32(&bytes[8]) != kVersion || get_le32(&bytes[20]) != 0) {
        return -EINVAL;
    }
    const size_t profile_count = get_le32(&bytes[12]);
    const size_t feature_count = get_le32(&bytes[16]);
    const size_t data_start = kHeaderSize + profile_count * kIndexSize + feature_count * kIndexSize;
    if (data_start > bytes.size()) return -EINVAL;

    store.profiles.clear();
    store.profiles.reserve(profile_count);
    for (size_t i = 0; i < profile_count; ++i) {
        const uint8_t* idx = &bytes[kHeaderSize + i * kIndexSize];
        const uint32_t key = get_le32(idx);
        const uint64_t offset = get_le64(idx + 4);
        const uint32_t len = get_le32(idx + 12);
        if (offset < data_start || offset + len > bytes.size()) return -EINVAL;
        ProfileEntry entry;
        if (!decode_profile(bytes.data() + offset, len, entry)) return -EINVAL;
        if (entry.uid != key) return -EINVAL; // 与上游一致：索引 key 必须等于条目 uid
        store.profiles.push_back(std::move(entry));
    }

    const size_t feature_index_start = kHeaderSize + profile_count * kIndexSize;
    store.features.clear();
    store.features.reserve(feature_count);
    for (size_t i = 0; i < feature_count; ++i) {
        const uint8_t* idx = &bytes[feature_index_start + i * kIndexSize];
        const uint32_t key = get_le32(idx);
        const uint64_t offset = get_le64(idx + 4);
        const uint32_t len = get_le32(idx + 12);
        if (offset < data_start || offset + len > bytes.size()) return -EINVAL;
        FeatureEntry entry;
        if (!decode_feature(bytes.data() + offset, len, entry)) return -EINVAL;
        if (static_cast<uint32_t>(entry.id) != key) return -EINVAL;
        store.features.push_back(std::move(entry));
    }
    return 0;
}

std::vector<uint8_t> encode_store(const Store& store) {
    std::vector<ProfileEntry> profiles = store.profiles;
    std::vector<FeatureEntry> features = store.features;
    // 稳定排序不需要，但保持原顺序即可；先编码载荷，再拼 header + 索引
    std::vector<std::vector<uint8_t>> profile_payloads;
    profile_payloads.reserve(profiles.size());
    for (const auto& e : profiles) profile_payloads.push_back(encode_profile(e));
    std::vector<std::vector<uint8_t>> feature_payloads;
    feature_payloads.reserve(features.size());
    for (const auto& e : features) feature_payloads.push_back(encode_feature(e));

    const size_t data_start = kHeaderSize + (profiles.size() + features.size()) * kIndexSize;
    size_t total = data_start;
    for (const auto& p : profile_payloads) total += p.size();
    for (const auto& p : feature_payloads) total += p.size();

    std::vector<uint8_t> out(total, 0);
    std::memcpy(out.data(), kMagic, sizeof(kMagic));
    put_le32(&out[8], kVersion);
    put_le32(&out[12], static_cast<uint32_t>(profiles.size()));
    put_le32(&out[16], static_cast<uint32_t>(features.size()));
    put_le32(&out[20], 0);

    size_t cursor = data_start;
    for (size_t i = 0; i < profiles.size(); ++i) {
        uint8_t* idx = &out[kHeaderSize + i * kIndexSize];
        put_le32(idx, profiles[i].uid);
        put_le64(idx + 4, cursor);
        put_le32(idx + 12, static_cast<uint32_t>(profile_payloads[i].size()));
        std::memcpy(&out[cursor], profile_payloads[i].data(), profile_payloads[i].size());
        cursor += profile_payloads[i].size();
    }
    const size_t feature_index_start = kHeaderSize + profiles.size() * kIndexSize;
    for (size_t i = 0; i < features.size(); ++i) {
        uint8_t* idx = &out[feature_index_start + i * kIndexSize];
        put_le32(idx, static_cast<uint32_t>(features[i].id));
        put_le64(idx + 4, cursor);
        put_le32(idx + 12, static_cast<uint32_t>(feature_payloads[i].size()));
        std::memcpy(&out[cursor], feature_payloads[i].data(), feature_payloads[i].size());
        cursor += feature_payloads[i].size();
    }
    return out;
}

/// 原子持久化：临时文件（0600）→ fsync → rename → 目录 fsync。
int persist(const Store& store) {
    const std::vector<uint8_t> bytes = encode_store(store);
    const std::string path = NKSU_STORE_PATH;
    const std::string tmp = path + ".tmp";

    unlink(tmp.c_str());
    const int fd = open(tmp.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
    if (fd < 0) return -errno;

    size_t off = 0;
    while (off < bytes.size()) {
        const ssize_t w = write(fd, bytes.data() + off, bytes.size() - off);
        if (w > 0) {
            off += static_cast<size_t>(w);
        } else if (w < 0 && errno == EINTR) {
            continue;
        } else {
            close(fd);
            unlink(tmp.c_str());
            return w < 0 ? -errno : -EIO;
        }
    }
    // open 的 mode 会过 umask，fchmod 钉死 0600
    if (fchmod(fd, 0600) != 0 || fsync(fd) != 0) {
        close(fd);
        unlink(tmp.c_str());
        return -EIO;
    }
    if (close(fd) != 0) {
        unlink(tmp.c_str());
        return -EIO;
    }
    if (rename(tmp.c_str(), path.c_str()) != 0) {
        unlink(tmp.c_str());
        return -errno;
    }
    // 目录 fsync 失败只算「不保险」，不算失败（上游仅告警）
    const size_t slash = path.rfind('/');
    const std::string dir = slash == std::string::npos ? "." : path.substr(0, slash);
    const int dir_fd = open(dir.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (dir_fd >= 0) {
        fsync(dir_fd);
        close(dir_fd);
    }
    return 0;
}

}  // namespace

extern "C" {

int32_t nksu_store_upsert(const nksu_profile* entry) {
    if (entry == nullptr) return -EINVAL;
    Store store;
    int rc = load_store(store);
    if (rc != 0) return rc;

    ProfileEntry target;
    target.uid = entry->uid;
    target.root_uid = entry->root_uid;
    target.caps = entry->caps;
    target.ns = entry->ns;
    target.domain = entry->domain;

    bool replaced = false;
    for (auto& slot : store.profiles) {
        if (slot.uid != target.uid) continue;
        replaced = true;
        // 与现有条目完全一致则不重写文件（对齐上游 save_profile 的 no-change 分支）
        if (slot.root_uid == target.root_uid && slot.caps == target.caps && slot.ns == target.ns &&
            slot.domain == target.domain) {
            return 0;
        }
        slot = std::move(target);
        break;
    }
    if (!replaced) store.profiles.push_back(std::move(target));
    return persist(store);
}

int32_t nksu_store_delete(uint32_t uid) {
    Store store;
    int rc = load_store(store);
    if (rc != 0) return rc;

    const size_t before = store.profiles.size();
    store.profiles.erase(
        std::remove_if(store.profiles.begin(), store.profiles.end(),
                       [uid](const ProfileEntry& e) { return e.uid == uid; }),
        store.profiles.end());
    if (store.profiles.size() == before) return 0; // 不存在，幂等成功
    return persist(store);
}

int32_t nksu_store_count(void) {
    Store store;
    int rc = load_store(store);
    if (rc != 0) return rc;
    return static_cast<int32_t>(store.profiles.size());
}

int32_t nksu_store_get(int32_t index, nksu_profile* out) {
    if (out == nullptr || index < 0) return -EINVAL;
    Store store;
    int rc = load_store(store);
    if (rc != 0) return rc;
    if (static_cast<size_t>(index) >= store.profiles.size()) return 1;
    const ProfileEntry& e = store.profiles[static_cast<size_t>(index)];
    out->uid = e.uid;
    out->root_uid = e.root_uid;
    out->caps = e.caps;
    out->ns = e.ns;
    std::strncpy(out->domain, e.domain.c_str(), NKSU_STORE_DOMAIN_MAX - 1);
    out->domain[NKSU_STORE_DOMAIN_MAX - 1] = '\0';
    return 0;
}

}  // extern "C"
