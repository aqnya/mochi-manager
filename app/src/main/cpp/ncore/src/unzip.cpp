// SPDX-License-Identifier: GPL-3.0-or-later
// 最小 ZIP 读取器：支持 store(0) / deflate(8) / zstd(93) / xz(95)。
//
// APPNOTE 6.3.8 起 93 = Zstandard、95 = XZ。但打包器并不可靠：有工具把 zstd
// 数据写成 95（也有把 zstd 写成已废弃的 20）。所以选编码时以数据开头的
// magic 为准，method 只在 magic 认不出来时兜底。
// 两个解码器都静态编进来——NDK 既不导出 libzstd 也不导出 liblzma，
// 设备上的系统库也不保证存在：
//   - zstd：third_party/zstd 的单文件 decoder
//   - xz：  third_party/xz 的 xz-embedded（LZMA2 + BCJ，仅解码）
#include "unzip.h"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <numeric>
#include <zlib.h>
#include <zstd.h>
#include <zstd_errors.h>

#include "common.h"

// 与 third_party/xz/xz_config.h 保持一致：xz.h 只在 XZ_USE_CRC64 下才声明
// xz_crc64_init()，C 源文件通过 xz_config.h 拿到这个宏，C++ 这边得自己补。
#define XZ_USE_CRC64 1
#include "xz.h"

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

// 单个条目的解压产物上限，防止畸形 zip 用极小输入把内存撑爆。
constexpr uint64_t kMaxDecoded = 256ull << 20;
// xz 允许的最大字典。xz -9 是 64MiB，128MiB 已经足够宽松。
constexpr uint32_t kXzDictMax = 128u << 20;

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
    // （或中央目录起点）的距离。个别打包器给压缩条目写 0，没有这个兜底就只能
    // 拿 0 字节去解码。
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

enum class Codec { kUnknown, kStore, kDeflate, kZstd, kXz };

// xz-embedded 没有 ZSTD_getErrorName 那样的字符串接口，自己给个可读名。
const char* xz_ret_name(xz_ret ret) {
    switch (ret) {
        case XZ_OK: return "ok";
        case XZ_STREAM_END: return "stream end";
        case XZ_UNSUPPORTED_CHECK: return "unsupported check type";
        case XZ_MEM_ERROR: return "out of memory";
        case XZ_MEMLIMIT_ERROR: return "dictionary too large";
        case XZ_FORMAT_ERROR: return "not an xz stream";
        case XZ_OPTIONS_ERROR: return "unsupported xz options";
        case XZ_DATA_ERROR: return "corrupt xz data";
        case XZ_BUF_ERROR: return "buffer error";
    }
    return "unknown";
}

bool has_zstd_magic(const uint8_t* p, size_t n) {
    if (n < 4) return false;
    const uint32_t magic = rd32(p);
    if (magic == 0xFD2FB528u) return true;                     // 标准帧
    return (magic & 0xFFFFFFF0u) == 0x184D2A50u;               // skippable frame
}

bool has_xz_magic(const uint8_t* p, size_t n) {
    static const uint8_t kMagic[6] = {0xFD, '7', 'z', 'X', 'Z', 0x00};
    return n >= sizeof(kMagic) && std::memcmp(p, kMagic, sizeof(kMagic)) == 0;
}

// method 是打包器写的声明，magic 才是事实。store/deflate 不会误判（它们的
// method 值稳定且数据没有固定 magic），压缩编码一律先看 magic。
Codec pick_codec(uint32_t method, const uint8_t* p, size_t n) {
    if (method == 0) return Codec::kStore;
    if (method == 8) return Codec::kDeflate;
    if (has_zstd_magic(p, n)) return Codec::kZstd;
    if (has_xz_magic(p, n)) return Codec::kXz;
    if (method == 20 || method == 93) return Codec::kZstd;     // 20 是废弃的 zstd 编号
    if (method == 95) return Codec::kXz;
    return Codec::kUnknown;
}

// 压缩数据区长度。中央目录的 size 不一定可信：zip64 写 0xFFFFFFFF，
// data descriptor 场景有时写 0，个别打包器干脆写 0。逐级回退。
uint64_t resolve_comp_size(const std::vector<uint8_t>& data, const Entry& e, const uint8_t* lh,
                           size_t data_off) {
    const uint64_t avail = data.size() - data_off;
    auto usable = [&](uint64_t v) { return v > 0 && v <= avail; };
    if (usable(e.compressed_size)) return e.compressed_size;
    const uint32_t local_comp = rd32(lh + 18);
    if (usable(local_comp)) return local_comp;
    if (e.data_end > data_off && e.data_end - data_off <= avail) return e.data_end - data_off;
    return 0;
}

std::vector<uint8_t> initial_buffer(uint64_t hint, size_t fallback) {
    if (hint > 0 && hint <= kMaxDecoded) return std::vector<uint8_t>(static_cast<size_t>(hint));
    return std::vector<uint8_t>(fallback);
}

std::vector<uint8_t> inflate_decode(const uint8_t* in, size_t in_size, uint64_t hint,
                                    const std::string& name) {
    if (in_size > 0xFFFFFFFFull) throw Error("zip: deflate input too large for " + name);
    std::vector<uint8_t> out = initial_buffer(hint, 64 * 1024);
    z_stream zs{};
    if (inflateInit2(&zs, -MAX_WBITS) != Z_OK) throw Error("inflateInit2 failed");
    zs.next_in = const_cast<Bytef*>(in);
    zs.avail_in = static_cast<uInt>(in_size);
    size_t produced = 0;
    for (;;) {
        if (produced == out.size()) {
            if (out.size() >= kMaxDecoded) {
                inflateEnd(&zs);
                throw Error("zip: inflate output exceeds limit for " + name);
            }
            out.resize(out.size() * 2);
        }
        zs.next_out = out.data() + produced;
        zs.avail_out = static_cast<uInt>(out.size() - produced);
        const int rc = inflate(&zs, Z_NO_FLUSH);
        produced = zs.total_out;
        if (rc == Z_STREAM_END) break;
        if (rc != Z_OK && rc != Z_BUF_ERROR) {
            inflateEnd(&zs);
            throw Error("inflate failed for " + name + ": " + std::to_string(rc));
        }
        if (rc == Z_BUF_ERROR && zs.avail_in == 0) {
            inflateEnd(&zs);
            throw Error("zip: truncated deflate stream for " + name);
        }
    }
    inflateEnd(&zs);
    out.resize(produced);
    return out;
}

std::vector<uint8_t> zstd_decode(const uint8_t* in, size_t in_size, uint64_t hint,
                                 const std::string& name) {
    ZSTD_DStream* ds = ZSTD_createDStream();
    if (ds == nullptr) throw Error("zstd: create stream failed");
    size_t rc = ZSTD_initDStream(ds);
    if (ZSTD_isError(rc)) {
        ZSTD_freeDStream(ds);
        throw Error("zstd: init stream failed: " + std::string(ZSTD_getErrorName(rc)));
    }

    std::vector<uint8_t> out = initial_buffer(hint, 64 * 1024);
    size_t produced = 0;
    ZSTD_inBuffer ib{in, in_size, 0};
    for (;;) {
        if (produced == out.size()) {
            if (out.size() >= kMaxDecoded) {
                ZSTD_freeDStream(ds);
                throw Error("zstd: output exceeds limit for " + name);
            }
            out.resize(out.size() * 2);
        }
        ZSTD_outBuffer ob{out.data() + produced, out.size() - produced, 0};
        rc = ZSTD_decompressStream(ds, &ob, &ib);
        if (ZSTD_isError(rc)) {
            const int code = static_cast<int>(ZSTD_getErrorCode(rc));
            const std::string err = ZSTD_getErrorName(rc);
            ZSTD_freeDStream(ds);
            throw Error("zstd decompress failed for " + name + ": " + err + " (code " +
                        std::to_string(code) + ", in " + std::to_string(in_size) + "B)");
        }
        produced += ob.pos;
        if (rc == 0) break;  // 一帧解完；尾部可能是 data descriptor，忽略
        if (ob.pos == 0 && ib.pos >= ib.size) {
            ZSTD_freeDStream(ds);
            throw Error("zstd: truncated stream for " + name + " (in " + std::to_string(in_size) + "B)");
        }
    }
    ZSTD_freeDStream(ds);
    out.resize(produced);
    return out;
}

// xz-embedded 的 CRC 表不是编译期常量，必须先调 init，否则算出来的校验值
// 恒为错（表现为开头 12 字节的 stream header 就 XZ_DATA_ERROR）。
void xz_crc_init_once() {
    static const bool once = [] {
        xz_crc32_init();
        xz_crc64_init();
        return true;
    }();
    (void)once;
}

std::vector<uint8_t> xz_decode(const uint8_t* in, size_t in_size, uint64_t hint,
                               const std::string& name) {
    xz_crc_init_once();
    xz_dec* s = xz_dec_init(XZ_DYNALLOC, kXzDictMax);
    if (s == nullptr) throw Error("xz: create decoder failed");

    std::vector<uint8_t> out = initial_buffer(hint, 64 * 1024);
    size_t produced = 0;
    xz_buf b{};
    b.in = in;
    b.in_pos = 0;
    b.in_size = in_size;
    for (;;) {
        if (produced == out.size()) {
            if (out.size() >= kMaxDecoded) {
                xz_dec_end(s);
                throw Error("xz: output exceeds limit for " + name);
            }
            out.resize(out.size() * 2);
        }
        b.out = out.data() + produced;
        b.out_pos = 0;
        b.out_size = out.size() - produced;
        const xz_ret ret = xz_dec_run(s, &b);
        produced += b.out_pos;
        if (ret == XZ_STREAM_END) break;
        if (ret == XZ_OK || ret == XZ_BUF_ERROR) {
            // 输入吃完且没有产出，说明流被截断（或数据区长度算大了，多给的
            // 那截解析不出新流）。前者是真错误。
            if (b.in_pos >= b.in_size && b.out_pos == 0) {
                xz_dec_end(s);
                throw Error("xz: truncated stream for " + name + " (in " + std::to_string(in_size) +
                            "B)");
            }
            continue;
        }
        xz_dec_end(s);
        throw Error("xz decompress failed for " + name + ": " + xz_ret_name(ret) + " (code " +
                    std::to_string(static_cast<int>(ret)) + ", in " + std::to_string(in_size) + "B)");
    }
    xz_dec_end(s);
    out.resize(produced);
    return out;
}

// 从 data 中取出条目内容（定位 local header -> 压缩数据 -> 解码）。
std::vector<uint8_t> read_entry(const std::vector<uint8_t>& data, const Entry& e) {
    if (e.local_header_offset + 30 > data.size()) throw Error("zip local header truncated");
    const uint8_t* p = data.data() + e.local_header_offset;
    if (!(p[0] == 0x50 && p[1] == 0x4b && p[2] == 0x03 && p[3] == 0x04)) {
        throw Error("zip local header signature mismatch for " + e.name);
    }
    const uint16_t name_len = rd16(p + 26);
    const uint16_t extra_len = rd16(p + 28);
    const size_t data_off = e.local_header_offset + 30 + name_len + extra_len;
    if (data_off > data.size()) throw Error("zip data truncated for " + e.name);
    const uint8_t* compressed = data.data() + data_off;
    const size_t avail = data.size() - data_off;

    const uint64_t comp_size = resolve_comp_size(data, e, p, data_off);
    if (comp_size == 0) {
        throw Error("zip: bad compressed size for " + e.name + " (central=" +
                    std::to_string(e.compressed_size) + ", avail=" + std::to_string(avail) + ")");
    }
    const size_t n = static_cast<size_t>(comp_size);

    switch (pick_codec(e.method, compressed, avail)) {
        case Codec::kStore:
            return std::vector<uint8_t>(compressed, compressed + n);
        case Codec::kDeflate:
            return inflate_decode(compressed, n, e.uncompressed_size, e.name);
        case Codec::kZstd:
            return zstd_decode(compressed, n, e.uncompressed_size, e.name);
        case Codec::kXz:
            return xz_decode(compressed, n, e.uncompressed_size, e.name);
        case Codec::kUnknown:
        default: {
            char magic[16] = {0};
            if (avail >= 4) std::snprintf(magic, sizeof(magic), "0x%08x", rd32(compressed));
            throw Error("unsupported zip compression method: " + std::to_string(e.method) + " for " +
                        e.name + (avail >= 4 ? std::string(" (magic ") + magic + ")" : ""));
        }
    }
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
