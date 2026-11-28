// SPDX-License-Identifier: GPL-3.0-or-later
#include "hot_install.h"

#include <chrono>
#include <cstdio>
#include <fcntl.h>
#include <sys/wait.h>
#include <unistd.h>

#include "assets.h"
#include "common.h"
#include "defs.h"
#include "module.h"
#include "sepolicy.h"
#include "utils.h"

namespace ncore::hot_install {

namespace {
bool is_safe_relative_script(const std::string& script) {
    if (script.empty()) return false;
    if (script[0] == '/') return false;
    for (const auto& part : split(script, '/')) {
        if (part == ".." || part == "." || part.empty()) return false;
    }
    return true;
}
}  // namespace

Request take_request(const std::string& staged_dir) {
    Request req;
    std::string marker = staged_dir + "/" + defs::HOT_INSTALL_MARKER;
    std::string content;
    if (!read_file_to_string(marker, content)) return req;
    unlink(marker.c_str());
    req.valid = true;
    req.run_script = trim(content);
    return req;
}

bool apply(const std::string& module_id, bool is_metamodule_, const Request& request) {
    printf("- Hot install requested\n");
    if (is_metamodule_) {
        printf("- Hot install skipped: metamodules are always applied at boot\n");
        printf("- Reboot to apply the module\n");
        return false;
    }
    if (utils::is_safe_mode()) {
        printf("- Hot install skipped: modules are disabled in safe mode\n");
        printf("- Reboot to apply the module\n");
        return false;
    }

    std::string run_script;
    if (!request.run_script.empty()) {
        if (!is_safe_relative_script(request.run_script)) {
            printf("- Hot install skipped: invalid MODULE_HOT_RUN_SCRIPT\n");
            return false;
        }
        std::string staged = std::string(defs::MODULE_UPDATE_DIR) + module_id;
        std::string script_path = staged + "/" + request.run_script;
        if (!is_file(script_path)) {
            printf("! MODULE_HOT_RUN_SCRIPT '%s' not found, skipping it\n", request.run_script.c_str());
        } else {
            run_script = request.run_script;
        }
    }

    std::string staged_dir = std::string(defs::MODULE_UPDATE_DIR) + module_id;
    std::string live_dir = std::string(defs::MODULE_DIR) + module_id;

    // stash 旧 live 目录。
    std::string stashed;
    if (path_exists(live_dir)) {
        if (!utils::ensure_dir_exists(defs::HOT_INSTALL_TRASH_DIR)) {
            printf("- Hot install skipped: cannot create trash dir\n");
            return false;
        }
        auto nanos = std::chrono::duration_cast<std::chrono::nanoseconds>(
                         std::chrono::system_clock::now().time_since_epoch())
                         .count();
        stashed = std::string(defs::HOT_INSTALL_TRASH_DIR) + module_id + "." + std::to_string(nanos);
        if (rename(live_dir.c_str(), stashed.c_str()) != 0) {
            printf("- Hot install skipped: cannot stash old module\n");
            return false;
        }
    }
    if (rename(staged_dir.c_str(), live_dir.c_str()) != 0) {
        if (!stashed.empty()) rename(stashed.c_str(), live_dir.c_str());
        printf("- Hot install skipped: cannot move staged module\n");
        return false;
    }

    // 应用 sepolicy.rule（规则是累加的，立即加载安全）。
    std::string rule = live_dir + "/sepolicy.rule";
    if (is_file(rule)) sepolicy::apply_file(rule);

    bool has_partition = false;
    for (const char* p : {"system", "vendor", "product", "system_ext", "odm"}) {
        if (is_dir(live_dir + "/" + p)) { has_partition = true; break; }
    }
    if (!is_file(live_dir + "/skip_mount") && has_partition) {
        printf("- Note: partition files are mounted on the next boot\n");
    }
    if (is_dir(live_dir + "/" + defs::MODULE_PREINIT_DIR)) {
        printf("- Note: preinit actions run from the next boot\n");
    }

    if (!run_script.empty()) {
        utils::switch_mnt_ns(1);
        printf("- Running %s\n", run_script.c_str());
        module::WaitDeadline wait;
        wait.kind = module::ScriptWait::Until;
        wait.deadline = std::chrono::steady_clock::now() + std::chrono::seconds(30);
        module::exec_script(live_dir + "/" + run_script, wait);
    }
    return true;
}

void clean_trash() {
    std::string trash = defs::HOT_INSTALL_TRASH_DIR;
    if (!path_exists(trash)) return;
    std::string old = std::string(defs::WORKING_DIR) + "hot_install_trash.old";
    if (path_exists(old)) remove_dir_all(old);
    if (rename(trash.c_str(), old.c_str()) != 0) return;
    // 后台删除。
    pid_t pid = fork();
    if (pid == 0) {
        int devnull = open("/dev/null", O_WRONLY);
        dup2(devnull, 1);
        dup2(devnull, 2);
        execl(assets::BUSYBOX_PATH, "busybox", "rm", "-rf", old.c_str(), (char*)nullptr);
        _exit(0);
    }
}

}  // namespace ncore::hot_install
