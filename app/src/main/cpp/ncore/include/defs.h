// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <string>

namespace ncore::defs {

// 版本信息由 CMake 通过编译定义注入（对齐 Rust build.rs 的 git 计数 + describe）。
#ifndef NKSU_VERSION_CODE
#define NKSU_VERSION_CODE "30000"
#endif
#ifndef NKSU_VERSION_NAME
#define NKSU_VERSION_NAME "unknown"
#endif
#ifndef NKSU_MANAGER_CERTHASH
#define NKSU_MANAGER_CERTHASH ""
#endif

inline constexpr const char* VERSION_CODE = NKSU_VERSION_CODE;
inline constexpr const char* VERSION_NAME = NKSU_VERSION_NAME;
inline constexpr const char* MANAGER_CERT_HASH = NKSU_MANAGER_CERTHASH;
inline constexpr unsigned KERNEL_SU_UAPI_VERSION = 5;

inline constexpr const char* ADB_DIR = "/data/adb/";
inline constexpr const char* WORKING_DIR = "/data/adb/nksu/";
inline constexpr const char* BINARY_DIR = "/data/adb/nksu/bin/";
inline constexpr const char* LOG_DIR = "/data/adb/nksu/log/";
inline constexpr const char* KSURC_PATH = "/data/adb/nksu/.ksurc";
inline constexpr const char* DAEMON_PATH = "/data/adb/ncore";
inline constexpr const char* DAEMON_LINK_PATH = "/data/adb/nksu/bin/ncore";

inline constexpr const char* MODULE_DIR = "/data/adb/modules/";
inline constexpr const char* MODULE_UPDATE_DIR = "/data/adb/modules_update/";
inline constexpr const char* METAMODULE_DIR = "/data/adb/metamodule/";
inline constexpr const char* HOT_INSTALL_TRASH_DIR = "/data/adb/nksu/hot_install_trash/";

inline constexpr const char* PREINIT_DIR_VOLD = "/metadata/vold/nksu/";
inline constexpr const char* PREINIT_DIR_WATCHDOG = "/metadata/watchdog/nksu/";
inline constexpr const char* PREINIT_DIR_DEFAULT = "/metadata/nksu/";
inline constexpr const char* METADATA_FILE_CON = "u:object_r:metadata_file:s0";
inline constexpr const char* VOLD_METADATA_FILE_CON = "u:object_r:vold_metadata_file:s0";

inline constexpr const char* PREINIT_SUBDIR = "preinit";
inline constexpr const char* MODULES_RC_FILE = "modules.rc";
inline constexpr const char* MODULES_RC_TMP_FILE = ".modules.rc.tmp";
inline constexpr const char* PREINIT_MANIFEST_FILE = ".manifest";
inline constexpr const char* PREINIT_BOOTCOUNT_FILE = ".preinit_bootcount";
inline constexpr const char* PREINIT_STATUS_PATH = "/data/adb/nksu/preinit_status";

inline constexpr const char* MODULE_WEB_DIR = "webroot";
inline constexpr const char* MODULE_ACTION_SH = "action.sh";
inline constexpr const char* DISABLE_FILE_NAME = "disable";
inline constexpr const char* UPDATE_FILE_NAME = "update";
inline constexpr const char* REMOVE_FILE_NAME = "remove";
inline constexpr const char* MODULE_INIT_RC_DIR = "initrc";
inline constexpr const char* MODULE_PREINIT_DIR = "preinit";
inline constexpr const char* PREINIT_CONF = "preinit.conf";
inline constexpr const char* HOT_INSTALL_MARKER = ".ksu_hot_install";

inline constexpr const char* MODULE_CONFIG_DIR = "/data/adb/nksu/module_configs/";
inline constexpr const char* PERSIST_CONFIG_NAME = "persist.config";
inline constexpr const char* TEMP_CONFIG_NAME = "tmp.config";

inline constexpr const char* METAMODULE_MOUNT_SCRIPT = "metamount.sh";
inline constexpr const char* METAMODULE_METAINSTALL_SCRIPT = "metainstall.sh";
inline constexpr const char* METAMODULE_METAUNINSTALL_SCRIPT = "metauninstall.sh";

inline constexpr const char* ANTIBRICK_PATH = "/data/adb/nksu/antibrick";

}  // namespace ncore::defs
