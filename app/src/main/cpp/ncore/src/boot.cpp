// SPDX-License-Identifier: GPL-3.0-or-later
// 启动生命周期事件：防砖记账 + 各阶段脚本分发。
// 移植自 neko/ncore/src/boot/init_event.rs。
#include "boot.h"

#include <fcntl.h>
#include <sys/wait.h>
#include <unistd.h>

#include <chrono>

#include "assets.h"
#include "common.h"
#include "config.h"
#include "defs.h"
#include "hot_install.h"
#include "klog.h"
#include "kzcall.h"
#include "manager.h"
#include "metamodule.h"
#include "module.h"
#include "preinit.h"
#include "restorecon.h"
#include "utils.h"

namespace ncore::boot {

namespace {

void catch_bootlog(const std::string& logname, const std::vector<std::string>& command) {
    utils::ensure_dir_exists(defs::LOG_DIR);
    std::string bootlog = std::string(defs::LOG_DIR) + logname + ".log";
    std::string oldbootlog = std::string(defs::LOG_DIR) + logname + ".old.log";
    if (path_exists(bootlog)) rename(bootlog.c_str(), oldbootlog.c_str());

    int fd = open(bootlog.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (fd < 0) return;

    pid_t pid = fork();
    if (pid == 0) {
        dup2(fd, STDOUT_FILENO);
        dup2(fd, STDERR_FILENO);
        close(fd);
        setsid();
        utils::switch_cgroups();
        std::vector<char*> args;
        std::string timeout = "30s";
        args.push_back(const_cast<char*>("timeout"));
        args.push_back(const_cast<char*>("-s"));
        args.push_back(const_cast<char*>("9"));
        args.push_back(const_cast<char*>(timeout.c_str()));
        for (const auto& c : command) args.push_back(const_cast<char*>(c.c_str()));
        args.push_back(nullptr);
        execvp(args[0], args.data());
        _exit(127);
    }
    close(fd);
}

void run_stage(const std::string& stage, const module::WaitDeadline& wait) {
    utils::umask(0);
    if (utils::has_magisk()) {
        klog("Magisk detected, skip %s", stage.c_str());
        return;
    }
    bool safe_mode = utils::check_antibrick_count(3) || utils::is_safe_mode();
    if (safe_mode) {
        klog("safe mode, skip %s scripts", stage.c_str());
        return;
    }
    module::exec_common_scripts(stage + ".d", wait);
    metamodule::exec_stage_script(stage, wait);
    module::exec_stage_script(stage, wait);
}

}  // namespace

bool on_post_fs_data() {
    manager::run_boot_scan();
    utils::umask(0);

    config::clear_all_temp_configs();
    catch_bootlog("logcat", {"logcat", "-b", "all"});
    catch_bootlog("dmesg", {"dmesg", "-w", "-r"});

    if (utils::has_magisk()) {
        klog("Magisk detected, skip post-fs-data!");
        return true;
    }

    uint64_t n = utils::write_antibrick_count(0, true);
    klog("anti brick count: %llu", static_cast<unsigned long long>(n));

    bool safe_mode = utils::check_antibrick_count(3) || utils::is_safe_mode();
    auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(35);
    module::WaitDeadline wait;
    wait.kind = module::ScriptWait::Until;
    wait.deadline = deadline;

    if (safe_mode) {
        klog("safe mode, skip common post-fs-data.d scripts");
    } else {
        module::exec_common_scripts("post-fs-data.d", wait);
    }

    if (!assets::ensure_binaries(true)) {
        klog("Failed to extract bin assets");
        return false;
    }
    hot_install::clean_trash();

    if (safe_mode) {
        klog("safe mode, skip post-fs-data scripts and disable all modules!");
        module::disable_all_modules();
        return true;
    }

    module::handle_updated_modules();
    module::prune_modules();
    module::regenerate_preinit();
    restorecon::restorecon();
    module::load_sepolicy_rule();

    metamodule::exec_stage_script("post-fs-data", wait);
    module::exec_stage_script("post-fs-data", wait);
    module::load_system_prop();
    metamodule::exec_mount_script(defs::MODULE_DIR);

    module::WaitDeadline no_wait;
    no_wait.kind = module::ScriptWait::NoWait;
    run_stage("post-mount", no_wait);

    chdir("/");
    return true;
}

void on_services() {
    klog("on_services triggered!");
    module::WaitDeadline no_wait;
    no_wait.kind = module::ScriptWait::NoWait;
    run_stage("service", no_wait);
}

void on_boot_completed() {
    klog("on_boot_completed triggered!");
    preinit::on_boot_completed();

    module::WaitDeadline no_wait;
    no_wait.kind = module::ScriptWait::NoWait;
    run_stage("boot-completed", no_wait);

    // Rust 侧 store::load() 由 JNI/root 落盘子系统负责，这里只做内核上报与防砖清理。
    utils::on_boot_completed_antibrick();
    try {
        kzcall::report_boot_complete();
    } catch (const Error& e) {
        klog("report boot complete failed: %s", e.what());
    }
}

}  // namespace ncore::boot
