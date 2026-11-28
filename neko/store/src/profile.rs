use proto::{ProfileEntry, ProfileStore};
use std::io;

use crate::db;

#[cfg(target_os = "android")]
fn apply(entry: &ProfileEntry) -> io::Result<()> {
    kzcall::set_profile(entry).map_err(|e| io::Error::other(format!("{e:#}")))
}

#[cfg(not(target_os = "android"))]
fn apply(_entry: &ProfileEntry) -> io::Result<()> {
    Err(io::Error::new(
        io::ErrorKind::Unsupported,
        "applying a profile requires the NekoSU kernel interface on Android",
    ))
}

pub fn save(entry: &ProfileEntry) -> io::Result<()> {
    match db::save_profile(entry)? {
        Some(total) => log::info!(
            "profile saved: uid={} domain={:?}, total={total}",
            entry.uid,
            entry.domain,
        ),
        None => log::debug!("profile save: no changes (uid={})", entry.uid),
    }
    Ok(())
}

pub fn del(uid: u32) -> io::Result<()> {
    match db::del_profile(uid)? {
        Some(total) => log::info!("profile deleted: uid={uid}, total={total}"),
        None => log::debug!("profile del: uid={uid} not found"),
    }
    Ok(())
}

pub fn load() -> io::Result<()> {
    let store = db::read()?;
    load_entries(&store.profiles)
}

pub(crate) fn load_entries(entries: &[ProfileEntry]) -> io::Result<()> {
    let total = entries.len();
    let mut failed = 0usize;

    for entry in entries {
        if let Err(e) = apply(entry) {
            failed += 1;
            log::error!(
                "set_profile failed: uid={} domain={:?}: {e:#}",
                entry.uid,
                entry.domain,
            );
        }
    }

    db::finish_load("profile", total, failed)
}

pub fn read_profile_store() -> io::Result<ProfileStore> {
    let store = db::read()?;
    let total = u32::try_from(store.profiles.len()).map_err(|_| {
        io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("too many profile entries: {}", store.profiles.len()),
        )
    })?;
    Ok(ProfileStore {
        entries: store.profiles,
        total,
    })
}

pub fn write_profile_store(store: &ProfileStore) -> io::Result<()> {
    if store.total as usize != store.entries.len() {
        log::warn!(
            "profile write: count mismatch: entries={} total={}",
            store.entries.len(),
            store.total,
        );
    }
    db::replace_profiles(store.entries.clone())
}
