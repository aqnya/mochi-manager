// SPDX-License-Identifier: GPL-3.0-or-later
#include "kzcall.h"

#include <algorithm>
#include <cerrno>
#include <cstring>
#include <dirent.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include "common.h"
#include "uapi/ioctl.h"

namespace ncore::kzcall {

namespace {
constexpr const char* FMAC_CTL_NAME = "[fmac_ctl]";
int cached_fd = -1;

void put_u32(std::vector<uint8_t>& buf, size_t off, uint32_t v) {
    std::memcpy(buf.data() + off, &v, 4);
}
void put_u64(std::vector<uint8_t>& buf, size_t off, uint64_t v) {
    std::memcpy(buf.data() + off, &v, 8);
}
uint64_t get_u64(const std::vector<uint8_t>& buf, size_t off) {
    uint64_t v = 0;
    std::memcpy(&v, buf.data() + off, 8);
    return v;
}
void put_cstr(std::vector<uint8_t>& buf, size_t off, size_t max, const std::string& value) {
    if (max == 0) return;
    size_t len = value.size() < max - 1 ? value.size() : max - 1;
    std::memcpy(buf.data() + off, value.data(), len);
    buf[off + len] = 0;
}
std::string cstr_lossy(const std::vector<uint8_t>& buf) {
    auto it = std::find(buf.begin(), buf.end(), 0);
    return std::string(buf.begin(), it);
}

int scan_driver_fd() {
    DIR* dir = opendir("/proc/self/fd");
    if (dir == nullptr) return -1;
    struct dirent* ent;
    int found = -1;
    while ((ent = readdir(dir)) != nullptr) {
        char* end = nullptr;
        long num = std::strtol(ent->d_name, &end, 10);
        if (*end != '\0' || num < 0) continue;
        std::string link = "/proc/self/fd/" + std::string(ent->d_name);
        char target[512] = {};
        ssize_t n = readlink(link.c_str(), target, sizeof(target) - 1);
        if (n < 0) continue;
        target[n] = '\0';
        if (std::strstr(target, FMAC_CTL_NAME) != nullptr) {
            found = static_cast<int>(num);
            break;
        }
    }
    closedir(dir);
    return found;
}
}  // namespace

int driver_fd() {
    if (cached_fd >= 0) return cached_fd;
    int fd = scan_driver_fd();
    if (fd < 0) throw Error("could not retrieve the NekoSU control fd");
    cached_fd = fd;
    return fd;
}

int call(uint32_t flag, std::vector<uint8_t>& data) {
    int fd = driver_fd();
    std::vector<uint8_t> buffer(8 + data.size(), 0);
    uint32_t fl = flag, sz = static_cast<uint32_t>(data.size());
    std::memcpy(buffer.data(), &fl, 4);
    std::memcpy(buffer.data() + 4, &sz, 4);
    if (!data.empty()) std::memcpy(buffer.data() + 8, data.data(), data.size());

    int ret = ioctl(fd, IOC_CMD, buffer.data());
    if (ret < 0) throw sys_error("NekoSU ioctl " + std::to_string(flag));
    if (!data.empty()) std::memcpy(data.data(), buffer.data() + 8, data.size());
    return ret;
}

int call_in(uint32_t flag, const std::vector<uint8_t>& data) {
    int fd = driver_fd();
    std::vector<uint8_t> buffer(8 + data.size(), 0);
    uint32_t fl = flag, sz = static_cast<uint32_t>(data.size());
    std::memcpy(buffer.data(), &fl, 4);
    std::memcpy(buffer.data() + 4, &sz, 4);
    if (!data.empty()) std::memcpy(buffer.data() + 8, data.data(), data.size());
    int ret = ioctl(fd, IOC_CMD, buffer.data());
    if (ret < 0) throw sys_error("NekoSU ioctl " + std::to_string(flag));
    return ret;
}

void set_manager_uid(uint32_t uid) {
    std::vector<uint8_t> buf(4);
    put_u32(buf, 0, uid);
    call(IOC_SET_MANAGER_UID, buf);
}

int set_sepolicy(const std::vector<uint8_t>& payload) {
    if (payload.empty()) return 0;
    return call_in(IOC_SET_SEPOLICY, payload);
}

void report_boot_complete() {
    std::vector<uint8_t> buf;
    call(IOC_DISARM_SAFEMODE, buf);
}

void set_init_pgrp() {
    std::vector<uint8_t> buf;
    call(IOC_SET_INIT_PGRP, buf);
}

bool check_kernel_safemode() {
    std::vector<uint8_t> buf;
    int ret = call(IOC_GET_SAFEMODE, buf);
    return ret == 1;
}

void set_profile(uint32_t uid, uint64_t caps, const std::string& domain, int32_t ns, uint32_t root_uid) {
    std::vector<uint8_t> buf(FMAC_DATA_PROFILE, 0);
    put_u32(buf, FMAC_OFF_UID, uid);
    put_u64(buf, FMAC_OFF_CAPS, caps);
    put_cstr(buf, FMAC_OFF_DOMAIN, FMAC_OFF_NS - FMAC_OFF_DOMAIN, domain);
    put_u32(buf, FMAC_OFF_NS, static_cast<uint32_t>(ns));
    put_u32(buf, FMAC_OFF_ROOT_UID, root_uid);
    call(IOC_SET_PROFILE, buf);
}

void remove_profile(uint32_t uid) {
    std::vector<uint8_t> buf(4);
    put_u32(buf, 0, uid);
    call(IOC_REMOVE_PROFILE, buf);
}

void add_selinux_rule(const std::string& src, const std::string& tgt, const std::string& cls,
                      const std::string& perm, int32_t effect, bool invert) {
    std::vector<uint8_t> buf(FMAC_DATA_SELRULE, 0);
    put_cstr(buf, FMAC_OFF_UID, FMAC_OFF_TGT - FMAC_OFF_UID, src);
    put_cstr(buf, FMAC_OFF_TGT, FMAC_OFF_CLS - FMAC_OFF_TGT, tgt);
    put_cstr(buf, FMAC_OFF_CLS, FMAC_OFF_PERM - FMAC_OFF_CLS, cls);
    put_cstr(buf, FMAC_OFF_PERM, FMAC_OFF_EFFECT - FMAC_OFF_PERM, perm);
    put_u32(buf, FMAC_OFF_EFFECT, static_cast<uint32_t>(effect));
    put_u32(buf, FMAC_OFF_INVERT, invert ? 1 : 0);
    call(IOC_SEL_ADD_RULE, buf);
}

bool list_profiles(std::string& out) {
    try {
        std::vector<uint8_t> buf(NKSU_PROFILE_TEXT_MAX, 0);
        call(IOC_GET_PROFILES, buf);
        out = cstr_lossy(buf);
        return true;
    } catch (const Error&) {
        return false;
    }
}

bool module_version(std::string& out) {
    try {
        std::vector<uint8_t> buf(64, 0);
        call(IOC_GET_VERSION, buf);
        out = cstr_lossy(buf);
        return true;
    } catch (const Error&) {
        return false;
    }
}

}  // namespace ncore::kzcall
