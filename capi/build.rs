// Generates include/refkv.h, the header JavaCPP's parser reads, from src/lib.rs.
fn main() {
    // find the library through the rpath of whatever loads it (the JNI library), not an absolute path
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("macos") {
        println!("cargo:rustc-cdylib-link-arg=-Wl,-install_name,@rpath/librefkv_capi.dylib");
    }
    let dir = std::env::var("CARGO_MANIFEST_DIR").unwrap();
    println!("cargo:rerun-if-changed=src/lib.rs");
    println!("cargo:rerun-if-changed=cbindgen.toml");
    cbindgen::generate(&dir)
        .expect("cbindgen")
        .write_to_file(std::path::Path::new(&dir).join("include/refkv.h"));
}
