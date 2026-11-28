// SPDX-License-Identifier: GPL-3.0-or-later
// su 入口点：内核已应用选定的 root profile，这里只做参数解析、身份切换和 exec。
// 移植自 neko/ncore/src/system/su.rs。
#include "su.h"

#include <fcntl.h>
#include <getopt.h>
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

// --no-new-privs 没有短选项，用一个超出 char 范围的值做 key。
enum { kOptNoNewPrivs = 1000 };

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
    // 复制一份可变 argv：既给 getopt_long 用，也能把历史别名就地规整——
    // `-mm`（老式 mount-master）若原样交给 getopt 会被拆成两个 `-m`，
    // `-cn` 会被当成 `-c n`（去执行一条叫 n 的命令），都必须在解析前改写。
    std::vector<std::string> args;
    args.reserve(static_cast<size_t>(argc > 0 ? argc : 1));
    args.emplace_back(argc > 0 && argv_raw[0] != nullptr ? argv_raw[0] : "su");
    for (int i = 1; i < argc; ++i) {
        std::string a = argv_raw[i] != nullptr ? argv_raw[i] : "";
        if (a == "-mm") {
            a = "-M";
        } else if (a == "-cn") {
            a = "-z";
        }
        args.push_back(std::move(a));
    }
    const std::string program = args[0];

    std::vector<char*> argv;
    argv.reserve(args.size() + 1);
    for (auto& s : args) argv.push_back(s.data());
    argv.push_back(nullptr);
    const int argc_i = static_cast<int>(args.size());

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

    // 长选项统一复用短选项字符处理，避免两套分支逻辑走偏。
    static const struct option kLongOptions[] = {
        {"command", required_argument, nullptr, 'c'},
        {"help", no_argument, nullptr, 'h'},
        {"login", no_argument, nullptr, 'l'},
        {"preserve-environment", no_argument, nullptr, 'p'},
        {"shell", required_argument, nullptr, 's'},
        {"mount-master", no_argument, nullptr, 'M'},
        {"group", required_argument, nullptr, 'g'},
        {"supp-group", required_argument, nullptr, 'G'},
        {"context", required_argument, nullptr, 'Z'},
        {"no-new-privs", no_argument, nullptr, kOptNoNewPrivs},
        {"version", no_argument, nullptr, 'v'},
        {nullptr, 0, nullptr, 0},
    };

    // optstring 前缀两个修饰符：
    //   ':' 让缺参数返回 ':'（与「未知选项」的 '?' 区分开），能精确报错；
    //   '+' 走 POSIX 模式——遇到第一个非选项就停下，否则 GNU getopt 会把
    //       `su 0 id` 里的 `0`、`id` 重排到所有选项之后，位置参数就错位了。
    optind = 0;  // 复位 getopt 全局状态（同一进程内可能多次进入本函数）
    opterr = 0;  // 错误信息自己打印，格式与其它 su 实现保持一致
    int opt;
    while ((opt = getopt_long(argc_i, argv.data(), "+:c:hlmps:vVMg:G:Z:z", kLongOptions, nullptr)) !=
           -1) {
        switch (opt) {
            case 'c':
                command = optarg;
                has_command = true;
                break;
            case 'h':
                print_usage(program);
                return;
            case 'v':
                printf("%s:NekoSU\n", defs::VERSION_NAME);
                return;
            case 'V':
                printf("%s\n", defs::VERSION_CODE);
                return;
            case 'l':
                is_login = true;
                break;
            case 'm':
            case 'p':
                preserve_env = true;
                break;
            case 'M':
                mount_master = true;
                break;
            case 's':
                shell = optarg;
                break;
            case 'g':
                gid = parse_gid(optarg);
                break;
            case 'G':
                groups.push_back(parse_gid(optarg));
                break;
            case 'Z':
                selinux_context = optarg;
                break;
            case 'z':
                // -z / -cn：历史别名，无实际操作。
                break;
            case kOptNoNewPrivs:
                no_new_privs = true;
                break;
            case ':':
                fprintf(stderr, "su: option requires an argument -- '%s'\n",
                        optind > 0 ? argv[optind - 1] : "");
                print_usage(program);
                exit(1);
            case '?':
            default:
                fprintf(stderr, "su: invalid option -- '%s'\n", optind > 0 ? argv[optind - 1] : "");
                print_usage(program);
                exit(1);
        }
    }

    // getopt 停下后，optind 之后全是位置参数（单独一个 "-" 表示登录 shell）。
    std::vector<std::string> free_args;
    for (int i = optind; i < argc_i; ++i) free_args.emplace_back(argv[i]);

    // 兼容 termux sudo（agnostic-apollo/sudo）这类包装器：它把整条 shell 命令拼进
    // 一个变量再**不加引号**展开，于是 `-c` 只吃到第一个词（如 `export`），后面
    // `PATH='...';`、`LD_LIBRARY_PATH='';` … 全被 bash 按空格切成位置参数。
    // 这些位置参数原本就和 `-c` 的值是同一条命令（原串正是被空格切开的），
    // 用空格接回去即可无损还原；带 `-c` 时位置参数不再当作「用户名」解析，
    // 否则会报 "Unknown user: PATH='...'"。
    if (has_command && !free_args.empty()) {
        for (const auto& a : free_args) {
            command.push_back(' ');
            command += a;
        }
        free_args.clear();
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
