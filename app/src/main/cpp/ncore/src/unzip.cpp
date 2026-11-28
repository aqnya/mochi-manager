// SPDX-License-Identifier: GPL-3.0-or-later
// 最小 ZIP 读取器：支持 store(0) / deflate(8) / zstd(95)。
// 95 是 Zstandard，部分模块作者用它压缩（体积比 deflate 小很多）；
// zstd 解码走 third_party/zstd 的单文件 decoder，不依赖系统的 libzstd。
#include "unzip.h"

#include <algorithm>
#include <cstring>
#include <numeric>
#include <zlib.h>
#include <zstd.h>
#include <zstd_errors.h>

#include "common.h"

namespace ncore::unzip {

namespace {
struct Entry {
    std::string name;
    uint32_t method = 0;
    uint64_t compressed_size = 0;
    uint64_t uncompressed_size = 0;
    uint64_t local_header_offset = 0;
    // 压缩数据区结束位置（下一条目 local header 或中央目录起点）。
    // 仅用于 compressed_size 字段不可用时推算长度。
    uint64_t data_end = 0;
};

uint16_t rd16(const uint8_t* p) { return static_cast<uint16_t>(p[0] | (p[1] << 8)); }
uint32_t rd32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}
uint64_t rd64(const uint8_t* p) {
    return static_cast<uint64_t>(rd32(p)) | (static_cast<uint64_t>(rd32(p + 4)) << 32);
}

// zip64：32 位字段为 0xFFFFFFFF 时，真实值放在 extra field（ID 0x0001）里，
// 顺序固定为 uncompressed / compressed / local-header-offset，
// 且只包含「对应字段确实是 0xFFFFFFFF」的那几项。
void apply_zip64_extra(Entry& e, const uint8_t* extra, size_t len) {
    size_t p = 0;
    while (p + 4 <= len) {
        const uint16_t id = rd16(extra + p);
        const uint16_t sz = rd16(extra + p + 2);
        p += 4;
        if (p + sz > len) return;
        if (id == 0x0001) {
            const uint8_t* q = extra + p;
            size_t rem = sz;
            auto take = [&](uint64_t& dst) {
                if (rem < 8) return;
                dst = rd64(q);
                q += 8;
                rem -= 8;
            };
            if (e.uncompressed_size == 0xFFFFFFFFu) take(e.uncompressed_size);
            if (e.compressed_size == 0xFFFFFFFFu) take(e.compressed_size);
            if (e.local_header_offset == 0xFFFFFFFFu) take(e.local_header_offset);
            return;
        }
        p += sz;
    }
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
        if (extra_len > 0 && pos + 46 + name_len + extra_len <= data.size()) {
            apply_zip64_extra(e, p + 46 + name_len, extra_len);
        }
        pos += 46 + name_len + extra_len + comment_len;
        entries.push_back(std::move(e));
    }

    // 为「大小字段不可用」的条目推算数据边界：从它的数据区到下一个 local header
    // （或中央目录起点）的距离。个别打包器给 zstd 条目写 0，没有这个兜底就只能
    // 拿 0 字节去解码，报 srcSize_wrong。
    std::vector<size_t> order(entries.size());
    std::iota(order.begin(), order.end(), 0);
    std::sort(order.begin(), order.end(), [&](size_t a, size_t b) {
        return entries[a].local_header_offset < entries[b].local_header_offset;
    });
    const uint64_t cd_start =
        (cd_offset == 0xFFFFFFFFu || cd_offset > data.size()) ? data.size() : cd_offset;
    for (size_t i = 0; i < order.size(); ++i) {
        Entry& e = entries[order[i]];
        e.data_end = (i + 1 < order.size()) ? entries[order[i + 1]].local_header_offset : cd_start;
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
    } else if (e.method == 95) {
        // zstd。不信任 zip 记录的大小：zip64 会把字段写成 0xFFFFFFFF，个别打包器
        // 干脆写 0，拿它算缓冲会得到 0 字节输入（srcSize_wrong）或 4GB 的 cap
        // （dstSize_tooSmall）。这里统一走流式解码，输出按需翻倍，
        // 任何 size 字段都不影响正确性。
        uint64_t comp_size = e.compressed_size;
        if (comp_size == 0 || comp_size == 0xFFFFFFFFull) {
            // 中央目录没给出可用值，退回 local header（data descriptor 时同样为 0）。
            const uint32_t local_comp = rd32(p + 18);
            if (local_comp != 0 && local_comp != 0xFFFFFFFFu) comp_size = local_comp;
        }
        if (comp_size == 0 || data_off + comp_size > data.size()) {
            // 还是拿不到/越界：用相邻条目偏移推算的数据区长度。
            if (e.data_end > data_off && e.data_end <= data.size()) {
                comp_size = e.data_end - data_off;
            }
        }
        if (comp_size == 0 || data_off + comp_size > data.size()) {
            throw Error("zstd: bad compressed size for " + e.name + " (central=" +
                        std::to_string(e.compressed_size) + ", local=" + std::to_string(comp_size) + ")");
        }

        ZSTD_DStream* ds = ZSTD_createDStream();
        if (ds == nullptr) throw Error("zstd: create stream failed");
        size_t rc = ZSTD_initDStream(ds);
        if (ZSTD_isError(rc)) {
            ZSTD_freeDStream(ds);
            throw Error("zstd: init stream failed: " + std::string(ZSTD_getErrorName(rc)));
        }

        // 用 zip 的 uncompressed size 做初始容量（合理范围内），否则从 64KB 起。
        std::vector<uint8_t> buf;
        const uint64_t hint = e.uncompressed_size;
        buf.resize(hint > 0 && hint <= (64ull << 20) ? static_cast<size_t>(hint) : 64 * 1024);

        ZSTD_inBuffer in{compressed, static_cast<size_t>(comp_size), 0};
        size_t produced = 0;
        rc = 1;
        while (in.pos < in.size) {
            if (produced == buf.size()) buf.resize(buf.size() * 2);
            ZSTD_outBuffer ob{buf.data() + produced, buf.size() - produced, 0};
            rc = ZSTD_decompressStream(ds, &ob, &in);
            if (ZSTD_isError(rc)) {
                const int code = static_cast<int>(ZSTD_getErrorCode(rc));
                const std::string name = ZSTD_getErrorName(rc);
                ZSTD_freeDStream(ds);
                throw Error("zstd decompress failed for " + e.name + ": " + name + " (code " +
                            std::to_string(code) + ", in " + std::to_string(comp_size) + "B)");
            }
            produced += ob.pos;
            if (rc == 0) break;  // 一帧解完；尾部可能是 data descriptor，忽略
            if (ob.pos == 0 && in.pos == in.size) break;
        }
        ZSTD_freeDStream(ds);
        if (rc != 0) {
            throw Error("zstd: truncated stream for " + e.name + " (in " + std::to_string(comp_size) +
                        "B)");
        }
        buf.resize(produced);
        out = std::move(buf);
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
