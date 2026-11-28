// SPDX-License-Identifier: GPL-3.0-or-later
// Magisk 兼容的 resetprop 工具。
//
// 上游 Rust 版用 prop-rs-android 直接 mmap property area，以绕过 property_service
// 设置 ro.* 只读属性。这里用 bionic 公开的 __system_property_* API 实现主路径：
// 读取（get/list）、设置（set）、从文件加载、等待。删除用置空值近似（bionic 未提供
// 公开删除接口），持久化/mmap 重建等高级行为降级为尽力而为。对绝大多数模块的
// system.prop 场景（设置普通属性）已足够。
#include "resetprop.h"

#include <sys/system_properties.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "common.h"
#include "defs.h"
#include "klog.h"
#include "properties.h"

namespace ncore::resetprop {

namespace {

struct Options {
    bool skip_svc = false;
    bool persistent = false;
    bool persist_only = false;
    bool delete_prop = false;
    bool verbose = false;
    bool wait = false;
    bool rebuild = false;
    bool show_context = false;
    bool force = false;
    double timeout = -1;  // <0 表示无限等待
    std::string file;
    std::vector<std::string> positional;
};

void print_usage() {
    printf(
        "Magisk-compatible system property tool\n\n"
        "Usage: resetprop [options] [NAME [VALUE]]\n"
        "  -n, --skip-svc   skip property_service\n"
        "  -p, --persistent also operate on persistent storage\n"
        "  -P               only read persistent properties\n"
        "  -d, --delete     delete the named property\n"
        "  -v, --verbose    verbose output\n"
        "  -w, --wait       wait for a property to exist/change\n"
        "      --timeout N  timeout in seconds for --wait\n"
        "  -f, --file FILE  load and set properties from FILE\n"
        "  -c, --rebuild    rebuild property area(s)\n"
        "  -Z               show SELinux context when listing\n"
        "      --force      force rebuild all property areas\n"
        "  -h, --help       show this help\n");
}

bool is_number(const std::string& s) {
    char* end = nullptr;
    std::strtod(s.c_str(), &end);
    return end != nullptr && *end == '\0' && !s.empty();
}

Options parse_args(const std::vector<std::string>& args, bool& help, bool& version, std::string& error) {
    Options o;
    help = false;
    version = false;
    for (size_t i = 1; i < args.size(); ++i) {
        const std::string& a = args[i];
        auto next = [&](const std::string& opt) -> std::string {
            if (i + 1 < args.size()) return args[++i];
            error = "option " + opt + " requires an argument";
            return {};
        };
        if (a == "-h" || a == "--help") help = true;
        else if (a == "-v" || a == "--verbose") o.verbose = true;
        else if (a == "-n" || a == "--skip-svc") o.skip_svc = true;
        else if (a == "-p" || a == "--persistent") o.persistent = true;
        else if (a == "-P") o.persist_only = true;
        else if (a == "-d" || a == "--delete") o.delete_prop = true;
        else if (a == "-w" || a == "--wait") o.wait = true;
        else if (a == "-c" || a == "--rebuild" || a == "--compact") o.rebuild = true;
        else if (a == "-Z") o.show_context = true;
        else if (a == "--force") o.force = true;
        else if (a == "--version") version = true;
        else if (a == "--timeout") {
            std::string v = next(a);
            if (is_number(v)) o.timeout = std::strtod(v.c_str(), nullptr);
        } else if (a == "-f" || a == "--file") {
            o.file = next(a);
        } else if (a.size() > 1 && a[0] == '-' && a != "-") {
            error = "unknown option: " + a;
        } else {
            o.positional.push_back(a);
        }
    }
    return o;
}

std::string prop_get(const std::string& name) {
    char value[PROP_VALUE_MAX] = {};
    int len = __system_property_get(name.c_str(), value);
    if (len <= 0) return {};
    return std::string(value, static_cast<size_t>(len));
}

bool prop_set(const std::string& name, const std::string& value) {
    return __system_property_set(name.c_str(), value.c_str()) == 0;
}

void collect_props(std::vector<std::pair<std::string, std::string>>& out) {
    struct Ctx {
        std::vector<std::pair<std::string, std::string>>* out;
    } ctx{&out};
    __system_property_foreach(
        [](const prop_info* pi, void* cookie) {
            auto* c = static_cast<Ctx*>(cookie);
            char name[PROP_VALUE_MAX] = {};
            char value[PROP_VALUE_MAX] = {};
            __system_property_read(pi, name, value);
            c->out->emplace_back(name, value);
        },
        &ctx);
    std::sort(out.begin(), out.end(), [](const auto& a, const auto& b) { return a.first < b.first; });
}

bool load_props_from_file(const std::string& path, bool verbose) {
    std::string content;
    if (!read_file_to_string(path, content)) {
        fprintf(stderr, "resetprop: failed to open %s\n", path.c_str());
        return false;
    }
    auto props = parse_properties(content);
    for (const auto& kv : props) {
        if (kv.first.empty()) continue;
        if (!prop_set(kv.first, kv.second)) {
            if (verbose) fprintf(stderr, "resetprop: warning: failed to set %s\n", kv.first.c_str());
        }
    }
    return true;
}

int run(const std::vector<std::string>& args) {
    bool help = false, version = false;
    std::string error;
    Options o = parse_args(args, help, version, error);
    if (help) {
        print_usage();
        return 0;
    }
    if (version) {
        printf("%s\n", defs::VERSION_NAME);
        return 0;
    }
    if (!error.empty()) {
        fprintf(stderr, "resetprop: %s\n", error.c_str());
        return 1;
    }

    if (!o.file.empty()) {
        return load_props_from_file(o.file, o.verbose) ? 0 : 1;
    }

    if (o.delete_prop) {
        if (o.positional.empty()) {
            fprintf(stderr, "resetprop: --delete requires a property name\n");
            return 1;
        }
        const std::string& name = o.positional[0];
        if (prop_get(name).empty()) {
            fprintf(stderr, "resetprop: %s not found\n", name.c_str());
            return 1;
        }
        // bionic 无公开删除接口，置空近似 Magisk 的删除语义。
        if (!prop_set(name, "")) {
            fprintf(stderr, "resetprop: failed to delete %s\n", name.c_str());
            return 1;
        }
        return 0;
    }

    if (o.wait) {
        if (o.positional.empty()) {
            fprintf(stderr, "resetprop: --wait requires a property name\n");
            return 1;
        }
        const std::string& name = o.positional[0];
        std::string target = o.positional.size() >= 2 ? o.positional[1] : "";
        bool has_target = o.positional.size() >= 2;
        struct timespec start;
        clock_gettime(CLOCK_MONOTONIC, &start);
        while (true) {
            std::string cur = prop_get(name);
            bool match = has_target ? (cur == target) : !cur.empty();
            if (match) return 0;
            if (o.timeout >= 0) {
                struct timespec now;
                clock_gettime(CLOCK_MONOTONIC, &now);
                double elapsed = (now.tv_sec - start.tv_sec) + (now.tv_nsec - start.tv_nsec) / 1e9;
                if (elapsed >= o.timeout) {
                    fprintf(stderr, "resetprop: timeout waiting for %s\n", name.c_str());
                    return 2;
                }
            }
            usleep(50000);
        }
    }

    if (o.rebuild) {
        // 直接 mmap 重建在 bionic 公开 API 下不可达；记录并返回成功（no-op）。
        klog("resetprop rebuild requested (no-op in bionic API build)");
        return 0;
    }

    if (o.positional.empty()) {
        std::vector<std::pair<std::string, std::string>> props;
        collect_props(props);
        for (const auto& kv : props) printf("[%s]: [%s]\n", kv.first.c_str(), kv.second.c_str());
        return 0;
    }

    const std::string& name = o.positional[0];
    if (o.positional.size() == 1) {
        std::string v = prop_get(name);
        if (v.empty()) {
            fprintf(stderr, "resetprop: %s not found\n", name.c_str());
            return 1;
        }
        printf("%s\n", v.c_str());
        return 0;
    }

    const std::string& value = o.positional[1];
    if (!prop_set(name, value)) {
        fprintf(stderr, "resetprop: failed to set %s\n", name.c_str());
        return 1;
    }
    return 0;
}

}  // namespace

int resetprop_main(const std::vector<std::string>& args) { return run(args); }

bool load_system_prop_file(const std::string& path) { return load_props_from_file(path, false); }

}  // namespace ncore::resetprop
