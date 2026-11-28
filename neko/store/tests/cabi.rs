// SPDX-License-Identifier: GPL-3.0-or-later
//! Integration tests for the exported C ABI.
//!
//! These exercise the public entry points the shared library exposes. Tests
//! that would write the real store are deliberately absent: writes switch to
//! uid 0 and target /data/adb, so they belong to an on-device test.

use std::ffi::CStr;
use std::path::Path;
use std::ptr;

use store::cabi::{
    NKSU_STORE_ABI_VERSION, NKSU_STORE_ERR_INVALID_ARGUMENT, NKSU_STORE_ERR_UNSUPPORTED,
    NKSU_STORE_OK, NksuProfileEntry, nksu_store_abi_version, nksu_store_feature_list,
    nksu_store_feature_replace, nksu_store_feature_save, nksu_store_free, nksu_store_last_error,
    nksu_store_load, nksu_store_profile_list, nksu_store_profile_replace, nksu_store_profile_save,
};

const STORE_PATH: &str = "/data/adb/nksu/profile";

fn last_error() -> String {
    // SAFETY: the library always returns a valid, NUL-terminated pointer.
    unsafe { CStr::from_ptr(nksu_store_last_error()) }
        .to_string_lossy()
        .into_owned()
}

#[test]
fn abi_version_matches_the_header() {
    assert_eq!(nksu_store_abi_version(), NKSU_STORE_ABI_VERSION);
}

#[test]
fn null_entry_pointers_are_rejected() {
    assert_eq!(
        nksu_store_profile_save(ptr::null()),
        NKSU_STORE_ERR_INVALID_ARGUMENT
    );
    assert!(last_error().contains("NULL"));

    assert_eq!(
        nksu_store_feature_save(ptr::null()),
        NKSU_STORE_ERR_INVALID_ARGUMENT
    );
    assert!(last_error().contains("NULL"));
}

#[test]
fn null_replace_entries_with_nonzero_count_are_rejected() {
    assert_eq!(
        nksu_store_profile_replace(ptr::null(), 1),
        NKSU_STORE_ERR_INVALID_ARGUMENT
    );
    assert!(last_error().contains("NULL"));

    assert_eq!(
        nksu_store_feature_replace(ptr::null(), 1),
        NKSU_STORE_ERR_INVALID_ARGUMENT
    );
    assert!(last_error().contains("NULL"));
}

#[test]
fn invalid_profile_domain_is_rejected_before_writing() {
    // 0xff is not valid UTF-8. The conversion runs before any disk access, so
    // this is safe to exercise on a development host.
    let domain = [0xff_u8, 0];
    let entry = NksuProfileEntry {
        uid: 1,
        root_uid: 0,
        caps: 0,
        ns: 0,
        domain: domain.as_ptr().cast(),
    };
    assert_eq!(
        nksu_store_profile_save(&entry),
        NKSU_STORE_ERR_INVALID_ARGUMENT
    );
    assert!(last_error().contains("UTF-8"));

    // The bulk path validates every row before it touches the store.
    assert_eq!(
        nksu_store_profile_replace(&entry, 1),
        NKSU_STORE_ERR_INVALID_ARGUMENT
    );
    assert!(last_error().contains("UTF-8"));
}

#[test]
fn null_list_output_pointers_are_rejected() {
    let mut count = 0usize;
    assert_eq!(
        nksu_store_profile_list(ptr::null_mut(), &mut count),
        NKSU_STORE_ERR_INVALID_ARGUMENT
    );

    let mut entries = ptr::null_mut();
    assert_eq!(
        nksu_store_profile_list(&mut entries, ptr::null_mut()),
        NKSU_STORE_ERR_INVALID_ARGUMENT
    );

    let mut feature_entries = ptr::null_mut();
    assert_eq!(
        nksu_store_feature_list(ptr::null_mut(), &mut count),
        NKSU_STORE_ERR_INVALID_ARGUMENT
    );
    assert_eq!(
        nksu_store_feature_list(&mut feature_entries, ptr::null_mut()),
        NKSU_STORE_ERR_INVALID_ARGUMENT
    );
}

#[test]
fn reads_a_missing_store_as_empty_lists() {
    if Path::new(STORE_PATH).exists() {
        eprintln!("skipping: {STORE_PATH} exists on this host");
        return;
    }

    let mut entries = ptr::null_mut();
    let mut count = usize::MAX;
    assert_eq!(
        nksu_store_profile_list(&mut entries, &mut count),
        NKSU_STORE_OK
    );
    assert!(entries.is_null());
    assert_eq!(count, 0);
    assert!(last_error().is_empty());
    nksu_store_free(entries.cast());

    let mut entries = ptr::null_mut();
    let mut count = usize::MAX;
    assert_eq!(
        nksu_store_feature_list(&mut entries, &mut count),
        NKSU_STORE_OK
    );
    assert!(entries.is_null());
    assert_eq!(count, 0);
    assert!(last_error().is_empty());
    nksu_store_free(entries.cast());
}

#[test]
fn freeing_null_is_a_noop() {
    nksu_store_free(ptr::null_mut());
}

#[cfg(not(target_os = "android"))]
#[test]
fn load_reports_unsupported_without_the_kernel_interface() {
    assert_eq!(
        nksu_store_load(),
        NKSU_STORE_ERR_UNSUPPORTED,
        "unexpected error: {}",
        last_error()
    );
    assert!(!last_error().is_empty());
}
