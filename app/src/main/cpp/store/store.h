// SPDX-License-Identifier: GPL-3.0-or-later
//
// NekoSU profile 落盘存储（libstore）——上游 neko/store 的 C++ 单一实现。
//
// 内核的授权/profile 表是易失的，重启后 ncore 读 /data/adb/nksu/profile 灌回去。
// 管理器（JNI，fork + setresuid 临时提权后调用）与 Rust 侧（ncore 守护进程本身是
// root，直接调用）共用这一个库，保证两边写出的文件格式与持久化语义完全一致。
//
// 文件布局（全部小端，对齐 neko/store/src/db.rs）：
//
//   header(24B) | profile 索引(16B/条) | feature 索引(16B/条) | profile 载荷 | feature 载荷
//
// header = "NKSUPRF\0" + 版本(u32) + profile 数(u32) + feature 数(u32)；
// 索引 = key(u32) + 绝对偏移(u64) + 长度(u32)；载荷 = protobuf 的
// ProfileEntry{uid=1, root_uid=2, caps=3, ns=4, domain=5} /
// FeatureEntry{id=1, value=2}。
//
// 持久化语义对齐上游 persist()：临时文件（0600）→ fsync → rename → 目录 fsync，
// 中途断电不会留下半个文件。
//
// Rust 侧绑定示例（ncore 里）：
//
//   #[repr(C)]
//   struct NksuProfile {
//       uid: u32,
//       root_uid: u32,
//       caps: u64,
//       ns: i32,
//       domain: [c_char; NKSU_STORE_DOMAIN_MAX],
//   }
//   extern "C" {
//       fn nksu_store_upsert(entry: *const NksuProfile) -> i32;
//       fn nksu_store_delete(uid: u32) -> i32;
//       fn nksu_store_count() -> i32;
//       fn nksu_store_get(index: i32, out: *mut NksuProfile) -> i32;
//   }

#ifndef NKSU_STORE_H
#define NKSU_STORE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define NKSU_STORE_PATH "/data/adb/nksu/profile"
#define NKSU_STORE_DOMAIN_MAX 128

typedef struct {
    uint32_t uid;
    uint32_t root_uid;
    uint64_t caps;
    int32_t ns;
    char domain[NKSU_STORE_DOMAIN_MAX]; /* NUL 结尾，超长截断 */
} nksu_profile;

/* 读改写：加载存储文件，按 uid upsert 一条 profile，原子写回。
 * 返回 0 成功，负值为错误码。要求调用进程对路径有访问权限
 * （已经是 root，或像管理器那样先 setresuid(0,0,0) 提权）。 */
int32_t nksu_store_upsert(const nksu_profile* entry);

/* 删除指定 uid 的条目并原子写回；条目不存在也算成功（幂等）。
 * 返回 0 成功，负值为错误码。 */
int32_t nksu_store_delete(uint32_t uid);

/* 当前 profile 条目数；失败返回负值。 */
int32_t nksu_store_count(void);

/* 按下标取一条 profile；越界返回 1，成功返回 0。 */
int32_t nksu_store_get(int32_t index, nksu_profile* out);

#ifdef __cplusplus
}
#endif

#endif /* NKSU_STORE_H */
