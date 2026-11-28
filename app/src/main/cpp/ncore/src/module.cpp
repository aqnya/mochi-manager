// SPDX-License-Identifier: GPL-3.0-or-later
// 模块管理：安装/卸载/启用/禁用、脚本执行、preinit 重建、metamodule 与列表输出。
// 移植自 neko/ncore/src/module/mod.rs。
#include "module.h"

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <limits.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

#include <chrono>
#include <map>
#include <set>
#include <thread>

#include "assets.h"
#include "common.h"
#include "config.h"
#include "defs.h"
#include "hot_install.h"
#include "klog.h"
#include "metamodule.h"
#include "preinit.h"
#include "properties.h"
#include "resetprop.h"
#include "restorecon.h"
#include "sepolicy.h"
#include "unzip.h"
#include "utils.h"

namespace ncore::module {

namespace {

std::string join_path(const std::string& a, const std::string& b) {
    if (a.empty()) return b;
    if (a.back() == '/') return a + b;
    return a + "/" + b;
}

void set_env(const std::map<std::string, std::string>& envs) {
    for (const auto& kv : envs) setenv(kv.first.c_str(), kv.second.c_str(), 1);
}

bool wait_child(pid_t pid, const WaitDeadline& wait) {
    if (wait.kind == ScriptWait::NoWait) return true;
    if (wait.kind == ScriptWait::Forever) {
        int status = 0;
        while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {
        }
        return true;
    }
    // Until: 轮询直到子进程退出或到达截止时间。
    while (true) {
        int status = 0;
        pid_t r = waitpid(pid, &status, WNOHANG);
        if (r == pid) return true;
        if (r < 0 && errno != EINTR) return true;
        if (std::chrono::steady_clock::now() >= wait.deadline) {
            klog("script wait timed out: %d", static_cast<int>(pid));
            return true;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }
}

std::string realpath_or(const std::string& path) {
    char buf[PATH_MAX] = {};
    if (realpath(path.c_str(), buf) != nullptr) return buf;
    return path;
}

bool run_script_argv(const std::vector<std::string>& argv, const std::string& cwd,
                     const std::map<std::string, std::string>& envs, bool wait_for) {
    std::vector<char*> cargs;
    for (const auto& a : argv) cargs.push_back(const_cast<char*>(a.c_str()));
    cargs.push_back(nullptr);
    pid_t pid = fork();
    if (pid < 0) return false;
    if (pid == 0) {
        setsid();
        if (!cwd.empty()) chdir(cwd.c_str());
        set_env(envs);
        utils::switch_cgroups();
        execv(argv[0].c_str(), cargs.data());
        _exit(127);
    }
    if (wait_for) {
        int status = 0;
        while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {
        }
        return WIFEXITED(status) && WEXITSTATUS(status) == 0;
    }
    return true;
}

std::string human_size(uint64_t bytes) {
    static const char* units[] = {"B", "KB", "MB", "GB"};
    double v = static_cast<double>(bytes);
    int i = 0;
    while (v >= 1000.0 && i < 3) {
        v /= 1000.0;
        ++i;
    }
    char buf[64];
    snprintf(buf, sizeof(buf), "%.1f%s", v, units[i]);
    return buf;
}

}  // namespace

bool validate_module_id(const std::string& id) {
    if (id.size() < 2) return false;
    if (!std::isalpha(static_cast<unsigned char>(id[0]))) return false;
    for (char c : id) {
        if (!(std::isalnum(static_cast<unsigned char>(c)) || c == '.' || c == '_' || c == '-')) {
            return false;
        }
    }
    return true;
}

std::map<std::string, std::string> get_common_script_envs(const std::string& module_id) {
    std::map<std::string, std::string> envs = {
        {"ASH_STANDALONE", "1"},
        {"KSU", "true"},
        {"KSU_KERNEL_VER_CODE", "30000"},
        {"KSU_VER_CODE", defs::VERSION_CODE},
        {"KSU_VER", defs::VERSION_NAME},
        {"KSU_UAPI_VER", std::to_string(defs::KERNEL_SU_UAPI_VERSION)},
        {"KSU_RUNTIME_MODE", utils::runtime_mode()},
    };
    std::string path = getenv("PATH") != nullptr ? getenv("PATH") : "";
    std::string bin = defs::BINARY_DIR;
    while (!bin.empty() && bin.back() == '/') bin.pop_back();
    envs["PATH"] = path + ":" + bin;
    if (!module_id.empty()) {
        if (validate_module_id(module_id)) {
            envs["KSU_MODULE"] = module_id;
        } else {
            klog("Invalid module_id provided: %s", module_id.c_str());
        }
    }
    return envs;
}

bool foreach_module(ModuleType type, const std::function<bool(const std::string&)>& fn) {
    const char* modules_dir = type == ModuleType::Updated ? defs::MODULE_UPDATE_DIR : defs::MODULE_DIR;
    DIR* dir = opendir(modules_dir);
    if (dir == nullptr) return false;
    struct dirent* ent;
    while ((ent = readdir(dir)) != nullptr) {
        std::string name = ent->d_name;
        if (name == "." || name == "..") continue;
        std::string path = join_path(modules_dir, name);
        if (!is_dir(path)) {
            klog("%s is not a directory, skip", path.c_str());
            continue;
        }
        if (type == ModuleType::Active && path_exists(join_path(path, defs::DISABLE_FILE_NAME))) continue;
        if (type == ModuleType::Active && path_exists(join_path(path, defs::REMOVE_FILE_NAME))) continue;
        if (!fn(path)) break;  // 回调返回 false 表示提前终止遍历。
    }
    closedir(dir);
    return true;
}

bool exec_script(const std::string& path, const WaitDeadline& wait) {
    klog("exec %s", path.c_str());
    bool is_module_script = path.find(defs::MODULE_DIR) == 0;
    std::string module_id;
    if (is_module_script) {
        std::string rel = path.substr(strlen(defs::MODULE_DIR));
        size_t slash = rel.find('/');
        std::string candidate = slash == std::string::npos ? rel : rel.substr(0, slash);
        if (validate_module_id(candidate)) module_id = candidate;
    }
    auto envs = get_common_script_envs(module_id);

    size_t slash = path.rfind('/');
    std::string cwd = slash == std::string::npos ? "" : path.substr(0, slash);
    std::vector<std::string> argv = {assets::BUSYBOX_PATH, "sh", path};

    std::vector<char*> cargs;
    for (const auto& a : argv) cargs.push_back(const_cast<char*>(a.c_str()));
    cargs.push_back(nullptr);

    pid_t pid = fork();
    if (pid < 0) return false;
    if (pid == 0) {
        setsid();
        if (!cwd.empty()) chdir(cwd.c_str());
        set_env(envs);
        utils::detach_process_group(true);
        utils::switch_cgroups();
        execv(argv[0].c_str(), cargs.data());
        _exit(127);
    }
    return wait_child(pid, wait);
}

bool exec_stage_script(const std::string& stage, const WaitDeadline& wait) {
    std::string meta = metamodule::get_metamodule_path();
    std::string meta_real = meta.empty() ? "" : realpath_or(meta);
    foreach_module(ModuleType::Active, [&](const std::string& module) {
        if (!meta_real.empty() && realpath_or(module) == meta_real) return true;
        std::string script = join_path(module, stage + ".sh");
        if (is_file(script)) exec_script(script, wait);
        return true;
    });
    return true;
}

bool exec_common_scripts(const std::string& dir, const WaitDeadline& wait) {
    std::string script_dir = join_path(defs::ADB_DIR, dir);
    if (!is_dir(script_dir)) {
        klog("%s not exists, skip", script_dir.c_str());
        return true;
    }
    DIR* d = opendir(script_dir.c_str());
    if (d == nullptr) return false;
    struct dirent* ent;
    while ((ent = readdir(d)) != nullptr) {
        std::string name = ent->d_name;
        if (name == "." || name == "..") continue;
        std::string path = join_path(script_dir, name);
        if (access(path.c_str(), X_OK) != 0) continue;
        exec_script(path, wait);
    }
    closedir(d);
    return true;
}

bool load_sepolicy_rule() {
    foreach_module(ModuleType::Active, [](const std::string& module) {
        std::string rule = join_path(module, "sepolicy.rule");
        if (is_file(rule)) sepolicy::apply_file(rule);
        return true;
    });
    return true;
}

bool load_system_prop() {
    foreach_module(ModuleType::Active, [](const std::string& module) {
        std::string prop = join_path(module, "system.prop");
        if (is_file(prop)) resetprop::load_system_prop_file(prop);
        return true;
    });
    return true;
}

std::map<std::string, std::string> read_module_prop(const std::string& module_path) {
    std::string prop = join_path(module_path, "module.prop");
    std::string content;
    if (!read_file_to_string(prop, content)) return {};
    return parse_properties(content);
}

bool prune_modules() {
    foreach_module(ModuleType::All, [](const std::string& module) {
        if (!path_exists(join_path(module, defs::REMOVE_FILE_NAME))) return true;
        klog("remove module: %s", module.c_str());
        std::string id = module.substr(module.find_last_of('/') + 1);
        auto props = read_module_prop(module);
        if (metamodule::is_metamodule(props)) {
            metamodule::remove_symlink();
        } else {
            metamodule::exec_metauninstall_script(id);
        }
        std::string uninstaller = join_path(module, "uninstall.sh");
        if (is_file(uninstaller)) {
            WaitDeadline w;
            w.kind = ScriptWait::Forever;
            exec_script(uninstaller, w);
        }
        config::clear_module_configs(id);
        remove_dir_all(module);
        return true;
    });
    return true;
}

bool disable_all_modules() {
    DIR* d = opendir(defs::MODULE_DIR);
    if (d != nullptr) {
        struct dirent* ent;
        while ((ent = readdir(d)) != nullptr) {
            std::string name = ent->d_name;
            if (name == "." || name == "..") continue;
            std::string path = join_path(defs::MODULE_DIR, name);
            utils::ensure_file_exists(join_path(path, defs::DISABLE_FILE_NAME));
        }
        closedir(d);
    }
    regenerate_preinit();
    return true;
}

const char* preinit_ksu_dir() {
    if (is_dir("/metadata/vold")) return defs::PREINIT_DIR_VOLD;
    if (is_dir("/metadata/watchdog")) return defs::PREINIT_DIR_WATCHDOG;
    return defs::PREINIT_DIR_DEFAULT;
}

const char* preinit_con(const std::string& dir) {
    return dir == defs::PREINIT_DIR_VOLD ? defs::VOLD_METADATA_FILE_CON : defs::METADATA_FILE_CON;
}

namespace {

void collect_rc_files(const std::string& dir, const std::string& mod_id, std::string& out) {
    DIR* d = opendir(dir.c_str());
    if (d == nullptr) return;
    std::vector<std::string> names;
    struct dirent* ent;
    while ((ent = readdir(d)) != nullptr) {
        std::string name = ent->d_name;
        if (name != "." && name != "..") names.push_back(name);
    }
    std::sort(names.begin(), names.end());
    for (const auto& name : names) {
        std::string path = join_path(dir, name);
        struct stat st;
        if (stat(path.c_str(), &st) != 0 || !S_ISREG(st.st_mode)) continue;
        size_t dot = name.rfind('.');
        if (dot == std::string::npos || name.substr(dot + 1) != "rc") continue;
        if (!mod_id.empty()) {
            out += "# === from " + mod_id + ":" + path + " ===\n";
        } else {
            if (access(path.c_str(), X_OK) != 0) continue;
            out += "# === from " + path + " ===\n";
        }
        std::string content;
        if (read_file_to_string(path, content)) {
            out += content;
            out += "\n";
        }
    }
    closedir(d);
}

// 下一次启动启用的模块：modules_update/ 覆盖 modules/，禁用/移除的跳过。
std::map<std::string, std::string> next_boot_modules() {
    std::map<std::string, std::string> enabled;
    std::set<std::string> disabled;
    for (const char* src_dir : {defs::MODULE_UPDATE_DIR, defs::MODULE_DIR}) {
        DIR* d = opendir(src_dir);
        if (d == nullptr) continue;
        struct dirent* ent;
        while ((ent = readdir(d)) != nullptr) {
            std::string name = ent->d_name;
            if (name == "." || name == "..") continue;
            std::string path = join_path(src_dir, name);
            if (!is_dir(path)) continue;
            if (path_exists(join_path(path, defs::DISABLE_FILE_NAME)) ||
                path_exists(join_path(path, defs::REMOVE_FILE_NAME))) {
                disabled.insert(name);
                continue;
            }
            if (!disabled.count(name)) enabled[name] = path;
        }
        closedir(d);
    }
    return enabled;
}

bool regenerate_preinit_rc(const std::string& preinit_dir,
                           const std::map<std::string, std::string>& modules,
                           const std::string& con) {
    if (!mkdirs(preinit_dir, 0755)) return false;
    preinit::secure(preinit_dir, 0755, con);
    std::string tmp = join_path(preinit_dir, defs::MODULES_RC_TMP_FILE);
    std::string out = join_path(preinit_dir, defs::MODULES_RC_FILE);
    std::string content;
    collect_rc_files(join_path(defs::ADB_DIR, "initrc.d"), "", content);
    for (const auto& kv : modules) {
        collect_rc_files(join_path(kv.second, defs::MODULE_INIT_RC_DIR), kv.first, content);
    }
    try {
        write_file(tmp, content);
    } catch (const Error&) {
        return false;
    }
    preinit::secure(tmp, 0644, con);
    if (rename(tmp.c_str(), out.c_str()) != 0) return false;
    preinit::secure(out, 0644, con);
    return true;
}

}  // namespace

bool regenerate_preinit() {
    std::string dir = preinit_ksu_dir();
    std::string con = preinit_con(dir);
    std::vector<std::string> stale;
    for (const char* d : {defs::PREINIT_DIR_VOLD, defs::PREINIT_DIR_WATCHDOG, defs::PREINIT_DIR_DEFAULT}) {
        if (std::string(d) != dir) stale.emplace_back(d);
    }
    auto modules = next_boot_modules();
    bool rc_ok = regenerate_preinit_rc(dir, modules, con);
    for (const auto& d : stale) unlink(join_path(d, defs::MODULES_RC_FILE).c_str());
    bool preinit_ok = preinit::sync(dir, stale, modules, con);
    return rc_ok && preinit_ok;
}

bool handle_updated_modules() {
    foreach_module(ModuleType::Updated, [](const std::string& updated) {
        std::string id = updated.substr(updated.find_last_of('/') + 1);
        std::string live = join_path(defs::MODULE_DIR, id);
        bool disabled = false, removed = false;
        if (path_exists(live)) {
            disabled = path_exists(join_path(live, defs::DISABLE_FILE_NAME));
            removed = path_exists(join_path(live, defs::REMOVE_FILE_NAME));
            remove_dir_all(live);
        }
        if (rename(updated.c_str(), live.c_str()) != 0) return true;
        if (removed) utils::ensure_file_exists(join_path(live, defs::REMOVE_FILE_NAME));
        else if (disabled) utils::ensure_file_exists(join_path(live, defs::DISABLE_FILE_NAME));
        return true;
    });
    return true;
}

namespace {

bool ensure_boot_completed() {
    if (getprop("sys.boot_completed") != "1") {
        printf("- Android is still booting\n");
        return false;
    }
    return true;
}

// 返回安装后的 module id；失败抛 Error。
std::string install_module_to_system(const std::string& zip) {
    if (!ensure_boot_completed()) throw Error("Android is booting");

    auto banner = assets::banner();
    if (banner.size > 0) {
        printf("%.*s", static_cast<int>(banner.size), reinterpret_cast<const char*>(banner.data));
    }
    if (!assets::ensure_binaries(false)) throw Error("Failed to extract assets");
    utils::ensure_dir_exists(defs::WORKING_DIR);
    utils::ensure_dir_exists(defs::BINARY_DIR);

    std::vector<uint8_t> prop_bytes = unzip::extract_to_memory(zip, "module.prop");
    std::string prop_text(reinterpret_cast<const char*>(prop_bytes.data()), prop_bytes.size());
    auto props = parse_properties(prop_text);
    auto id_it = props.find("id");
    if (id_it == props.end()) throw Error("module id not found in module.prop!");
    std::string module_id = trim(id_it->second);
    if (!validate_module_id(module_id)) {
        throw Error("Invalid module ID in module.prop: '" + module_id + "'");
    }

    bool is_meta = metamodule::is_metamodule(props);
    if (!is_meta) {
        bool blocked = false;
        if (!metamodule::check_install_safety(blocked)) {
            printf("\n- Installation Blocked (metamodule with custom installer is active)\n");
            throw Error("Metamodule installation blocked");
        }
    }

    std::string updated_dir = join_path(defs::MODULE_UPDATE_DIR, module_id);
    if (is_meta && metamodule::has_metamodule()) {
        std::string existing = metamodule::get_metamodule_path();
        auto existing_props = read_module_prop(existing);
        std::string existing_id = existing_props.count("id") ? existing_props["id"] : "unknown";
        if (existing_id != module_id) {
            printf("\n- Installation Failed: a metamodule '%s' is already installed\n", existing_id.c_str());
            throw Error("Cannot install multiple metamodules");
        }
    }

    uint64_t size = unzip::uncompressed_size(zip);
    printf("- Module size: %s\n", human_size(size).c_str());

    utils::ensure_dir_exists(defs::MODULE_UPDATE_DIR);
    restorecon::setsyscon(defs::MODULE_UPDATE_DIR);
    printf("- Installing to %s\n", updated_dir.c_str());
    utils::ensure_clean_dir(updated_dir);
    printf("- Extracting module files\n");
    unzip::extract_all(zip, updated_dir);
    unlink(join_path(updated_dir, defs::HOT_INSTALL_MARKER).c_str());

    std::string system_dir = join_path(updated_dir, "system");
    if (is_dir(system_dir)) {
        chmod(system_dir.c_str(), 0755);
        restorecon::restore_syscon(system_dir);
    }

    printf("- Running module installer\n");
    auto installer = assets::installer_sh();
    std::string installer_content(reinterpret_cast<const char*>(installer.data), installer.size);
    std::string install_script = installer_content + "\ninstall_module\nexit 0\n";
    std::string script = metamodule::get_install_script(is_meta, installer_content, install_script);
    std::string real_zip = realpath_or(zip);
    auto envs = get_common_script_envs(module_id);
    envs["OUTFD"] = "1";
    envs["ZIPFILE"] = real_zip;
    std::vector<std::string> argv = {assets::BUSYBOX_PATH, "sh", "-c", script};
    if (!run_script_argv(argv, updated_dir, envs, true)) throw Error("Failed to install module script");

    auto request = hot_install::take_request(updated_dir);
    if (request.valid && hot_install::apply(module_id, is_meta, request)) {
        printf("- Module installed successfully, no reboot required!\n");
        return module_id;
    }

    std::string live_dir = join_path(defs::MODULE_DIR, module_id);
    unlink(join_path(live_dir, defs::HOT_INSTALL_MARKER).c_str());
    utils::ensure_dir_exists(live_dir);
    std::string src_prop = join_path(updated_dir, "module.prop");
    std::string dst_prop = join_path(live_dir, "module.prop");
    std::string content;
    if (read_file_to_string(src_prop, content)) write_file(dst_prop, content);
    utils::ensure_file_exists(join_path(live_dir, defs::UPDATE_FILE_NAME));
    if (is_meta) {
        printf("- Creating metamodule symlink\n");
        metamodule::ensure_symlink(live_dir);
    }
    printf("- Module installed successfully!\n");
    return module_id;
}

}  // namespace

bool install_module(const std::string& zip) {
    try {
        std::string id = install_module_to_system(zip);
        if (!regenerate_preinit()) klog("regenerate preinit failed after install");
        auto status = preinit::status_of(id);
        if (!status.first.empty() && status.first != "ok") {
            printf("! preinit will not take effect: %s %s\n", status.first.c_str(), status.second.c_str());
        }
        return true;
    } catch (const Error& e) {
        printf("- Error: %s\n", e.what());
        return false;
    }
}

bool undo_uninstall_module(const std::string& id) {
    if (!validate_module_id(id)) return false;
    std::string path = join_path(defs::MODULE_DIR, id);
    if (!path_exists(path)) {
        printf("Module %s not found\n", id.c_str());
        return false;
    }
    std::string remove = join_path(path, defs::REMOVE_FILE_NAME);
    if (path_exists(remove)) unlink(remove.c_str());
    regenerate_preinit();
    return true;
}

bool uninstall_module(const std::string& id) {
    if (!validate_module_id(id)) return false;
    std::string path = join_path(defs::MODULE_DIR, id);
    if (!path_exists(path)) {
        printf("Module %s not found\n", id.c_str());
        return false;
    }
    utils::ensure_file_exists(join_path(path, defs::REMOVE_FILE_NAME));
    regenerate_preinit();
    return true;
}

bool enable_module(const std::string& id) {
    if (!validate_module_id(id)) return false;
    std::string path = join_path(defs::MODULE_DIR, id);
    if (!path_exists(path)) {
        printf("Module %s not found\n", id.c_str());
        return false;
    }
    std::string disable = join_path(path, defs::DISABLE_FILE_NAME);
    if (path_exists(disable)) unlink(disable.c_str());
    regenerate_preinit();
    return true;
}

bool disable_module(const std::string& id) {
    std::string path = join_path(defs::MODULE_DIR, id);
    if (!path_exists(path)) {
        printf("Module %s not found\n", id.c_str());
        return false;
    }
    utils::ensure_file_exists(join_path(path, defs::DISABLE_FILE_NAME));
    regenerate_preinit();
    return true;
}

bool run_action(const std::string& id) {
    if (!validate_module_id(id)) return false;
    std::string script = join_path(defs::MODULE_DIR, id + "/" + defs::MODULE_ACTION_SH);
    WaitDeadline w;
    w.kind = ScriptWait::Forever;
    return exec_script(script, w);
}

bool list_modules() {
    auto all_configs = config::get_all_module_configs();
    auto preinit_states = preinit::module_states();
    std::vector<JsonObject> modules;

    DIR* d = opendir(defs::MODULE_DIR);
    if (d == nullptr) {
        printf("[]\n");
        return true;
    }
    struct dirent* ent;
    while ((ent = readdir(d)) != nullptr) {
        std::string name = ent->d_name;
        if (name == "." || name == "..") continue;
        std::string path = join_path(defs::MODULE_DIR, name);
        if (!path_exists(join_path(path, "module.prop"))) continue;
        auto props = read_module_prop(path);
        if (!props.count("id") || props["id"].empty()) props["id"] = name;

        bool enabled = !path_exists(join_path(path, defs::DISABLE_FILE_NAME));
        bool update = path_exists(join_path(path, defs::UPDATE_FILE_NAME));
        bool remove = path_exists(join_path(path, defs::REMOVE_FILE_NAME));
        bool web = is_dir(join_path(path, defs::MODULE_WEB_DIR));
        bool action = path_exists(join_path(path, defs::MODULE_ACTION_SH));
        bool has_system = is_dir(join_path(path, "system"));
        bool skip_mount = path_exists(join_path(path, "skip_mount"));
        bool need_mount = has_system && !skip_mount;
        bool meta = metamodule::is_metamodule(props);

        auto bool_field = [](bool v) { return JsonValue::bool_value(v); };
        JsonObject obj;
        for (const auto& kv : props) {
            obj.emplace_back(kv.first, JsonValue::string_value(kv.second));
        }
        // 用固定顺序的布尔字段覆盖字符串版本，与 serde_json 输出对齐。
        auto upsert = [&](const std::string& key, JsonValue value) {
            for (auto& kv : obj) {
                if (kv.first == key) {
                    kv.second = std::move(value);
                    return;
                }
            }
            obj.emplace_back(key, std::move(value));
        };
        upsert("enabled", bool_field(enabled));
        upsert("update", bool_field(update));
        upsert("remove", bool_field(remove));
        upsert("web", bool_field(web));
        upsert("action", bool_field(action));
        upsert("mount", bool_field(need_mount));
        upsert("hasWebUi", bool_field(web));
        upsert("hasActionScript", bool_field(action));
        upsert("hasSystem", bool_field(has_system));
        upsert("skipMount", bool_field(skip_mount));
        upsert("metamodule", bool_field(meta));

        auto id_it = props.find("id");
        if (id_it != props.end()) {
            auto st = preinit_states.find(id_it->second);
            if (st != preinit_states.end()) upsert("preinit", JsonValue::string_value(st->second));
        }

        auto cfg_it = all_configs.find(props.count("id") ? props["id"] : "");
        if (cfg_it != all_configs.end()) {
            auto desc = cfg_it->second.find("override.description");
            if (desc != cfg_it->second.end()) upsert("description", JsonValue::string_value(desc->second));
            std::vector<std::string> managed;
            for (const auto& c : cfg_it->second) {
                if (c.first.find("manage.") == 0 && config::parse_bool_config(c.second)) {
                    managed.push_back(c.first.substr(7));
                }
            }
            if (!managed.empty()) {
                std::string joined;
                for (size_t i = 0; i < managed.size(); ++i) {
                    if (i) joined += ",";
                    joined += managed[i];
                }
                upsert("managedFeatures", JsonValue::string_value(joined));
            }
        }
        modules.push_back(std::move(obj));
    }
    closedir(d);
    printf("%s\n", json_array_pretty(modules).c_str());
    return true;
}

}  // namespace ncore::module
