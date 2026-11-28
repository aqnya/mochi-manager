// SPDX-License-Identifier: GPL-3.0-or-later
// su 入口点：内核已应用选定的 root profile，这里只做参数解析、身份切换和 exec。
// 移植自 neko/ncore/src/system/su.rs。
#include "su.h"

#include <fcntl.h>
#include <grp.h>
#include <pwd.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <climits>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "common.h"
#include "defs.h"
#include "utils.h"

namespace ncore::su {

namespace {

// 帮助文本同时是**能力声明**：termux 的 sudo（agnostic-apollo/sudo）会先跑
// `su --help`，用 `*"--shell"*`、`*"--preserve-environment"*`、`*"--mount-master"*`
// 这类字面量判断这个 su 能不能用，缺一个就跳过、继续找下一个，最后报
// "No 'su' binary found that supports the '-c', '--shell', ... options"。
// 所以长选项必须原样出现在这里，不能只写短选项。
void print_usage(const std::string& program) {
    printf(
        "NekoSU\n\n"
        "Usage: %s [options] [-] [user [argument...]]\n\n"
        "Options:\n"
        "  -c, --command COMMAND         pass COMMAND to the invoked shell\n"
        "  -h, --help                    display this help message and exit\n"
        "  -, -l, --login                pretend the shell to be a login shell\n"
        "  -m, -p, --preserve-environment  preserve the entire environment\n"
        "  -s, --shell SHELL             use SHELL instead of the default /system/bin/sh\n"
        "  -M, --mount-master            force run in the global mount namespace\n"
        "  -g, --group GROUP             specify the primary group\n"
        "  -G, --supp-group GROUP        specify a supplementary group (repeatable)\n"
        "  -Z, --context CONTEXT         specify the SELinux context\n"
        "  --no-new-privs                prevent privilege re-escalation\n"
        "  -v, --version                 display version number and exit\n"
        "  -V                            display version code and exit\n",
        program.c_str());
}

uid_t resolve_uid(const std::string& user) {
    struct passwd* pw = getpwnam(user.c_str());
    if (pw != nullptr) return pw->pw_uid;
    char* end = nullptr;
    unsigned long v = std::strtoul(user.c_str(), &end, 10);
    if (*end == '\0' && !user.empty()) return static_cast<uid_t>(v);
    throw Error("Unknown user: " + user);
}

gid_t parse_gid(const std::string& g) {
    char* end = nullptr;
    unsigned long v = std::strtoul(g.c_str(), &end, 10);
    if (*end != '\0' || g.empty()) throw Error("Invalid GID: " + g);
    return static_cast<gid_t>(v);
}

bool is_option(const std::string& s) { return s.size() > 1 && s[0] == '-'; }

void add_path_to_env(const std::string& path) {
    std::string cur = getenv("PATH") != nullptr ? getenv("PATH") : "";
    std::string bin = path;
    while (!bin.empty() && bin.back() == '/') bin.pop_back();
    setenv("PATH", (cur + ":" + bin).c_str(), 1);
}

void set_identity(uid_t uid, gid_t gid, const std::vector<gid_t>& groups) {
    if (!groups.empty() && setgroups(groups.size(), groups.data()) != 0) {
        throw sys_error("setgroups");
    }
    if (setresgid(gid, gid, gid) != 0) throw sys_error("setresgid");
    if (setresuid(uid, uid, uid) != 0) throw sys_error("setresuid");
}

}  // namespace

void root_shell(int argc, char** argv_raw) {
    std::vector<std::string> argv;
    for (int i = 0; i < argc; ++i) argv.emplace_back(argv_raw[i] != nullptr ? argv_raw[i] : "");
    if (argv.empty()) argv.push_back("su");
    std::string program = argv[0];

    // 解析选项。
    std::string shell = "/system/bin/sh";
    std::string command;
    bool has_command = false;
    bool is_login = false;
    bool preserve_env = false;
    bool mount_master = false;
    bool no_new_privs = false;
    std::vector<gid_t> groups;
    gid_t gid = static_cast<gid_t>(-1);
    std::string selinux_context;
    std::vector<std::string> free_args;

    // 需要带值选项的统一取值：缺参数直接报错，不再"静默降级成交互 shell"。
    auto take_value = [&](size_t& i, const char* opt) -> std::string {
        if (i + 1 >= argv.size()) {
            fprintf(stderr, "su: option requires an argument -- '%s'\n", opt);
            print_usage(program);
            exit(1);
        }
        return argv[++i];
    };

    for (size_t i = 1; i < argv.size(); ++i) {
        const std::string& a = argv[i];
        if (a == "--") {
            // 「选项结束」：后面的全部按位置参数处理。
            for (++i; i < argv.size(); ++i) free_args.push_back(argv[i]);
            break;
        }
        if (a == "-c" || a == "--command") {
            command = take_value(i, "c");
            has_command = true;
        } else if (a.rfind("--command=", 0) == 0) {
            command = a.substr(strlen("--command="));
            has_command = true;
        } else if (a == "-h" || a == "--help") {
            print_usage(program);
            return;
        } else if (a == "-v" || a == "--version") {
            printf("%s:NekoSU\n", defs::VERSION_NAME);
            return;
        } else if (a == "-V") {
            printf("%s\n", defs::VERSION_CODE);
            return;
        } else if (a == "-l" || a == "--login") {
            is_login = true;
        } else if (a == "-p" || a == "-m" || a == "--preserve-environment") {
            preserve_env = true;
        } else if (a == "-M" || a == "-mm" || a == "--mount-master") {
            mount_master = true;
        } else if (a == "--no-new-privs") {
            no_new_privs = true;
        } else if (a == "-s" || a == "--shell") {
            shell = take_value(i, "s");
        } else if (a.rfind("--shell=", 0) == 0) {
            shell = a.substr(strlen("--shell="));
        } else if (a == "-g" || a == "--group") {
            gid = parse_gid(take_value(i, "g"));
        } else if (a.rfind("--group=", 0) == 0) {
            gid = parse_gid(a.substr(strlen("--group=")));
        } else if (a == "-G" || a == "--supp-group") {
            groups.push_back(parse_gid(take_value(i, "G")));
        } else if (a.rfind("--supp-group=", 0) == 0) {
            groups.push_back(parse_gid(a.substr(strlen("--supp-group="))));
        } else if (a == "-Z" || a == "--context") {
            selinux_context = take_value(i, "Z");
        } else if (a.rfind("--context=", 0) == 0) {
            selinux_context = a.substr(strlen("--context="));
        } else if (a == "-z" || a == "-cn") {
            // -z / -cn 依赖后续的 -Z 取值，这里不额外处理。
        } else if (is_option(a)) {
            fprintf(stderr, "su: invalid option -- '%s'\n", a.c_str());
            print_usage(program);
            exit(1);
        } else {
            free_args.push_back(a);
        }
    }

    size_t free_idx = 0;
    if (!free_args.empty() && free_args[free_idx] == "-") {
        is_login = true;
        ++free_idx;
    }

    bool identity_requested = free_idx < free_args.size() || gid != static_cast<gid_t>(-1) || !groups.empty();

    uid_t uid = free_idx < free_args.size() ? resolve_uid(free_args[free_idx]) : getuid();
    if (gid == static_cast<gid_t>(-1)) {
        gid = !groups.empty() ? groups[0] : static_cast<gid_t>(uid);
    }

    // 要 exec 的程序路径与子进程 argv 必须分开维护。
    // 之前把程序路径同时塞进 argv[0] 和 argv[1]，裸执行 `su` 就变成
    // `sh /system/bin/sh`——mksh 会把 sh 二进制当脚本读，报
    // "/system/bin/sh[1]: syntax error: unexpected '('"。
    std::string program_path;
    std::vector<std::string> child_args;  // 不含 argv[0]

    if (has_command) {
        program_path = shell;
        child_args.push_back("-c");
        child_args.push_back(command);
    } else if (free_idx + 1 < free_args.size()) {
        program_path = free_args[free_idx + 1];
        for (size_t i = free_idx + 2; i < free_args.size(); ++i) child_args.push_back(free_args[i]);
    } else {
        program_path = shell;
    }

    // argv[0] 用 basename；login shell 前置 '-'，shell 见到才按登录 shell 处理。
    std::string base = program_path;
    size_t last_slash = base.rfind('/');
    if (last_slash != std::string::npos) base = base.substr(last_slash + 1);
    if (base.empty()) base = program_path;
    std::string arg0 = is_login ? "-" + base : base;

    if (!preserve_env) {
        struct passwd* pw = getpwuid(uid);
        if (pw != nullptr) {
            setenv("HOME", pw->pw_dir != nullptr ? pw->pw_dir : "/", 1);
            setenv("USER", pw->pw_name != nullptr ? pw->pw_name : "root", 1);
            setenv("LOGNAME", pw->pw_name != nullptr ? pw->pw_name : "root", 1);
            setenv("SHELL", shell.c_str(), 1);
        }
    }

    add_path_to_env(defs::BINARY_DIR);
    if (path_exists(defs::KSURC_PATH) && getenv("ENV") == nullptr) setenv("ENV", defs::KSURC_PATH, 1);

    if (no_new_privs && prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
        throw sys_error("set NO_NEW_PRIVS");
    }

    std::vector<char*> cargs;
    cargs.push_back(const_cast<char*>(arg0.c_str()));
    for (const auto& a : child_args) cargs.push_back(const_cast<char*>(a.c_str()));
    cargs.push_back(nullptr);

    utils::umask(022);
    utils::switch_cgroups();
    if (mount_master) utils::switch_mnt_ns(1);
    if (identity_requested) set_identity(uid, gid, groups);

    if (!selinux_context.empty()) {
        int fd = open("/proc/thread-self/attr/current", O_WRONLY | O_CLOEXEC);
        if (fd >= 0) {
            (void)write(fd, selinux_context.data(), selinux_context.size());
            close(fd);
        }
    }

    // 带 '/' 的按路径 exec；不带 '/' 的（如 `su 0 id`）走 PATH 查找。
    if (program_path.find('/') == std::string::npos) {
        execvp(program_path.c_str(), cargs.data());
    } else {
        execv(program_path.c_str(), cargs.data());
    }
    throw sys_error("exec " + program_path);
}

}  // namespace ncore::su
