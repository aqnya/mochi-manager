// SPDX-License-Identifier: GPL-3.0-or-later
#include "metamodule.h"

#include <cstdlib>
#include <sys/wait.h>
#include <unistd.h>

#include "assets.h"
#include "common.h"
#include "defs.h"
#include "klog.h"
#include "module.h"

namespace ncore::metamodule {

namespace {
bool is_truthy(const std::string& s) {
    std::string t = trim(s);
    return t == "1" || to_lower(t) == "true";
}
}  // namespace

bool is_metamodule(const std::map<std::string, std::string>& props) {
    auto it = props.find("metamodule");
    return it != props.end() && is_truthy(it->second);
}

std::string get_metamodule_path() {
    std::string path = defs::METAMODULE_DIR;
    // 去掉尾部斜杠。
    while (!path.empty() && path.back() == '/') path.pop_back();
    if (is_symlink(path)) {
        char buf[4096] = {};
        ssize_t n = readlink(path.c_str(), buf, sizeof(buf) - 1);
        if (n > 0) {
            buf[n] = '\0';
            std::string target = buf;
            std::string resolved = target;
            if (target[0] != '/') {
                size_t slash = path.rfind('/');
                resolved = (slash == std::string::npos ? "" : path.substr(0, slash)) + "/" + target;
            }
            if (is_dir(resolved)) return resolved;
        }
    }
    // fallback：遍历 modules 找 metamodule=1。
    std::string found;
    module::foreach_module(module::ModuleType::All, [&](const std::string& p) {
        auto props = module::read_module_prop(p);
        if (is_metamodule(props)) {
            found = p;
            return false;  // 提前终止遍历
        }
        return true;  // 继续
    });
    return found;
}

std::string get_metamodule_id() {
    std::string path = get_metamodule_path();
    if (path.empty()) return {};
    size_t slash = path.rfind('/');
    return slash == std::string::npos ? path : path.substr(slash + 1);
}

bool has_metamodule() { return !get_metamodule_path().empty(); }

bool check_install_safety(bool& blocked) {
    blocked = false;
    std::string path = get_metamodule_path();
    if (path.empty()) return true;
    bool has_metainstall = is_file(path + "/" + defs::METAMODULE_METAINSTALL_SCRIPT);
    std::string id = get_metamodule_id();
    if (!id.empty()) {
        has_metainstall = has_metainstall ||
                          is_file(std::string(defs::MODULE_UPDATE_DIR) + id + "/" + defs::METAMODULE_METAINSTALL_SCRIPT);
    }
    if (!has_metainstall) return true;
    bool has_update = is_file(path + "/" + defs::UPDATE_FILE_NAME);
    bool has_remove = is_file(path + "/" + defs::REMOVE_FILE_NAME);
    bool has_disable = is_file(path + "/" + defs::DISABLE_FILE_NAME);
    if (!has_update && !has_remove && !has_disable) return true;
    blocked = has_disable && !has_update && !has_remove;
    return false;
}

bool ensure_symlink(const std::string& module_path) {
    std::string link = defs::METAMODULE_DIR;
    while (!link.empty() && link.back() == '/') link.pop_back();
    if (path_exists(link) || is_symlink(link)) {
        if (is_symlink(link)) unlink(link.c_str());
        else remove_dir_all(link);
    }
    return symlink(module_path.c_str(), link.c_str()) == 0;
}

bool remove_symlink() {
    std::string link = defs::METAMODULE_DIR;
    while (!link.empty() && link.back() == '/') link.pop_back();
    if (is_symlink(link)) return unlink(link.c_str()) == 0;
    return true;
}

std::string get_install_script(bool is_metamodule_, const std::string& installer_content,
                               const std::string& install_module_script) {
    if (is_metamodule_) return install_module_script;
    std::string path = get_metamodule_path();
    if (path.empty()) return install_module_script;
    if (is_file(path + "/" + defs::DISABLE_FILE_NAME)) return install_module_script;
    std::string metainstall = path + "/" + defs::METAMODULE_METAINSTALL_SCRIPT;
    std::string content;
    if (read_file_to_string(metainstall, content)) {
        return installer_content + "\n" + content + "\nexit 0\n";
    }
    return install_module_script;
}

namespace {
std::string check_metamodule_script(const std::string& script_name) {
    std::string path = get_metamodule_path();
    if (path.empty()) return {};
    if (is_file(path + "/" + defs::DISABLE_FILE_NAME)) return {};
    std::string script = path + "/" + script_name;
    if (!is_file(script)) return {};
    return script;
}

bool run_busybox_script(const std::string& script, const std::string& cwd,
                        const std::map<std::string, std::string>& envs) {
    std::vector<std::string> argv = {assets::BUSYBOX_PATH, "sh", script};
    std::vector<char*> cargs;
    for (auto& a : argv) cargs.push_back(const_cast<char*>(a.c_str()));
    cargs.push_back(nullptr);

    pid_t pid = fork();
    if (pid < 0) return false;
    if (pid == 0) {
        if (!cwd.empty()) chdir(cwd.c_str());
        for (const auto& kv : envs) setenv(kv.first.c_str(), kv.second.c_str(), 1);
        execv(argv[0].c_str(), cargs.data());
        _exit(127);
    }
    int status = 0;
    waitpid(pid, &status, 0);
    return WIFEXITED(status) && WEXITSTATUS(status) == 0;
}
}  // namespace

bool exec_stage_script(const std::string& stage, const module::WaitDeadline& wait) {
    std::string script = check_metamodule_script(stage + ".sh");
    if (script.empty()) return true;
    return module::exec_script(script, wait);
}

bool exec_mount_script(const std::string& module_dir) {
    std::string script = check_metamodule_script(defs::METAMODULE_MOUNT_SCRIPT);
    if (script.empty()) return true;
    auto envs = module::get_common_script_envs(get_metamodule_id());
    envs["MODULE_DIR"] = module_dir;
    size_t slash = script.rfind('/');
    return run_busybox_script(script, slash == std::string::npos ? "" : script.substr(0, slash), envs);
}

bool exec_metauninstall_script(const std::string& module_id) {
    std::string script = check_metamodule_script(defs::METAMODULE_METAUNINSTALL_SCRIPT);
    if (script.empty()) return true;
    auto envs = module::get_common_script_envs(get_metamodule_id());
    envs["MODULE_ID"] = module_id;
    size_t slash = script.rfind('/');
    return run_busybox_script(script, slash == std::string::npos ? "" : script.substr(0, slash), envs);
}

}  // namespace ncore::metamodule
