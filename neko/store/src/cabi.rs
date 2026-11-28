// SPDX-License-Identifier: GPL-3.0-or-later
//! C ABI for the NekoSU profile/feature store.
//!
//! The exported functions and the `#[repr(C)]` entry structs are declared in
//! `include/nksu_store.h`. C callers pass and receive plain structs; the
//! protobuf encoding used by the JNI bridge stays an internal detail.
//!
//! Store operations return [`NKSU_STORE_OK`] on success and a negative
//! `NKSU_STORE_ERR_*` code on failure. A failed call also records a
//! thread-local message that [`nksu_store_last_error`] returns.

use std::cell::RefCell;
use std::ffi::{CStr, CString, c_char, c_void};
use std::io;
use std::mem::size_of;
use std::ptr;
use std::slice;

use proto::{FeatureEntry, FeatureStore, ProfileEntry, ProfileStore};

use crate::{feature, profile};

/// The ABI described by `include/nksu_store.h`.
pub const NKSU_STORE_ABI_VERSION: u32 = 1;

pub const NKSU_STORE_OK: i32 = 0;
/// A NULL required pointer, an impossible length, or an invalid field.
pub const NKSU_STORE_ERR_INVALID_ARGUMENT: i32 = -1;
/// The on-disk store could not be read or written.
pub const NKSU_STORE_ERR_IO: i32 = -2;
/// The operation needs the NekoSU kernel interface, which is Android-only.
pub const NKSU_STORE_ERR_UNSUPPORTED: i32 = -3;

/// One profile row, matching `proto::ProfileEntry`.
#[repr(C)]
#[derive(Clone, Copy)]
pub struct NksuProfileEntry {
    pub uid: u32,
    pub root_uid: u32,
    pub caps: u64,
    pub ns: i32,
    /// NUL-terminated UTF-8. For input, NULL is treated as the empty string.
    /// For list results, it points into the list's allocation.
    pub domain: *const c_char,
}

/// One feature row, matching `proto::FeatureEntry`.
#[repr(C)]
#[derive(Clone, Copy)]
pub struct NksuFeatureEntry {
    pub id: i32,
    pub value: u64,
}

thread_local! {
    static LAST_ERROR: RefCell<CString> = RefCell::new(CString::default());
}

fn clear_last_error() {
    LAST_ERROR.with(|slot| {
        let mut slot = slot.borrow_mut();
        if !slot.as_bytes().is_empty() {
            *slot = CString::default();
        }
    });
}

fn set_last_error(message: &str) {
    // A CString cannot contain interior NULs. Error text may originate from the
    // OS or a decoder, so replace them instead of letting CString::new fail.
    let message = CString::new(message.replace('\0', " ")).unwrap_or_default();
    LAST_ERROR.with(|slot| *slot.borrow_mut() = message);
}

fn invalid_argument(message: impl Into<String>) -> i32 {
    set_last_error(&message.into());
    NKSU_STORE_ERR_INVALID_ARGUMENT
}

fn io_error(context: &str, error: &io::Error) -> i32 {
    let code = if error.kind() == io::ErrorKind::Unsupported {
        NKSU_STORE_ERR_UNSUPPORTED
    } else {
        NKSU_STORE_ERR_IO
    };
    set_last_error(&format!("{context}: {error}"));
    code
}

fn out_of_memory() -> io::Error {
    io::Error::from(io::ErrorKind::OutOfMemory)
}

fn succeed() -> i32 {
    clear_last_error();
    NKSU_STORE_OK
}

fn profile_from_c(entry: &NksuProfileEntry) -> Result<ProfileEntry, i32> {
    let domain = if entry.domain.is_null() {
        String::new()
    } else {
        // SAFETY: the FFI contract requires a NUL-terminated string.
        let domain = unsafe { CStr::from_ptr(entry.domain) };
        domain
            .to_str()
            .map_err(|_| invalid_argument("profile domain is not valid UTF-8"))?
            .to_owned()
    };
    Ok(ProfileEntry {
        uid: entry.uid,
        root_uid: entry.root_uid,
        caps: entry.caps,
        ns: entry.ns,
        domain,
    })
}

fn convert_profile_entries(
    entries: *const NksuProfileEntry,
    count: usize,
) -> Result<Vec<ProfileEntry>, i32> {
    if count == 0 {
        return Ok(Vec::new());
    }
    if entries.is_null() {
        return Err(invalid_argument(
            "profile entries pointer is NULL with a non-zero count",
        ));
    }
    if count > u32::MAX as usize {
        return Err(invalid_argument("too many profile entries"));
    }

    // SAFETY: the FFI contract requires `count` valid entries at `entries`.
    let entries = unsafe { slice::from_raw_parts(entries, count) };
    let mut converted = Vec::new();
    if converted.try_reserve(count).is_err() {
        return Err(io_error("allocate profile entries", &out_of_memory()));
    }
    for entry in entries {
        converted.push(profile_from_c(entry)?);
    }
    Ok(converted)
}

fn convert_feature_entries(
    entries: *const NksuFeatureEntry,
    count: usize,
) -> Result<Vec<FeatureEntry>, i32> {
    if count == 0 {
        return Ok(Vec::new());
    }
    if entries.is_null() {
        return Err(invalid_argument(
            "feature entries pointer is NULL with a non-zero count",
        ));
    }
    if count > u32::MAX as usize {
        return Err(invalid_argument("too many feature entries"));
    }

    // SAFETY: the FFI contract requires `count` valid entries at `entries`.
    let entries = unsafe { slice::from_raw_parts(entries, count) };
    let mut converted = Vec::new();
    if converted.try_reserve(count).is_err() {
        return Err(io_error("allocate feature entries", &out_of_memory()));
    }
    converted.extend(entries.iter().map(|entry| FeatureEntry {
        id: entry.id,
        value: entry.value,
    }));
    Ok(converted)
}

/// Allocate one block holding the entry array followed by its domain strings.
fn alloc_profile_list(entries: &[ProfileEntry]) -> io::Result<*mut NksuProfileEntry> {
    if entries.is_empty() {
        return Ok(ptr::null_mut());
    }

    let structs_len = entries
        .len()
        .checked_mul(size_of::<NksuProfileEntry>())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "profile list is too large"))?;
    let strings_len = entries
        .iter()
        .try_fold(0usize, |total, entry| {
            total.checked_add(entry.domain.len().checked_add(1)?)
        })
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "profile list is too large"))?;
    let total_len = structs_len
        .checked_add(strings_len)
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "profile list is too large"))?;

    // SAFETY: malloc returns either NULL or at least `total_len` writable bytes.
    let base = unsafe { libc::malloc(total_len) }.cast::<u8>();
    if base.is_null() {
        return Err(out_of_memory());
    }

    // SAFETY: `base` owns `structs_len + strings_len` bytes. The struct array
    // starts at `base`; the string arena starts right after it.
    unsafe {
        let out = base.cast::<NksuProfileEntry>();
        let mut cursor = base.add(structs_len);
        for (position, entry) in entries.iter().enumerate() {
            let domain_len = entry.domain.len();
            ptr::copy_nonoverlapping(entry.domain.as_ptr(), cursor, domain_len);
            *cursor.add(domain_len) = 0;
            ptr::write(
                out.add(position),
                NksuProfileEntry {
                    uid: entry.uid,
                    root_uid: entry.root_uid,
                    caps: entry.caps,
                    ns: entry.ns,
                    domain: cursor.cast::<c_char>(),
                },
            );
            cursor = cursor.add(domain_len + 1);
        }
        Ok(out)
    }
}

fn alloc_feature_list(entries: &[FeatureEntry]) -> io::Result<*mut NksuFeatureEntry> {
    if entries.is_empty() {
        return Ok(ptr::null_mut());
    }

    let total_len = entries
        .len()
        .checked_mul(size_of::<NksuFeatureEntry>())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "feature list is too large"))?;

    // SAFETY: malloc returns either NULL or at least `total_len` writable bytes.
    let base = unsafe { libc::malloc(total_len) }.cast::<NksuFeatureEntry>();
    if base.is_null() {
        return Err(out_of_memory());
    }

    // SAFETY: `base` owns `entries.len()` entries.
    unsafe {
        for (position, entry) in entries.iter().enumerate() {
            ptr::write(
                base.add(position),
                NksuFeatureEntry {
                    id: entry.id,
                    value: entry.value,
                },
            );
        }
    }
    Ok(base)
}

/// Return the ABI version declared by `include/nksu_store.h`.
#[unsafe(no_mangle)]
pub extern "C" fn nksu_store_abi_version() -> u32 {
    NKSU_STORE_ABI_VERSION
}

/// Return the calling thread's last error message.
///
/// The returned pointer stays valid until the next store call on the same
/// thread and must not be freed.
#[unsafe(no_mangle)]
pub extern "C" fn nksu_store_last_error() -> *const c_char {
    LAST_ERROR.with(|slot| slot.borrow().as_ptr())
}

/// Release a list returned by `nksu_store_*_list`. NULL is ignored.
///
/// # Safety
///
/// `data` must be NULL or a pointer previously returned through a list out
/// parameter by this library, and must not have been freed already.
#[unsafe(no_mangle)]
pub extern "C" fn nksu_store_free(data: *mut c_void) {
    if data.is_null() {
        return;
    }
    // SAFETY: the caller upholds the contract above; the block came from
    // libc::malloc.
    unsafe { libc::free(data) };
}

/// Read the on-disk store and apply every profile and feature to the kernel.
#[unsafe(no_mangle)]
pub extern "C" fn nksu_store_load() -> i32 {
    match crate::load() {
        Ok(()) => succeed(),
        Err(e) => io_error("load store", &e),
    }
}

/// Persist one profile row.
#[unsafe(no_mangle)]
pub extern "C" fn nksu_store_profile_save(entry: *const NksuProfileEntry) -> i32 {
    if entry.is_null() {
        return invalid_argument("profile entry pointer is NULL");
    }
    // SAFETY: the FFI contract guarantees a valid entry for the call.
    let entry = match profile_from_c(unsafe { &*entry }) {
        Ok(entry) => entry,
        Err(code) => return code,
    };
    match profile::save(&entry) {
        Ok(()) => succeed(),
        Err(e) => io_error("save profile", &e),
    }
}

/// Delete the persisted profile for `uid`.
#[unsafe(no_mangle)]
pub extern "C" fn nksu_store_profile_delete(uid: u32) -> i32 {
    match profile::del(uid) {
        Ok(()) => succeed(),
        Err(e) => io_error("delete profile", &e),
    }
}

/// Copy the whole profile table into a malloc-owned struct array.
///
/// # Safety
///
/// `out_entries` and `out_count` must be valid, writable pointers. On success
/// the caller owns `*out_entries` and must release it with [`nksu_store_free`].
#[unsafe(no_mangle)]
pub extern "C" fn nksu_store_profile_list(
    out_entries: *mut *mut NksuProfileEntry,
    out_count: *mut usize,
) -> i32 {
    if out_entries.is_null() || out_count.is_null() {
        return invalid_argument("output entries and count pointers must not be NULL");
    }
    // SAFETY: the FFI contract guarantees both pointers are writable.
    unsafe {
        *out_entries = ptr::null_mut();
        *out_count = 0;
    }

    let store = match profile::read_profile_store() {
        Ok(store) => store,
        Err(e) => return io_error("read profile store", &e),
    };
    match alloc_profile_list(&store.entries) {
        Ok(entries) => {
            // SAFETY: the FFI contract guarantees both pointers are writable.
            unsafe {
                *out_entries = entries;
                *out_count = store.entries.len();
            }
            succeed()
        }
        Err(e) => io_error("allocate profile list", &e),
    }
}

/// Replace the persisted profile table with `count` caller-provided rows.
#[unsafe(no_mangle)]
pub extern "C" fn nksu_store_profile_replace(
    entries: *const NksuProfileEntry,
    count: usize,
) -> i32 {
    let entries = match convert_profile_entries(entries, count) {
        Ok(entries) => entries,
        Err(code) => return code,
    };
    let total = match u32::try_from(entries.len()) {
        Ok(total) => total,
        Err(_) => return invalid_argument("too many profile entries"),
    };
    let store = ProfileStore { entries, total };
    match profile::write_profile_store(&store) {
        Ok(()) => succeed(),
        Err(e) => io_error("write profile store", &e),
    }
}

/// Persist one feature row.
#[unsafe(no_mangle)]
pub extern "C" fn nksu_store_feature_save(entry: *const NksuFeatureEntry) -> i32 {
    if entry.is_null() {
        return invalid_argument("feature entry pointer is NULL");
    }
    // SAFETY: the FFI contract guarantees a valid entry for the call.
    let entry = unsafe { &*entry };
    let entry = FeatureEntry {
        id: entry.id,
        value: entry.value,
    };
    match feature::save_feature(&entry) {
        Ok(()) => succeed(),
        Err(e) => io_error("save feature", &e),
    }
}

/// Delete the persisted feature with `id`.
#[unsafe(no_mangle)]
pub extern "C" fn nksu_store_feature_delete(id: i32) -> i32 {
    match feature::del_feature(id) {
        Ok(()) => succeed(),
        Err(e) => io_error("delete feature", &e),
    }
}

/// Copy the whole feature table into a malloc-owned struct array.
///
/// # Safety
///
/// `out_entries` and `out_count` must be valid, writable pointers. On success
/// the caller owns `*out_entries` and must release it with [`nksu_store_free`].
#[unsafe(no_mangle)]
pub extern "C" fn nksu_store_feature_list(
    out_entries: *mut *mut NksuFeatureEntry,
    out_count: *mut usize,
) -> i32 {
    if out_entries.is_null() || out_count.is_null() {
        return invalid_argument("output entries and count pointers must not be NULL");
    }
    // SAFETY: the FFI contract guarantees both pointers are writable.
    unsafe {
        *out_entries = ptr::null_mut();
        *out_count = 0;
    }

    let store = match feature::read_feature_store() {
        Ok(store) => store,
        Err(e) => return io_error("read feature store", &e),
    };
    match alloc_feature_list(&store.entries) {
        Ok(entries) => {
            // SAFETY: the FFI contract guarantees both pointers are writable.
            unsafe {
                *out_entries = entries;
                *out_count = store.entries.len();
            }
            succeed()
        }
        Err(e) => io_error("allocate feature list", &e),
    }
}

/// Replace the persisted feature table with `count` caller-provided rows.
#[unsafe(no_mangle)]
pub extern "C" fn nksu_store_feature_replace(
    entries: *const NksuFeatureEntry,
    count: usize,
) -> i32 {
    let entries = match convert_feature_entries(entries, count) {
        Ok(entries) => entries,
        Err(code) => return code,
    };
    let total = match u32::try_from(entries.len()) {
        Ok(total) => total,
        Err(_) => return invalid_argument("too many feature entries"),
    };
    let store = FeatureStore { entries, total };
    match feature::write_feature_store(&store) {
        Ok(()) => succeed(),
        Err(e) => io_error("write feature store", &e),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn c_string(value: &str) -> CString {
        CString::new(value).expect("test string has no NUL")
    }

    #[test]
    fn profile_conversion_copies_the_domain() {
        let domain = c_string("u:r:su:s0");
        let entry = NksuProfileEntry {
            uid: 1000,
            root_uid: 0,
            caps: 0x1ff,
            ns: -1,
            domain: domain.as_ptr(),
        };
        let converted = profile_from_c(&entry).expect("valid entry");
        assert_eq!(converted.uid, 1000);
        assert_eq!(converted.root_uid, 0);
        assert_eq!(converted.caps, 0x1ff);
        assert_eq!(converted.ns, -1);
        assert_eq!(converted.domain, "u:r:su:s0");
    }

    #[test]
    fn profile_conversion_treats_null_domain_as_empty() {
        let entry = NksuProfileEntry {
            uid: 1,
            root_uid: 2,
            caps: 3,
            ns: 4,
            domain: ptr::null(),
        };
        assert_eq!(profile_from_c(&entry).expect("valid entry").domain, "");
    }

    #[test]
    fn profile_conversion_rejects_invalid_utf8() {
        let domain = [0xff_u8, 0];
        let entry = NksuProfileEntry {
            uid: 1,
            root_uid: 0,
            caps: 0,
            ns: 0,
            domain: domain.as_ptr().cast::<c_char>(),
        };
        assert_eq!(
            profile_from_c(&entry).expect_err("invalid UTF-8"),
            NKSU_STORE_ERR_INVALID_ARGUMENT
        );
        assert!(LAST_ERROR.with(|slot| slot.borrow().to_string_lossy().contains("UTF-8")));
    }

    #[test]
    fn profile_list_is_one_contiguous_block() {
        let entries = vec![
            ProfileEntry {
                uid: 1000,
                root_uid: 0,
                caps: 1,
                ns: 0,
                domain: "u:r:su:s0".to_owned(),
            },
            ProfileEntry {
                uid: 2000,
                root_uid: 1000,
                caps: 2,
                ns: 1,
                domain: "u:r:untrusted_app:s0".to_owned(),
            },
        ];
        let list = alloc_profile_list(&entries).expect("allocation");
        assert!(!list.is_null());

        // SAFETY: `list` points at `entries.len()` initialized structs.
        let list = unsafe { slice::from_raw_parts(list, entries.len()) };
        for (actual, expected) in list.iter().zip(&entries) {
            assert_eq!(actual.uid, expected.uid);
            assert_eq!(actual.root_uid, expected.root_uid);
            assert_eq!(actual.caps, expected.caps);
            assert_eq!(actual.ns, expected.ns);
            let domain = unsafe { CStr::from_ptr(actual.domain) };
            assert_eq!(domain.to_str().expect("UTF-8"), expected.domain);
        }
        nksu_store_free(list.as_ptr().cast_mut().cast());
    }

    #[test]
    fn feature_list_is_allocated() {
        let entries = vec![
            FeatureEntry { id: 1, value: 10 },
            FeatureEntry { id: -2, value: 20 },
        ];
        let list = alloc_feature_list(&entries).expect("allocation");
        assert!(!list.is_null());
        // SAFETY: `list` points at `entries.len()` initialized structs.
        let list = unsafe { slice::from_raw_parts(list, entries.len()) };
        assert_eq!(list[0].id, 1);
        assert_eq!(list[0].value, 10);
        assert_eq!(list[1].id, -2);
        assert_eq!(list[1].value, 20);
        nksu_store_free(list.as_ptr().cast_mut().cast());
    }

    #[test]
    fn empty_lists_are_null() {
        assert!(alloc_profile_list(&[]).expect("allocation").is_null());
        assert!(alloc_feature_list(&[]).expect("allocation").is_null());
    }

    #[test]
    fn last_error_replaces_interior_nul() {
        set_last_error("bad\0message");
        let message = unsafe { CStr::from_ptr(nksu_store_last_error()) };
        assert_eq!(message.to_string_lossy(), "bad message");
    }

    #[test]
    fn freeing_null_is_a_noop() {
        nksu_store_free(ptr::null_mut());
    }
}
