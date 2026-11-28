// SPDX-License-Identifier: GPL-3.0-or-later
// Magisk 兼容的 resetprop CLI。
//
// 读写底层全部落在 prop_area.cpp（沿用上游 manager 侧 prop 工具的 property
// area 实现）：ro.* 能改，长值能写，非 ro.* 默认仍走 property_service 以保留
// init 的 SELinux 检查与 trigger，`-n` 则一律直写。
//
// 尚未实现的开关（解析后忽略，不影响其它参数）：-p/--persistent、
// -P（持久化文件读写）、-c/-Z/--force（property area 重建/紧凑）。
#include "resetprop.h"

#include <time.h>
#include <unistd.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "common.h"
#include "defs.h"
#include "klog.h"
#include "prop_area.h"
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

bool set_prop(const std::string& name, const std::string& value, bool force_direct) {
    std::string err;
    if (!prop::write(name, value, force_direct, &err)) {
        fprintf(stderr, "resetprop: %s\n", err.c_str());
        return false;
    }
    return true;
}

bool load_props_from_file(const std::string& path, bool force_direct) {
    std::string content;
    if (!read_file_to_string(path, content)) {
        fprintf(stderr, "resetprop: failed to open %s\n", path.c_str());
        return false;
    }
    bool ok = true;
    for (const auto& kv : parse_properties(content)) {
        if (kv.first.empty()) continue;
        if (!set_prop(kv.first, kv.second, force_direct)) ok = false;
    }
    return ok;
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
        return load_props_from_file(o.file, o.skip_svc) ? 0 : 1;
    }

    if (o.delete_prop) {
        if (o.positional.empty()) {
            fprintf(stderr, "resetprop: --delete requires a property name\n");
            return 1;
        }
        const std::string& name = o.positional[0];
        std::string current;
        if (!prop::read(name, &current)) {
            fprintf(stderr, "resetprop: %s not found\n", name.c_str());
            return 1;
        }
        // 没有公开的删除接口：置空是与 Magisk 行为最接近的近似。
        if (!set_prop(name, "", o.skip_svc)) return 1;
        return 0;
    }

    if (o.wait) {
        if (o.positional.empty()) {
            fprintf(stderr, "resetprop: --wait requires a property name\n");
            return 1;
        }
        const std::string& name = o.positional[0];
        const bool has_target = o.positional.size() >= 2;
        const std::string target = has_target ? o.positional[1] : "";
        struct timespec start;
        clock_gettime(CLOCK_MONOTONIC, &start);
        while (true) {
            std::string cur;
            const bool exists = prop::read(name, &cur);
            if (has_target ? (exists && cur == target) : exists) return 0;
            if (o.timeout >= 0) {
                struct timespec now;
                clock_gettime(CLOCK_MONOTONIC, &now);
                const double elapsed =
                    (now.tv_sec - start.tv_sec) + (now.tv_nsec - start.tv_nsec) / 1e9;
                if (elapsed >= o.timeout) {
                    fprintf(stderr, "resetprop: timeout waiting for %s\n", name.c_str());
                    return 2;
                }
            }
            usleep(50000);
        }
    }

    if (o.rebuild) {
        // property area 的重建/紧凑没有实现：这里保持 no-op，但仍返回成功，
        // 免得模块脚本因为一个可选的维护命令中断。
        klog("resetprop: -c/--rebuild is not implemented");
        return 0;
    }

    if (o.positional.empty()) {
        prop::list();
        return 0;
    }

    const std::string& name = o.positional[0];
    if (o.positional.size() == 1) {
        std::string v;
        if (!prop::read(name, &v)) {
            fprintf(stderr, "resetprop: %s not found\n", name.c_str());
            return 1;
        }
        printf("%s\n", v.c_str());
        return 0;
    }

    return set_prop(name, o.positional[1], o.skip_svc) ? 0 : 1;
}

}  // namespace

int resetprop_main(const std::vector<std::string>& args) { return run(args); }

bool load_system_prop_file(const std::string& path) {
    // 等价 `resetprop -n --file`：模块的 system.prop 要在属性被 init 定型之前
    // 生效，绕开 property_service 才能写 ro.*。
    return load_props_from_file(path, true);
}

}  // namespace ncore::resetprop
