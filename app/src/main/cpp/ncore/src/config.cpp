// SPDX-License-Identifier: GPL-3.0-or-later
#include "config.h"

#include <cstring>
#include <dirent.h>
#include <unistd.h>

#include "common.h"
#include "defs.h"
#include "module.h"

namespace ncore::config {

namespace {
constexpr uint32_t MAGIC = 0x4b53554d;  // "KSUM"
constexpr uint32_t VERSION = 1;
constexpr size_t MAX_KEY_LEN = 256;
constexpr size_t MAX_VALUE_LEN = 1024 * 1024;
constexpr size_t MAX_COUNT = 32;

const char* filename(ConfigType type) {
    return type == ConfigType::Persist ? defs::PERSIST_CONFIG_NAME : defs::TEMP_CONFIG_NAME;
}

std::string config_dir(const std::string& id) {
    return std::string(defs::MODULE_CONFIG_DIR) + id;
}
}  // namespace

bool parse_bool_config(const std::string& value) {
    std::string t = trim(value);
    return to_lower(t) == "true" || t == "1";
}

std::map<std::string, std::string> load_config(const std::string& module_id, ConfigType type) {
    std::map<std::string, std::string> out;
    if (!module::validate_module_id(module_id)) return out;
    std::string path = config_dir(module_id) + "/" + filename(type);
    std::vector<uint8_t> data;
    try {
        data = read_file(path);
    } catch (const Error&) {
        return out;
    }
    if (data.size() < 12) return out;
    auto u32at = [&](size_t off) {
        uint32_t v = 0;
        std::memcpy(&v, data.data() + off, 4);
        return v;
    };
    if (u32at(0) != MAGIC || u32at(4) != VERSION) return out;
    uint32_t count = u32at(8);
    if (count > MAX_COUNT) return out;
    size_t pos = 12;
    for (uint32_t i = 0; i < count; ++i) {
        if (pos + 4 > data.size()) return out;
        size_t key_len = u32at(pos);
        pos += 4;
        if (key_len > MAX_KEY_LEN || pos + key_len > data.size()) return out;
        std::string key(reinterpret_cast<const char*>(data.data() + pos), key_len);
        pos += key_len;
        if (pos + 4 > data.size()) return out;
        size_t value_len = u32at(pos);
        pos += 4;
        if (value_len > MAX_VALUE_LEN || pos + value_len > data.size()) return out;
        std::string value(reinterpret_cast<const char*>(data.data() + pos), value_len);
        pos += value_len;
        out[key] = value;
    }
    return out;
}

std::map<std::string, std::string> merge_configs(const std::string& module_id) {
    auto merged = load_config(module_id, ConfigType::Persist);
    auto temp = load_config(module_id, ConfigType::Temp);
    for (const auto& kv : temp) merged[kv.first] = kv.second;
    return merged;
}

std::map<std::string, std::map<std::string, std::string>> get_all_module_configs() {
    std::map<std::string, std::map<std::string, std::string>> all;
    DIR* d = opendir(defs::MODULE_CONFIG_DIR);
    if (d == nullptr) return all;
    struct dirent* ent;
    while ((ent = readdir(d)) != nullptr) {
        std::string name = ent->d_name;
        if (name == "." || name == "..") continue;
        if (!is_dir(std::string(defs::MODULE_CONFIG_DIR) + name)) continue;
        auto cfg = merge_configs(name);
        if (!cfg.empty()) all[name] = cfg;
    }
    closedir(d);
    return all;
}

bool clear_all_temp_configs() {
    DIR* d = opendir(defs::MODULE_CONFIG_DIR);
    if (d == nullptr) return true;
    struct dirent* ent;
    while ((ent = readdir(d)) != nullptr) {
        std::string name = ent->d_name;
        if (name == "." || name == "..") continue;
        std::string path = std::string(defs::MODULE_CONFIG_DIR) + name + "/" + defs::TEMP_CONFIG_NAME;
        unlink(path.c_str());
    }
    closedir(d);
    return true;
}

bool clear_module_configs(const std::string& module_id) {
    if (!module::validate_module_id(module_id)) return false;
    return remove_dir_all(config_dir(module_id));
}

}  // namespace ncore::config
