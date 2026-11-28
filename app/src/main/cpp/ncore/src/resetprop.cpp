// SPDX-License-Identifier: GPL-3.0-or-later
// Magisk 兼容的 resetprop CLI。
//
// 读写底层全部落在 prop_area.cpp（沿用上游 manager 侧 prop 工具的 property
// area 实现）：ro.* 能改，长值能写，非 ro.* 默认仍走 property_service 以保留
// init 的 SELinux 检查与 trigger，`-n` 则一律直写。
//
// 各开关的语义与 Magisk 一致（native/src/core/resetprop/cli.rs）：
//   -p/-p 读：连持久化存储一起读；写：直写时把 persist.* 落盘
//   -P    只读持久化存储
//   -Z    取属性的 SELinux context，而不是值
//   -c    紧凑 property area（可带一个 context 参数）
//   -w    等属性出现；给了 OLD_VALUE 时等它变成别的值
//   -d    真删除（trie 解链，不是把值置空）
#include "resetprop.h"

#include <sys/system_properties.h>
#include <time.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <string>
#include <vector>

#include "common.h"
#include "defs.h"
#include "persist_prop.h"
#include "prop_area.h"
#include "properties.h"

// NDK 的 <sys/system_properties.h> 不声明这两个：它们是 bionic 的内部符号，
// 但一直在 libc 的导出表里（LIBC 版本，API 1 起），-w 的 futex 语义需要它们。
extern "C" uint32_t __system_property_area_serial();
extern "C" uint32_t __system_property_serial(const prop_info* pi);

namespace ncore::resetprop {

namespace {

struct Options {
    bool verbose = false;
    bool wait = false;
    bool persist = false;
    bool persist_only = false;
    bool context = false;
    bool skip_svc = false;
    bool compact = false;
    bool version = false;
    bool has_delete = false;
    std::string delete_key;
    std::string file;
    double timeout = -1;  // <0 表示无限等待
    std::vector<std::string> positional;
};

void print_usage() {
    fprintf(stderr,
            "resetprop - System Property Manipulation Tool\n"
            "\n"
            "Usage: resetprop [flags] [arguments...]\n"
            "\n"
            "Read mode arguments:\n"
            "   (no arguments)    print all properties\n"
            "   NAME              get property of NAME\n"
            "\n"
            "Write mode arguments:\n"
            "   NAME VALUE        set property NAME as VALUE\n"
            "   -f,--file   FILE  load and set properties from FILE\n"
            "   -d,--delete NAME  delete property\n"
            "\n"
            "Wait mode arguments (toggled with -w):\n"
            "   NAME              wait until property NAME exists\n"
            "   NAME OLD_VALUE    if value of property NAME is not OLD_VALUE, get value\n"
            "                     or else wait until property NAME changes\n"
            "\n"
            "General flags:\n"
            "   -h,--help         show this message\n"
            "   -v,--verbose      print verbose output to stderr\n"
            "   -w                switch to wait mode\n"
            "   -c,--compact      compact property area (optional: context label)\n"
            "      --timeout N   give up waiting after N seconds (with -w)\n"
            "\n"
            "Read mode flags:\n"
            "   -p      also read persistent properties from storage\n"
            "   -P      only read persistent properties from storage\n"
            "   -Z      get property context instead of value\n"
            "\n"
            "Write mode flags:\n"
            "   -n      set properties bypassing property_service\n"
            "   -p      always write persistent prop changes to storage\n");
}

bool parse_seconds(const std::string& text, double* value) {
    if (text.empty()) return false;
    char* end = nullptr;
    double parsed = std::strtod(text.c_str(), &end);
    if (end == nullptr || *end != '\0' || parsed < 0) return false;
    *value = parsed;
    return true;
}

Options parse_args(const std::vector<std::string>& args, bool& help, std::string& error) {
    Options options;
    help = false;
    for (size_t i = 1; i < args.size(); ++i) {
        const std::string& arg = args[i];
        auto take = [&](const std::string& option) -> std::string {
            if (i + 1 < args.size()) return args[++i];
            error = "option " + option + " requires an argument";
            return {};
        };

        if (arg == "-h" || arg == "--help") {
            help = true;
        } else if (arg == "-v" || arg == "--verbose") {
            options.verbose = true;
        } else if (arg == "-w") {
            options.wait = true;
        } else if (arg == "-p") {
            options.persist = true;
        } else if (arg == "-P") {
            options.persist_only = true;
        } else if (arg == "-Z") {
            options.context = true;
        } else if (arg == "-n") {
            options.skip_svc = true;
        } else if (arg == "-c" || arg == "--compact") {
            options.compact = true;
        } else if (arg == "-f" || arg == "--file") {
            options.file = take(arg);
        } else if (arg == "-d" || arg == "--delete") {
            options.delete_key = take(arg);
            options.has_delete = true;
        } else if (arg == "--timeout") {
            std::string value = take(arg);
            if (!parse_seconds(value, &options.timeout)) {
                error = "invalid timeout: " + value;
            }
        } else if (arg == "--version") {
            options.version = true;
        } else if (arg.size() > 1 && arg[0] == '-' && arg != "-") {
            error = "unknown option: " + arg;
        } else {
            options.positional.push_back(arg);
        }

        if (!error.empty()) return options;
    }
    return options;
}

bool get_prop(const Options& options, const std::string& name, std::string* value) {
    if (options.context) {
        return prop::context_of(name, value);
    }

    if (!options.persist_only && prop::read(name, value)) {
        if (options.verbose) fprintf(stderr, "resetprop: get prop [%s]\n", name.c_str());
        return true;
    }

    if ((options.persist || options.persist_only) && name.starts_with("persist.")) {
        if (prop::persist::read(name, value)) {
            if (options.verbose) {
                fprintf(stderr, "resetprop: get persistent prop [%s]\n", name.c_str());
            }
            return true;
        }
    }
    return false;
}

bool set_prop(const Options& options, const std::string& name, const std::string& value) {
    std::string error;
    if (!prop::write(name, value, options.skip_svc, &error)) {
        fprintf(stderr, "resetprop: %s\n", error.c_str());
        return false;
    }
    if (options.verbose) {
        fprintf(stderr, "resetprop: set prop [%s] by %s\n", name.c_str(),
                options.skip_svc ? "direct modification" : "property_service");
    }

    // 直写绕过了 init，persist.* 的落盘要自己补；走 property_service 时
    // init 会自己写存储，再写一次纯属多余。
    if (options.skip_svc && options.persist && name.starts_with("persist.")) {
        std::string persist_error;
        if (!prop::persist::write(name, value, &persist_error)) {
            fprintf(stderr, "resetprop: %s\n", persist_error.c_str());
        }
    }
    return true;
}

bool delete_prop(const Options& options, const std::string& name) {
    std::string error;
    bool removed = prop::remove(name, true, &error);
    if (!removed) {
        fprintf(stderr, "resetprop: %s\n", error.c_str());
    }
    if (options.verbose) fprintf(stderr, "resetprop: delete prop [%s]\n", name.c_str());

    if (options.persist && name.starts_with("persist.")) {
        std::string persist_error;
        if (prop::persist::remove(name, &persist_error)) {
            removed = true;
        } else {
            fprintf(stderr, "resetprop: %s\n", persist_error.c_str());
        }
    }
    return removed;
}

bool load_props_from_file(const std::string& path, bool skip_svc, bool persist) {
    std::string content;
    if (!read_file_to_string(path, content)) {
        fprintf(stderr, "resetprop: failed to open %s\n", path.c_str());
        return false;
    }

    Options options;
    options.skip_svc = skip_svc;
    options.persist = persist;

    bool ok = true;
    for (const auto& entry : parse_properties(content)) {
        if (entry.first.empty()) continue;
        if (!set_prop(options, entry.first, entry.second)) ok = false;
    }
    return ok;
}

// 等到属性出现；给了 old_value 时等到它的值不再是 old_value。
// 返回 0 成功、2 超时。__system_property_wait 是 futex 等待，比轮询准。
int wait_prop(const Options& options) {
    const std::string& name = options.positional[0];
    const bool has_old_value = options.positional.size() >= 2;
    const std::string old_value = has_old_value ? options.positional[1] : "";

    struct timespec start {};
    clock_gettime(CLOCK_MONOTONIC, &start);

    auto remaining = [&](struct timespec* out) -> bool {
        if (options.timeout < 0) return true;  // 无限等
        struct timespec now {};
        clock_gettime(CLOCK_MONOTONIC, &now);
        double elapsed =
            (now.tv_sec - start.tv_sec) + (now.tv_nsec - start.tv_nsec) / 1000000000.0;
        double left = options.timeout - elapsed;
        if (left <= 0) return false;
        out->tv_sec = static_cast<time_t>(left);
        out->tv_nsec = static_cast<long>((left - static_cast<double>(out->tv_sec)) * 1e9);
        return true;
    };

    uint32_t serial = __system_property_area_serial();
    const prop_info* info = __system_property_find(name.c_str());
    while (info == nullptr) {
        struct timespec limit {};
        if (!remaining(&limit)) {
            fprintf(stderr, "resetprop: timeout waiting for %s\n", name.c_str());
            return 2;
        }
        struct timespec* limit_ptr = options.timeout < 0 ? nullptr : &limit;
        if (!__system_property_wait(nullptr, serial, &serial, limit_ptr)) {
            fprintf(stderr, "resetprop: timeout waiting for %s\n", name.c_str());
            return 2;
        }
        info = __system_property_find(name.c_str());
    }

    if (!has_old_value) {
        return 0;
    }

    for (;;) {
        std::string current;
        if (!prop::read(name, &current)) {
            // 等待期间被删了，重新等它出现。
            info = nullptr;
            while (info == nullptr) {
                struct timespec limit {};
                if (!remaining(&limit)) {
                    fprintf(stderr, "resetprop: timeout waiting for %s\n", name.c_str());
                    return 2;
                }
                struct timespec* limit_ptr = options.timeout < 0 ? nullptr : &limit;
                if (!__system_property_wait(nullptr, serial, &serial, limit_ptr)) {
                    fprintf(stderr, "resetprop: timeout waiting for %s\n", name.c_str());
                    return 2;
                }
                info = __system_property_find(name.c_str());
            }
            continue;
        }

        if (current != old_value) {
            return 0;
        }

        uint32_t observed = __system_property_serial(info);
        struct timespec limit {};
        if (!remaining(&limit)) {
            fprintf(stderr, "resetprop: timeout waiting for %s\n", name.c_str());
            return 2;
        }
        struct timespec* limit_ptr = options.timeout < 0 ? nullptr : &limit;
        if (!__system_property_wait(info, observed, &serial, limit_ptr)) {
            fprintf(stderr, "resetprop: timeout waiting for %s\n", name.c_str());
            return 2;
        }
        info = __system_property_find(name.c_str());
    }
}

int print_all(const Options& options) {
    if (options.context) {
        prop::list(true);
        return 0;
    }
    if (!options.persist && !options.persist_only) {
        prop::list(false);
        return 0;
    }

    std::map<std::string, std::string> merged;
    if (!options.persist_only) {
        std::string error;
        if (!prop::snapshot(&merged, &error)) {
            fprintf(stderr, "resetprop: %s\n", error.c_str());
            return 1;
        }
    }

    std::map<std::string, std::string> persisted;
    std::string error;
    if (!prop::persist::list(&persisted, &error)) {
        fprintf(stderr, "resetprop: %s\n", error.c_str());
        return 1;
    }
    // 内存里的实时值优先，持久化只补内存里没有的。
    for (auto& entry : persisted) {
        merged.emplace(std::move(entry.first), std::move(entry.second));
    }

    for (const auto& entry : merged) {
        printf("[%s]: [%s]\n", entry.first.c_str(), entry.second.c_str());
    }
    return 0;
}

int run(const std::vector<std::string>& args) {
    bool help = false;
    std::string error;
    Options options = parse_args(args, help, error);

    if (help) {
        print_usage();
        return 0;
    }
    if (options.version) {
        printf("%s\n", defs::VERSION_NAME);
        return 0;
    }
    if (!error.empty()) {
        fprintf(stderr, "resetprop: %s\n", error.c_str());
        print_usage();
        return 1;
    }

    int modes = (options.wait ? 1 : 0) + (options.compact ? 1 : 0) +
                (options.has_delete ? 1 : 0) + (!options.file.empty() ? 1 : 0);
    if (modes > 1) {
        fprintf(stderr, "resetprop: multiple operation modes detected\n");
        return 1;
    }
    if (options.positional.size() > 2) {
        fprintf(stderr, "resetprop: unrecognized argument: %s\n",
                options.positional[2].c_str());
        return 1;
    }

    if (options.compact) {
        const std::string filter = options.positional.empty() ? "" : options.positional[0];
        std::string compact_error;
        if (!prop::compact(filter, &compact_error)) {
            fprintf(stderr, "resetprop: %s\n", compact_error.c_str());
            return 1;
        }
        return 0;
    }

    if (options.wait) {
        if (options.positional.empty()) {
            fprintf(stderr, "resetprop: -w requires a property name\n");
            return 1;
        }
        return wait_prop(options);
    }

    if (!options.file.empty()) {
        return load_props_from_file(options.file, options.skip_svc, options.persist) ? 0 : 1;
    }

    if (options.has_delete) {
        return delete_prop(options, options.delete_key) ? 0 : 1;
    }

    if (options.positional.empty()) {
        return print_all(options);
    }

    if (options.positional.size() == 1) {
        std::string value;
        if (!get_prop(options, options.positional[0], &value)) {
            fprintf(stderr, "resetprop: %s not found\n", options.positional[0].c_str());
            return 1;
        }
        printf("%s\n", value.c_str());
        return 0;
    }

    return set_prop(options, options.positional[0], options.positional[1]) ? 0 : 1;
}

}  // namespace

int resetprop_main(const std::vector<std::string>& args) { return run(args); }

bool load_system_prop_file(const std::string& path) {
    // 等价 `resetprop -n --file`：模块的 system.prop 要在属性被 init 定型之前
    // 生效，绕开 property_service 才能写 ro.*。
    return load_props_from_file(path, true, false);
}

}  // namespace ncore::resetprop
