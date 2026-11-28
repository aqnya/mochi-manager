/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * NekoSU profile/feature store C ABI.
 *
 * libstore persists the profile and feature tables used by NekoSU's userspace
 * runtime in /data/adb/nksu/profile. C callers exchange plain structs; the
 * protobuf encoding used internally by the JNI bridge is not part of this ABI.
 *
 * Functions return NKSU_STORE_OK (0) on success and a negative
 * NKSU_STORE_ERR_* code on failure. After a failure, nksu_store_last_error()
 * describes the problem for the calling thread. Writes require root and set
 * the real, effective and saved uid/gid to 0 before touching the store.
 *
 * Writes through this library are serialized. nksu_store_last_error() is
 * thread-local. The *_list functions return a single malloc-owned block that
 * must be released with nksu_store_free(); an empty table yields NULL and a
 * count of 0.
 */

#ifndef NKSU_STORE_H
#define NKSU_STORE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Version of this ABI. Keep in sync with NKSU_STORE_ABI_VERSION in cabi.rs. */
#define NKSU_STORE_ABI_VERSION 1u

enum nksu_store_status {
    /* Operation completed. */
    NKSU_STORE_OK = 0,
    /* A NULL required pointer, an impossible length, or an invalid field. */
    NKSU_STORE_ERR_INVALID_ARGUMENT = -1,
    /* The store could not be read or written. */
    NKSU_STORE_ERR_IO = -2,
    /* The operation needs the NekoSU kernel interface (Android-only). */
    NKSU_STORE_ERR_UNSUPPORTED = -3,
};

/*
 * Entry struct layouts are part of the ABI. Bump NKSU_STORE_ABI_VERSION when a
 * field is added, removed or reordered.
 */

/* One persisted profile, matching me.nekosu.aqnya.proto.ProfileEntry. */
typedef struct nksu_profile_entry {
    uint32_t uid;
    uint32_t root_uid;
    uint64_t caps;
    int32_t ns;
    /*
     * NUL-terminated UTF-8 domain.
     *
     * As input, NULL is treated as the empty string and the library copies the
     * bytes before returning. In list results, this points into the list's
     * allocation and is valid until nksu_store_free() is called on the list.
     */
    const char *domain;
} nksu_profile_entry;

/* One persisted feature, matching me.nekosu.aqnya.proto.FeatureEntry. */
typedef struct nksu_feature_entry {
    int32_t id;
    uint64_t value;
} nksu_feature_entry;

/* Return the ABI version implemented by the loaded library. */
uint32_t nksu_store_abi_version(void);

/*
 * Return the last error recorded on the calling thread, or an empty string.
 *
 * The pointer remains valid until the next libstore call on the same thread and
 * must not be freed.
 */
const char *nksu_store_last_error(void);

/*
 * Release a struct array returned by nksu_store_profile_list() or
 * nksu_store_feature_list(). Passing NULL is allowed.
 */
void nksu_store_free(void *data);

/*
 * Read the on-disk store and apply every profile and feature to the kernel.
 * Non-Android builds report NKSU_STORE_ERR_UNSUPPORTED because applying entries
 * needs the NekoSU kernel interface.
 */
int32_t nksu_store_load(void);

/*
 * Insert or replace one persisted profile, keyed by uid.
 *
 * Persisting a row does not change the live kernel policy; call
 * nksu_store_load() to apply the persisted table.
 */
int32_t nksu_store_profile_save(const nksu_profile_entry *entry);

/*
 * Delete the persisted profile for uid. Missing entries are not an error.
 * A profile already active in the kernel is not removed by this call.
 */
int32_t nksu_store_profile_delete(uint32_t uid);

/*
 * Copy the whole profile table into a malloc-owned struct array.
 *
 * On success *out_entries points to *out_count entries and must be released
 * with nksu_store_free(). When the table is empty, *out_entries is NULL and
 * *out_count is 0. Each entry's domain points into the same allocation.
 */
int32_t nksu_store_profile_list(nksu_profile_entry **out_entries, size_t *out_count);

/*
 * Replace the persisted profile table with count caller-provided rows.
 * entries may be NULL only when count is 0 (which clears the table).
 */
int32_t nksu_store_profile_replace(const nksu_profile_entry *entries, size_t count);

/*
 * Insert or replace one persisted feature, keyed by id.
 *
 * Persisting a row does not change the live kernel feature; call
 * nksu_store_load() to apply the persisted table.
 */
int32_t nksu_store_feature_save(const nksu_feature_entry *entry);

/*
 * Delete the persisted feature with id. Missing entries are not an error.
 * A feature already active in the kernel is not removed by this call.
 */
int32_t nksu_store_feature_delete(int32_t id);

/*
 * Copy the whole feature table into a malloc-owned struct array.
 *
 * On success *out_entries points to *out_count entries and must be released
 * with nksu_store_free(). When the table is empty, *out_entries is NULL and
 * *out_count is 0.
 */
int32_t nksu_store_feature_list(nksu_feature_entry **out_entries, size_t *out_count);

/*
 * Replace the persisted feature table with count caller-provided rows.
 * entries may be NULL only when count is 0 (which clears the table).
 */
int32_t nksu_store_feature_replace(const nksu_feature_entry *entries, size_t count);

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* NKSU_STORE_H */
