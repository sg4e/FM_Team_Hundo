fn main() {
    println!("cargo:rerun-if-env-changed=FM_HUNDO_PROTOCOL_VERSION");
    if let Ok(version) = std::env::var("FM_HUNDO_PROTOCOL_VERSION") {
        let trimmed = version.trim();
        if !trimmed.is_empty() {
            println!("cargo:rustc-env=FM_HUNDO_PROTOCOL_VERSION={trimmed}");
        }
    }
}
