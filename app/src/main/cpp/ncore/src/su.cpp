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

void print_usage(const std::string& program) {
    printf("NekoSU\n\nUsage: %s [options] [-] [user [argument...]]\n", program.c_str());
    printf(
        "Options:\n"
        "  -c COMMAND      pass COMMAND to the invoked shell\n"
        "  -h, --help      display this help message and exit\n"
        "  -l, --login     pretend the shell to be a login shell\n"
        "  -p, --preserve-environment  preserve the entire environment\n"
        "  -s SHELL        use SHELL instead of the default /system/bin/sh\n"
        "  -v, --version   display version number and exit\n"
        "  -V              display version code and exit\n"
        "  -M, --mount-master  force run in the global mount namespace\n"
        "  -g GROUP        specify the primary group\n"
        "  -G GROUP        specify a supplementary group (repeatable)\n"
        "  --no-new-privs  prevent privilege re-escalation\n"
        "  -Z CONTEXT      specify the SELinux context\n");
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
    std::string executable;

    for (size_t i = 1; i < argv.size(); ++i) {
        const std::string& a = argv[i];
        if (a == "-c") {
            if (i + 1 < argv.size()) {
                command = argv[++i];
                has_command = true;
            }
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
        } else if (a == "-p" || a == "--preserve-environment") {
            preserve_env = true;
        } else if (a == "-M" || a == "--mount-master" || a == "-mm") {
            mount_master = true;
        } else if (a == "--no-new-privs") {
            no_new_privs = true;
        } else if (a == "-s" || a == "--shell") {
            if (i + 1 < argv.size()) shell = argv[++i];
        } else if (a == "-g" || a == "--group") {
            if (i + 1 < argv.size()) gid = parse_gid(argv[++i]);
        } else if (a == "-G" || a == "--supp-group") {
            if (i + 1 < argv.size()) groups.push_back(parse_gid(argv[++i]));
        } else if (a.rfind("--supp-group=", 0) == 0) {
            groups.push_back(parse_gid(a.substr(strlen("--supp-group="))));
        } else if (a.rfind("--shell=", 0) == 0) {
            shell = a.substr(strlen("--shell="));
        } else if (a.rfind("--context=", 0) == 0) {
            selinux_context = a.substr(strlen("--context="));
        } else if (a == "-Z" || a == "--context") {
            if (i + 1 < argv.size()) selinux_context = argv[++i];
        } else if (a == "-z" || a == "-cn") {
            // -cn 归一化为 -z（上下文），这里忽略无 context 的情况。
        } else if (is_option(a)) {
            printf("su: invalid option -- '%s'\n", a.c_str());
            print_usage(program);
            exit(-1);
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

    std::vector<std::string> exec_argv;
    std::string arg0 = is_login ? "-" : shell;
    if (has_command) {
        exec_argv = {shell, "-c", command};
    } else if (free_idx + 1 < free_args.size()) {
        executable = free_args[free_idx + 1];
        exec_argv.push_back(executable);
        for (size_t i = free_idx + 2; i < free_args.size(); ++i) exec_argv.push_back(free_args[i]);
        arg0 = is_login ? "-" : executable;
    } else {
        exec_argv.push_back(shell);
    }

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
    for (const auto& a : exec_argv) cargs.push_back(const_cast<char*>(a.c_str()));
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

    execv(exec_argv[0].c_str(), cargs.data());
    throw sys_error("exec " + exec_argv[0]);
}

}  // namespace ncore::su
