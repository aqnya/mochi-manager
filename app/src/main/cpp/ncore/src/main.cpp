// SPDX-License-Identifier: GPL-3.0-or-later
// NekoSU ncore 命令行入口。
//
// 内核在 boot 阶段从 nativeLibraryDir 找 libncore.so 引导；它顶着 .so 名字，
// 但本质是 PIE 可执行文件。argv[0] 为 "su"/.../su 或 ...resetprop 时走多调用入口。
// 移植自 neko/ncore/src/cli.rs + main.rs。
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "boot.h"
#include "common.h"
#include "defs.h"
#include "klog.h"
#include "manager.h"
#include "module.h"
#include "resetprop.h"
#include "sepolicy.h"
#include "su.h"
#include "utils.h"

namespace {

std::vector<std::string> make_args(int argc, char** argv) {
    std::vector<std::string> args;
    for (int i = 0; i < argc; ++i) args.emplace_back(argv[i] != nullptr ? argv[i] : "");
    return args;
}

void print_help() {
    printf(
        "NekoSU ncore %s (uapi: %u)\n\n"
        "Usage: ncore <command> [args...]\n\n"
        "Commands:\n"
        "  module install <ZIP>          install a module\n"
        "  module uninstall <id>         mark a module for removal\n"
        "  module undo-uninstall <id>    undo removal mark\n"
        "  module enable <id>            enable a module\n"
        "  module disable <id>           disable a module\n"
        "  module action <id>            run module action.sh\n"
        "  module list [--json]          list modules as JSON\n"
        "  sepolicy patch <RULE>         patch live policy with a rule\n"
        "  sepolicy apply <FILE>         apply rules from a file\n"
        "  sepolicy check <RULE|FILE>    validate a rule\n"
        "  post-fs-data                  trigger post-fs-data event\n"
        "  services                      trigger service event\n"
        "  boot-completed                trigger boot-completed event\n"
        "  install                       install ncore userspace component\n"
        "  manager scan [CERT] [--packages-xml PATH]\n"
        "                                find & authorize manager package\n"
        "  resetprop [args...]           system property tool\n",
        ncore::defs::VERSION_NAME, ncore::defs::KERNEL_SU_UAPI_VERSION);
}

int dispatch(const std::vector<std::string>& args) {
    if (args.size() < 2) {
        print_help();
        return 1;
    }
    const std::string& cmd = args[1];

    if (cmd == "post-fs-data") {
        return ncore::boot::on_post_fs_data() ? 0 : 1;
    }
    if (cmd == "services") {
        ncore::boot::on_services();
        return 0;
    }
    if (cmd == "boot-completed") {
        ncore::boot::on_boot_completed();
        return 0;
    }
    if (cmd == "install") {
        return ncore::utils::install() ? 0 : 1;
    }
    if (cmd == "resetprop") {
        // 把 "resetprop" 作为 argv[0] 传给 resetprop 解析器。
        auto rp_args = args;
        rp_args[0] = "resetprop";
        rp_args.erase(rp_args.begin() + 1);
        return ncore::resetprop::resetprop_main(rp_args);
    }
    if (cmd == "sepolicy") {
        if (args.size() < 4) {
            print_help();
            return 1;
        }
        const std::string& op = args[2];
        const std::string& arg = args[3];
        bool ok = false;
        if (op == "patch") ok = ncore::sepolicy::live_patch(arg);
        else if (op == "apply") ok = ncore::sepolicy::apply_file(arg);
        else if (op == "check") ok = ncore::sepolicy::check_rule(arg);
        else {
            print_help();
            return 1;
        }
        return ok ? 0 : 1;
    }
    if (cmd == "manager") {
        if (args.size() < 3 || args[2] != "scan") {
            print_help();
            return 1;
        }
        std::string cert;
        std::string packages_xml;
        for (size_t i = 3; i < args.size(); ++i) {
            if (args[i] == "--packages-xml" && i + 1 < args.size()) {
                packages_xml = args[++i];
            } else if (args[i].rfind("--packages-xml=", 0) == 0) {
                packages_xml = args[i].substr(strlen("--packages-xml="));
            } else {
                cert = args[i];
            }
        }
        std::string hash;
        if (!ncore::manager::resolve_cert_hash(cert, hash)) {
            fprintf(stderr, "manager: no certificate hash provided\n");
            return 1;
        }
        return ncore::manager::run_manager_scan(hash, packages_xml) ? 0 : 1;
    }
    if (cmd == "module") {
        if (args.size() < 3) {
            print_help();
            return 1;
        }
        // 模块命令需要切到 init 的挂载命名空间（对齐 Rust：switch_mnt_ns(1)）。
        ncore::utils::switch_mnt_ns(1);
        const std::string& op = args[2];
        auto arg_at = [&](size_t idx) -> std::string {
            return idx < args.size() ? args[idx] : std::string();
        };
        if (op == "list") {
            return ncore::module::list_modules() ? 0 : 1;
        }
        std::string target = arg_at(3);
        if (target.empty()) {
            print_help();
            return 1;
        }
        bool ok = false;
        if (op == "install") ok = ncore::module::install_module(target);
        else if (op == "uninstall") ok = ncore::module::uninstall_module(target);
        else if (op == "undo-uninstall") ok = ncore::module::undo_uninstall_module(target);
        else if (op == "enable") ok = ncore::module::enable_module(target);
        else if (op == "disable") ok = ncore::module::disable_module(target);
        else if (op == "action") ok = ncore::module::run_action(target);
        else {
            print_help();
            return 1;
        }
        return ok ? 0 : 1;
    }

    print_help();
    return 1;
}

}  // namespace

int main(int argc, char** argv) {
    std::vector<std::string> args = make_args(argc, argv);

    // argv[0] 为 su（内核重定向）时进入 root shell。
    if (!args.empty()) {
        const std::string& arg0 = args[0];
        if (arg0 == "su" || (arg0.size() >= 3 && arg0.compare(arg0.size() - 3, 3, "/su") == 0)) {
            try {
                ncore::su::root_shell(argc, argv);
            } catch (const ncore::Error& e) {
                fprintf(stderr, "su: %s\n", e.what());
                return 1;
            }
            return 0;
        }
        if (arg0.size() >= 9 && arg0.compare(arg0.size() - 9, 9, "resetprop") == 0) {
            return ncore::resetprop::resetprop_main(args);
        }
    }

    try {
        return dispatch(args);
    } catch (const ncore::Error& e) {
        ncore::klog("Error: %s", e.what());
        fprintf(stderr, "Error: %s\n", e.what());
        return 1;
    } catch (const std::exception& e) {
        ncore::klog("Error: %s", e.what());
        fprintf(stderr, "Error: %s\n", e.what());
        return 1;
    }
}
