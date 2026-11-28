// SPDX-License-Identifier: GPL-3.0-or-later
// 最小 ZIP 读取器：基于 zlib inflate，支持 store(0)/deflate(8)，
// 覆盖 Android 模块 zip 的绝大多数情况（对齐 Rust zip crate 的默认用途）。
#include "unzip.h"

#include <cstring>
#include <zlib.h>

#include "common.h"

namespace ncore::unzip {

namespace {
struct Entry {
    std::string name;
    uint32_t method = 0;
    uint64_t compressed_size = 0;
    uint64_t uncompressed_size = 0;
    uint64_t local_header_offset = 0;
};

uint16_t rd16(const uint8_t* p) { return static_cast<uint16_t>(p[0] | (p[1] << 8)); }
uint32_t rd32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

// 解析中央目录，返回所有条目。
std::vector<Entry> read_central_directory(const std::vector<uint8_t>& data) {
    // EOCD 签名 0x06054b50，从尾部向前找（最多 64KB + 22）。
    if (data.size() < 22) throw Error("zip too small");
    size_t search_start = data.size() > 66000 ? data.size() - 66000 : 0;
    size_t eocd = std::string::npos;
    for (size_t i = data.size() - 22 + 1; i > search_start; --i) {
        size_t pos = i - 1;
        if (data[pos] == 0x50 && data[pos + 1] == 0x4b && data[pos + 2] == 0x05 && data[pos + 3] == 0x06) {
            eocd = pos;
            break;
        }
    }
    if (eocd == std::string::npos) throw Error("zip EOCD not found");
    uint16_t count = rd16(data.data() + eocd + 10);
    uint32_t cd_offset = rd32(data.data() + eocd + 16);
    uint32_t pos = cd_offset;

    std::vector<Entry> entries;
    for (uint16_t i = 0; i < count; ++i) {
        if (pos + 46 > data.size()) throw Error("zip central directory truncated");
        const uint8_t* p = data.data() + pos;
        if (!(p[0] == 0x50 && p[1] == 0x4b && p[2] == 0x01 && p[3] == 0x02)) {
            throw Error("zip central directory signature mismatch");
        }
        Entry e;
        e.method = rd16(p + 10);
        e.compressed_size = rd32(p + 20);
        e.uncompressed_size = rd32(p + 24);
        uint16_t name_len = rd16(p + 28);
        uint16_t extra_len = rd16(p + 30);
        uint16_t comment_len = rd16(p + 32);
        e.local_header_offset = rd32(p + 42);
        if (pos + 46 + name_len > data.size()) throw Error("zip name truncated");
        e.name.assign(reinterpret_cast<const char*>(p + 46), name_len);
        pos += 46 + name_len + extra_len + comment_len;
        entries.push_back(std::move(e));
    }
    return entries;
}

// 从 data 中取出条目内容（定位 local header -> 压缩数据 -> inflate）。
std::vector<uint8_t> read_entry(const std::vector<uint8_t>& data, const Entry& e) {
    const uint8_t* p = data.data() + e.local_header_offset;
    if (e.local_header_offset + 30 > data.size()) throw Error("zip local header truncated");
    if (!(p[0] == 0x50 && p[1] == 0x4b && p[2] == 0x03 && p[3] == 0x04)) {
        throw Error("zip local header signature mismatch");
    }
    uint16_t name_len = rd16(p + 26);
    uint16_t extra_len = rd16(p + 28);
    size_t data_off = e.local_header_offset + 30 + name_len + extra_len;
    if (data_off + e.compressed_size > data.size()) throw Error("zip data truncated");
    const uint8_t* compressed = data.data() + data_off;

    std::vector<uint8_t> out;
    if (e.method == 0) {
        out.assign(compressed, compressed + e.compressed_size);
    } else if (e.method == 8) {
        out.resize(e.uncompressed_size);
        z_stream zs{};
        if (inflateInit2(&zs, -MAX_WBITS) != Z_OK) throw Error("inflateInit2 failed");
        zs.next_in = const_cast<Bytef*>(compressed);
        zs.avail_in = static_cast<uInt>(e.compressed_size);
        zs.next_out = out.data();
        zs.avail_out = static_cast<uInt>(out.size());
        int rc = inflate(&zs, Z_FINISH);
        inflateEnd(&zs);
        if (rc != Z_STREAM_END) throw Error("inflate failed: " + std::to_string(rc));
        out.resize(zs.total_out);
    } else {
        throw Error("unsupported zip compression method: " + std::to_string(e.method));
    }
    return out;
}

bool safe_rel_path(const std::string& name) {
    if (name.empty()) return false;
    if (name[0] == '/' || name.find('\\') != std::string::npos) return false;
    for (const auto& part : split(name, '/')) {
        if (part == "..") return false;
    }
    return true;
}
}  // namespace

std::vector<uint8_t> extract_to_memory(const std::string& zip_path, const std::string& entry) {
    auto data = read_file(zip_path);
    auto entries = read_central_directory(data);
    for (const auto& e : entries) {
        if (e.name == entry) return read_entry(data, e);
    }
    throw Error("zip entry not found: " + entry);
}

void extract_all(const std::string& zip_path, const std::string& target_dir) {
    auto data = read_file(zip_path);
    auto entries = read_central_directory(data);
    if (!mkdirs(target_dir, 0755)) throw Error("create target dir failed: " + target_dir);
    for (const auto& e : entries) {
        if (!safe_rel_path(e.name)) continue;
        bool is_dir = !e.name.empty() && e.name.back() == '/';
        std::string full = target_dir + "/" + e.name;
        if (is_dir) {
            mkdirs(full, 0755);
            continue;
        }
        size_t slash = full.rfind('/');
        if (slash != std::string::npos) mkdirs(full.substr(0, slash), 0755);
        auto content = read_entry(data, e);
        write_file_bytes(full, content);
    }
}

uint64_t uncompressed_size(const std::string& zip_path) {
    auto data = read_file(zip_path);
    auto entries = read_central_directory(data);
    uint64_t total = 0;
    for (const auto& e : entries) total += e.uncompressed_size;
    return total;
}

}  // namespace ncore::unzip
