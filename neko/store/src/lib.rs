pub mod cabi;
pub mod feature;
pub mod profile;

mod db;
mod root;

#[cfg(target_os = "android")]
pub fn load() -> std::io::Result<()> {
    let store = db::read()?;
    let profile = profile::load_entries(&store.profiles);
    let feature = feature::load_entries(&store.features);

    match (profile, feature) {
        (Ok(()), Ok(())) => Ok(()),
        (Err(e), Ok(())) | (Ok(()), Err(e)) => Err(e),
        (Err(profile), Err(feature)) => {
            log::error!("feature load also failed: {feature}");
            Err(profile)
        }
    }
}

#[cfg(not(target_os = "android"))]
pub fn load() -> std::io::Result<()> {
    Err(std::io::Error::new(
        std::io::ErrorKind::Unsupported,
        "applying the store requires the NekoSU kernel interface on Android",
    ))
}
