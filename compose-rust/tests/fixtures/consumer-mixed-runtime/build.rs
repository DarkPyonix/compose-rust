//! Links the C++ library scripts/check-windows-consumer.sh built with the DLL runtime (/MD).

fn main() {
    println!("cargo:rerun-if-env-changed=MIXED_RUNTIME_LIB_DIR");
    let dir = std::env::var("MIXED_RUNTIME_LIB_DIR").expect(
        "MIXED_RUNTIME_LIB_DIR names the directory holding mixed_runtime.lib; \
         scripts/check-windows-consumer.sh builds it",
    );
    println!("cargo:rustc-link-search=native={dir}");
    println!("cargo:rustc-link-lib=static=mixed_runtime");
}
