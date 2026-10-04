use std::path::{Path, PathBuf};
use std::process::Command;

// The resolution rules and every error message live here, free of Cargo directives so
// tests/renderer_resolution.rs can exercise them directly.
#[allow(dead_code)]
mod renderer_dir {
    include!("build/renderer_dir.rs");
}

// Which C runtime a Windows application links with the Kotlin/Native renderer, and the
// rewriting of prebuilt libraries that lets the build choose it. Kept apart, like the
// resolution rules, so tests/windows_crt.rs can exercise it.
#[allow(dead_code)]
mod windows_crt {
    include!("build/windows_crt.rs");
}

use windows_crt::{
    WINDOWS_CRT_ENV, WindowsCrt, blank_runtime_directives, find_msvc_library,
    unreconcilable_message, windows_crt,
};

use renderer_dir::{
    CACHE_DIR_ENV, FetchError, RENDERER_DIR_ENV, RendererLinkage, RendererSource, Request,
    acquire_renderer, artifact_target, default_cache_root, renderer_linkage,
};

/// What the Linux static renderer ships beside its archive so that an application exports
/// the Host's functions. Linked through `-l`, so the `lib` prefix and suffix are dropped
/// there.
const HOST_EXPORTS_LIBRARY: &str = "libcompose_rust_host_exports.so";

/// docs.rs builds with the network switched off. Linking a renderer is not what building
/// the documentation needs, so that build skips the whole thing and produces a library
/// with no renderer in it, which is one of the cases the Host is loud about at run time.
const DOCS_RS_ENV: &str = "DOCS_RS";

/// Where an Android application's Gradle project keeps the Kotlin it compiles.
const ANDROID_KOTLIN_DIR_ENV: &str = "COMPOSE_RUST_ANDROID_KOTLIN_DIR";

/// Where the crate carries that Kotlin, relative to the crate root.
const ANDROID_KOTLIN_DIR: &str = "android-kotlin";

/// The package an Android application's generated Activity belongs to.
const ANDROID_PACKAGE_ENV: &str = "COMPOSE_RUST_ANDROID_PACKAGE";

/// Where dx says to put generated Kotlin, and the package it belongs to.
///
/// The names are wry's: dx sets them for every Android build so that wry can generate the
/// Activity that hosts its webview. We draw with Compose and have no webview in us, but
/// the two values say the same thing either way, which is the whole Gradle project this
/// build is part of. Reading them is what makes an APK come out of `dx build` with
/// nothing written in `Dioxus.toml` about us.
const DX_KOTLIN_DIR_ENV: &str = "WRY_ANDROID_KOTLIN_FILES_OUT_DIR";
const DX_PACKAGE_ENV: &str = "WRY_ANDROID_PACKAGE";
const DX_LIBRARY_ENV: &str = "WRY_ANDROID_LIBRARY";

fn main() {
    // Set when a renderer is actually linked into this build, which is not the same thing
    // as the feature being on: docs.rs turns the feature on and links nothing.
    println!("cargo:rustc-check-cfg=cfg(renderer_linked)");
    // Set when the Linux static renderer is linked, which comes with a library the Host has
    // to refer to so that the linker keeps it. See the DXC_LINUX_NATIVE_LIB branch below.
    println!("cargo:rustc-check-cfg=cfg(renderer_host_exports)");
    // Set when the Windows static renderer is linked and the application asked for no C
    // runtime of its own. See the DXC_WINDOWS_NATIVE_LIB branch below.
    println!("cargo:rustc-check-cfg=cfg(windows_crt_linked_in)");
    println!("cargo:rustc-check-cfg=cfg(windows_crt_dynamic)");
    println!("cargo:rerun-if-env-changed={RENDERER_DIR_ENV}");
    println!("cargo:rerun-if-env-changed={CACHE_DIR_ENV}");
    println!("cargo:rerun-if-env-changed=DIOXUS_COMPOSE_RENDERER_DIR");
    println!("cargo:rerun-if-env-changed=DIOXUS_COMPOSE_CACHE_DIR");
    println!("cargo:rerun-if-env-changed={DOCS_RS_ENV}");
    println!("cargo:rerun-if-changed=build/renderer_dir.rs");
    println!("cargo:rerun-if-changed=build/sha256.rs");
    println!("cargo:rerun-if-changed=build/elf.rs");
    println!("cargo:rerun-if-changed=build/windows_crt.rs");

    if std::env::var_os("CARGO_FEATURE_NATIVE_RENDERER").is_none() {
        return;
    }

    // `mock-renderer` says this build draws nothing, so there is nothing to link and no
    // reason to spend a download on it. It wins over the default feature rather than
    // forcing anyone who wants the mock to also spell out `default-features = false`.
    if std::env::var_os("CARGO_FEATURE_MOCK_RENDERER").is_some() {
        return;
    }

    let target_family =
        std::env::var("CARGO_CFG_TARGET_FAMILY").expect("Cargo sets CARGO_CFG_TARGET_FAMILY");
    let target_os = std::env::var("CARGO_CFG_TARGET_OS").expect("Cargo sets CARGO_CFG_TARGET_OS");
    match renderer_linkage(&target_os, &target_family, false) {
        // Nobody resolves a declared symbol on these, because there is none to resolve.
        // The browser and Android both run the renderer in a managed runtime beside the
        // Host, and both install their entry points at load time. Declaring the desktop
        // renderer's symbols instead leaves the loader looking for something that does
        // not exist, which on Android stops the library opening at all.
        RendererLinkage::Installed | RendererLinkage::None => {
            // Android is also where the renderer's Kotlin has to reach the application's
            // own Gradle build, because ART compiles it rather than us. Everywhere else
            // the Kotlin was frozen ahead of time into a library.
            if target_os == "android" {
                unpack_android_kotlin();
            }
            return;
        }
        // iOS links the XCFramework through Xcode. Cargo does not link the renderer, but
        // the symbols are there by the time anything runs, so the Host must call them
        // rather than take its no-renderer path.
        RendererLinkage::Provided => {
            println!("cargo:rustc-cfg=renderer_linked");
            // Same reason as macOS below, and the same Mach-O. Xcode links the renderer
            // into the application, so the symbols are real by the time anything runs,
            // but a shared library built here has to be allowed to leave them open.
            // Without this every iOS build of this crate fails at its own cdylib, which
            // is not even the artifact an iOS application uses.
            if target_os == "ios" {
                println!("cargo:rustc-link-arg-cdylib=-Wl,-undefined,dynamic_lookup");
            }
            return;
        }
        RendererLinkage::Linked => {}
    }

    // Mach-O can leave the Renderer's symbols unresolved and bind them from the shared
    // library at load time. PE/COFF cannot, which is why the Windows renderer defines
    // forwarding stubs instead, and ELF resolves them from the library we link below.
    if target_os == "macos" {
        println!("cargo:rustc-link-arg-cdylib=-Wl,-undefined,dynamic_lookup");
    }

    if std::env::var_os(DOCS_RS_ENV).is_some() {
        return;
    }

    // The renderer built for this platform directly, with no virtual machine in it.
    //
    // Off unless DXC_MACOS_NATIVE_LIB names the directory holding
    // `libcompose_rust_renderer.a`, because it is not what the published crate ships:
    // `renderer/desktop/scripts/build-macos.sh` makes one in a checkout.
    //
    // A static archive rather than a library beside the binary, because Kotlin/Native
    // produces one and because what is inside it is most of Compose and all of Skia: what
    // the application does not reach, the linker drops.
    if target_os == "macos" {
        if let Some(dir) = std::env::var_os("DXC_MACOS_NATIVE_LIB") {
            let dir = PathBuf::from(dir);
            println!("cargo:rerun-if-env-changed=DXC_MACOS_NATIVE_LIB");
            // The archive itself, not only the variable naming it. Rebuilding the
            // renderer and not saying so left Cargo linking yesterday's one and
            // reporting a build that finished in no time at all.
            println!(
                "cargo:rerun-if-changed={}",
                dir.join("libcompose_rust_renderer.a").display()
            );
            println!("cargo:rustc-link-search=native={}", dir.display());
            println!("cargo:rustc-link-lib=static=compose_rust_renderer");
            // What the archive itself calls in. Kotlin/Native names none of these: they
            // are the frameworks Compose and Skia reach through, and the compression the
            // Kotlin runtime uses for its own resources.
            for framework in [
                "AppKit",
                "Foundation",
                "Metal",
                "QuartzCore",
                "CoreGraphics",
                "CoreText",
                "CoreServices",
                "IOKit",
                "Carbon",
                "OpenGL",
                // The renderer's notifications (UNUserNotificationCenter), as develop links.
                "UserNotifications",
            ] {
                println!("cargo:rustc-link-lib=framework={framework}");
            }
            println!("cargo:rustc-link-lib=dylib=c++");
            println!("cargo:rustc-link-lib=dylib=z");
            println!("cargo:rustc-cfg=renderer_linked");
            return;
        }
    }

    // The same thing on Linux, from `build-linux.sh`, and the same reasons.
    if target_os == "linux" {
        if let Some(dir) = std::env::var_os("DXC_LINUX_NATIVE_LIB") {
            let dir = PathBuf::from(dir);
            println!("cargo:rerun-if-env-changed=DXC_LINUX_NATIVE_LIB");
            println!(
                "cargo:rerun-if-changed={}",
                dir.join("libcompose_rust_renderer.a").display()
            );
            println!("cargo:rustc-link-search=native={}", dir.display());
            println!("cargo:rustc-link-lib=static=compose_rust_renderer");
            // What the archive itself calls in and Kotlin/Native names none of: the
            // window's own libraries, the font configuration Skia asks for a font
            // through, and the compression the Kotlin runtime uses for its resources.
            // `stdc++` where macOS says `c++`: Skia is C++ and names its standard
            // library's symbols, and the archive says nothing about which one. Without it
            // the link fails on eight hundred references to std::string from Skia's text
            // shaping alone.
            for library in ["X11", "Xext", "GL", "fontconfig", "freetype", "stdc++", "z"] {
                println!("cargo:rustc-link-lib=dylib={library}");
            }
            // A desktop keeps these where its own convention puts them and the
            // conventions differ, so both are searched. One that is not there costs
            // nothing.
            for path in ["/usr/lib/x86_64-linux-gnu", "/usr/lib64"] {
                println!("cargo:rustc-link-search=native={path}");
            }
            // The Host's own five functions, put where the renderer can find them.
            //
            // It resolves them by name at startup with `dlsym`, which reads the dynamic
            // symbol table, and an executable's table holds only what its link put there.
            // Without them the window opens, stays black and reports that
            // `compose_rust_host_init is not in this image`.
            //
            // `-rdynamic` used to be emitted here, and it reached this package's own
            // binaries and no application: Cargo does not pass a dependency's link
            // arguments on. A library it names does reach every application, so the
            // renderer build ships a small one that leaves the five undefined, and a
            // linker exports from an executable what a library it links needs. The Host
            // refers to one byte in it (see `native_run`) so that `--as-needed` keeps it.
            // It is named after its absolute path, the way the desktop renderer is, so an
            // application with no rpath can load it.
            let exports = renderer_dir::absolute(&dir).join(HOST_EXPORTS_LIBRARY);
            println!("cargo:rerun-if-changed={}", exports.display());
            if !exports.is_file() {
                panic!(
                    "\n\ncompose-rust: {} has the static renderer but not {HOST_EXPORTS_LIBRARY}.\n\n\
                     The renderer finds the Host's functions in the application's dynamic \
                     symbol table, and that library is what puts them there. Without it the \
                     application would build, open a black window and report that \
                     compose_rust_host_init is not in the image.\n\n\
                     renderer/desktop/scripts/build-linux.sh writes both \
                     files; build the renderer again, or point DXC_LINUX_NATIVE_LIB at the \
                     directory it wrote them to.\n\n",
                    dir.display()
                );
            }
            let crate_version =
                std::env::var("CARGO_PKG_VERSION").expect("Cargo sets CARGO_PKG_VERSION");
            let target = artifact_target(
                &target_os,
                &std::env::var("CARGO_CFG_TARGET_ARCH").expect("Cargo sets CARGO_CFG_TARGET_ARCH"),
            );
            if let Err(message) = renderer_dir::name_after_its_location(
                &exports,
                &exports,
                &target_os,
                &crate_version,
                &target,
            ) {
                panic!("\n\n{message}\n\n");
            }
            println!("cargo:rustc-link-lib=dylib=compose_rust_host_exports");
            println!("cargo:rustc-cfg=renderer_host_exports");
            println!("cargo:rustc-cfg=renderer_linked");
            return;
        }
    }

    // The same thing on Windows, from `build-windows.sh`: no virtual machine, and one
    // executable. The renderer object is MinGW, because that is Kotlin/Native's only Windows
    // target; everything else is MSVC, and the two halves meet in C calls only. What the
    // script leaves in the directory is the whole kit an MSVC link needs.
    if target_os == "windows" {
        if let Some(dir) = std::env::var_os("DXC_WINDOWS_NATIVE_LIB") {
            link_windows_native(&PathBuf::from(dir));
            return;
        }
    }

    let manifest_dir = PathBuf::from(
        std::env::var_os("CARGO_MANIFEST_DIR").expect("Cargo sets CARGO_MANIFEST_DIR"),
    );
    // Only present in a checkout of this repository, and only once its renderer has been
    // built. A consumer of the published crate has neither, which is why everything below
    // it exists.
    let workspace_lib_dir = manifest_dir.join("../renderer/build/native-image/dist/lib");
    let crate_version = std::env::var("CARGO_PKG_VERSION").expect("Cargo sets CARGO_PKG_VERSION");
    let target = artifact_target(
        &target_os,
        &std::env::var("CARGO_CFG_TARGET_ARCH").expect("Cargo sets CARGO_CFG_TARGET_ARCH"),
    );
    let cache_root = cache_root();

    let renderer = match acquire_renderer(&Request {
        env_dir: renamed_var(RENDERER_DIR_ENV, "DIOXUS_COMPOSE_RENDERER_DIR")
            .map(PathBuf::from)
            .as_deref(),
        workspace_lib_dir: &workspace_lib_dir,
        cache_root: cache_root.as_deref(),
        crate_version: &crate_version,
        target: &target,
        target_os: &target_os,
        fetch: Some(&fetch),
    }) {
        Ok(renderer) => renderer,
        // A build script panic is reported as the build failure itself, message and all.
        // That is the point: the consumer reads this instead of an undefined-symbol dump.
        Err(message) => panic!("\n\n{message}\n\n"),
    };

    if matches!(
        renderer.source,
        RendererSource::Download | RendererSource::LocalArtifact
    ) {
        // Cargo gives a build script one channel to the terminal and this is it. Fetching
        // tens of megabytes is worth a line; it is written in the past tense because Cargo
        // replays a cached build script's warnings on later builds, and a line claiming to
        // be downloading something would then be a lie.
        println!(
            "cargo:warning=compose-rust: unpacked the renderer for v{crate_version} ({target}) into {}",
            renderer.lib_dir.display()
        );
    }

    let lib_dir = renderer.lib_dir;
    check_schema_agreement(&lib_dir);
    println!("cargo:rerun-if-changed={}", lib_dir.display());
    println!("cargo:rustc-link-search=native={}", lib_dir.display());
    println!("cargo:rustc-cfg=renderer_linked");

    if target_os == "windows" {
        // MSVC links against the import library the native image produced beside the DLL,
        // and it carries the same `lib` prefix the image was named with.
        println!("cargo:rustc-link-lib=dylib=libcompose_rust_renderer");
        // The exports the Renderer looks up are not asked for here.
        //
        // They were, with `/EXPORT:` link arguments, and that reached this package's own
        // binaries and nothing else: Cargo does not pass a dependency's link arguments on
        // to whatever depends on it. So every application built on this crate linked with
        // no export table, the Renderer could not reach back into it, and the window came
        // up empty. It looked like it worked because the only Windows binaries anyone had
        // run were this package's own examples.
        //
        // The directive travels inside the rlib now, in the `.drectve` section that the
        // MSVC linker reads out of every object it links. See the `global_asm!` block in
        // src/boundary.rs.
        // The subsystem is not set here, and the reason is the same one the export
        // directive ran into: a build script's link arguments reach this package's own
        // targets and stop. Setting it here broke the crate's own cdylib, which has no
        // `main` for `/ENTRY:mainCRTStartup` to point at, and would not have reached a
        // single application even if it had worked.
        //
        // An application says it for itself, with `#![windows_subsystem = "windows"]` at
        // the top of its `main.rs`. That is one line, it is the ordinary way to do it in
        // Rust, and the samples here carry it. Without it a window application also opens
        // a console, and closing that console kills it.
        //
        // The renderer attaches to a parent console at startup so that saying it costs no
        // diagnostics: run from a terminal the messages still appear, run from Explorer
        // there is no terminal to open.
        // Windows has no rpath and no name inside the file that the loader consults: a
        // DLL is found on the loader's search path and nowhere else. The other platforms
        // name the library after where it sits, which does nothing here.
        //
        // Telling the person building to put a directory on PATH was the whole of the fix
        // and it was not enough. It makes adding this crate to a Cargo.toml two steps
        // instead of one, and it breaks things that have nothing to do with drawing: the
        // code generator in this package links the renderer only because it lives in the
        // same package, and on Windows it died on startup with STATUS_DLL_NOT_FOUND
        // before it had generated a line.
        //
        // The loader searches the directory the executable is in, so the renderer is put
        // there. Cargo does not tell a build script where that is, but OUT_DIR is
        // `<target>/<profile>/build/<crate>-<hash>/out`, so three levels up is the
        // profile directory where binaries land, and `examples/` and `deps/` beside it
        // are where examples and tests land.
        copy_renderer_beside_executables(&lib_dir);
        return;
    }

    println!("cargo:rustc-link-lib=dylib=compose_rust_renderer");
    // No rpath. It would not reach an application that merely depends on this crate,
    // because Cargo does not pass a dependency's link arguments on, and emitting one here
    // anyway would leave this repository's own binaries loading the renderer by a route no
    // consumer has. The renderer is named after the absolute path it sits at instead,
    // which every binary that links it records for itself: on macOS that name is the
    // install name, and on Linux it is the SONAME, which acquiring the renderer sets
    // because the linker copies a SONAME into DT_NEEDED as it stands. Without one, GNU ld
    // would record a library it found through `-l` and the search path by its bare file
    // name, and the loader would have to search for it on a path the binary does not
    // have.
    //
    // The Renderer resolves the Host's compose_rust_host_* symbols from this executable.
    // GNU ld spells this `--export-dynamic`. Passing the macOS spelling to it is not a
    // harmless no-op: `-export_dynamic` parses as `-e xport_dynamic`, which sets the
    // entry point to a symbol that does not exist, so the link succeeds with a warning
    // and the program jumps into the middle of its own text and dies on the first
    // instruction.
    let export_dynamic = if target_os == "macos" {
        "-Wl,-export_dynamic"
    } else {
        "-Wl,--export-dynamic"
    };
    println!("cargo:rustc-link-arg={export_dynamic}");
}

/// Links the Windows renderer that Kotlin/Native built, and everything beside it.
///
/// Every library here is named with `rustc-link-lib` and none with a link argument, because
/// a library a build script names travels in the rlib to every application built on this
/// crate, and a link argument stops at this package's own targets.
///
/// The Host's functions need nothing here. The renderer finds them with GetProcAddress on
/// the executable, which reads its export table, and the `.drectve` directives in
/// src/boundary.rs put them there in every application's link.
fn link_windows_native(dir: &Path) {
    println!("cargo:rerun-if-env-changed=DXC_WINDOWS_NATIVE_LIB");
    println!("cargo:rerun-if-env-changed={WINDOWS_CRT_ENV}");
    println!("cargo:rerun-if-env-changed=LIB");
    // The archive itself, not only the variable naming it: a rebuilt renderer that Cargo was
    // not told about is yesterday's renderer, linked in no time at all.
    println!(
        "cargo:rerun-if-changed={}",
        dir.join("libcompose_rust_renderer.a").display()
    );
    for (piece, what) in [
        ("libcompose_rust_renderer.a", "the renderer"),
        (
            "native/dxc-windows-native.lib",
            "the window and the MSVC bridge",
        ),
        (
            "native/dxc-windows-static-ucrt.lib",
            "the bridge for a statically linked UCRT",
        ),
        ("gcc/libstdc++.a", "the GCC runtime the renderer carries"),
        ("skiko/skiko-bridges.lib", "skiko's C++ half"),
        ("skiko/skia.lib", "Skia"),
    ] {
        if !dir.join(piece).is_file() {
            panic!(
                "\n\ncompose-rust: DXC_WINDOWS_NATIVE_LIB is {}, which has no {piece} ({what}).\n\n\
                 renderer/desktop/scripts/build-windows.sh writes the whole \
                 directory; build the renderer again, or point DXC_WINDOWS_NATIVE_LIB at the \
                 directory it wrote.\n\n",
                dir.display()
            );
        }
    }

    // Which C runtime. Nothing the application has to say: it gets the one that needs no
    // DLL beside the executable, and build/windows_crt.rs says how that is put together.
    let features = std::env::var("CARGO_CFG_TARGET_FEATURE").unwrap_or_default();
    let requested = std::env::var(WINDOWS_CRT_ENV).ok();
    let crt = match windows_crt(&features, requested.as_deref()) {
        Ok(crt) => crt,
        Err(message) => panic!("\n\ncompose-rust: {message}\n\n"),
    };
    let out_dir = PathBuf::from(std::env::var_os("OUT_DIR").expect("Cargo sets OUT_DIR"));
    let rewritten = out_dir.join("windows-crt");
    if let Err(error) = std::fs::create_dir_all(&rewritten) {
        panic!(
            "\n\ncompose-rust: could not make {}: {error}\n\n",
            rewritten.display()
        );
    }

    for sub in ["", "gcc", "native"] {
        println!("cargo:rustc-link-search=native={}", dir.join(sub).display());
    }
    // Skia and skiko's C++ half come from the copies made below, so the kit's own directory
    // is not searched: two libraries of one name would leave the choice to the linker.
    println!("cargo:rustc-link-search=native={}", rewritten.display());

    // The renderer, MinGW, rewritten by the build so an MSVC link keeps its constructors and
    // its unwind data. Named exactly, because MSVC would look for `.lib`.
    println!("cargo:rustc-link-lib=static:+verbatim=libcompose_rust_renderer.a");
    // The Win32 window, its toasts, the bridge to MSVC's runtime and the embedded ICU loader,
    // linked whole: nothing calls the ICU loader or the initialiser the bridge registers by
    // name, and a member nothing calls is one the linker leaves out.
    println!("cargo:rustc-link-lib=static:+whole-archive,+verbatim=dxc-windows-native.lib");
    // The GCC runtime the renderer object carries, as Kotlin/Native links it into a MinGW
    // executable of its own: statically.
    for library in ["libstdc++.a", "libgcc.a", "libgcc_eh.a", "libwinpthread.a"] {
        println!("cargo:rustc-link-lib=static:+verbatim={library}");
    }
    // skiko's C++ half and Skia, MSVC, from copies whose runtime directives are blanked so
    // that this build and not the order the linker reads them in decides the C runtime.
    // Every library the Skia distribution carries is named, because which of them a given
    // application reaches is the linker's business.
    for library in [
        "skiko-bridges",
        "skresources",
        "skparagraph",
        "skia",
        "icu",
        "jsonreader",
        "skottie",
        "svg",
        "sksg",
        "skshaper",
        "skunicode_core",
        "skunicode_icu",
        "harfbuzz",
        "skcms",
        "libpng",
        "zlib",
        "libjpeg",
        "libwebp",
        "libwebp_sse41",
        "wuffs",
        "expat",
        "d3d12allocator",
        "spirv_cross",
        "bentleyottmann",
    ] {
        let file = format!("{library}.lib");
        let source = dir.join("skiko").join(&file);
        rewrite_runtime_directives(&source, &rewritten.join(&file));
        println!("cargo:rustc-link-lib=static={library}");
    }

    // The C++ standard library, linked in from this machine's own MSVC installation, with its
    // guard blanked like Skia's. Skia names it as a default library, which the copies above no
    // longer do, so it is named here. Every runtime choice links it, the dynamic one included:
    // Skia is built for the static runtime, so its objects name the library's data (the
    // locale ids, std::_Raise_handler) the way static code does, and msvcprt.lib, the import
    // library for MSVCP140.dll, defines those only under the __imp_ spelling a DLL caller uses
    // (LNK2001, 160 times, for a link that named msvcprt.lib instead).
    let source = msvc_library("libcpmt.lib");
    println!("cargo:rerun-if-changed={}", source.display());
    rewrite_runtime_directives(&source, &rewritten.join("dxc-libcpmt.lib"));
    println!("cargo:rustc-link-lib=static:+verbatim=dxc-libcpmt.lib");
    match crt {
        WindowsCrt::LinkedIn | WindowsCrt::FullyStatic => {
            if crt == WindowsCrt::LinkedIn {
                // The rest is said by the directives in src/boundary.rs: vcruntime from its
                // static library, and none of the default libraries that would bring a second
                // copy of anything.
                println!("cargo:rustc-cfg=windows_crt_linked_in");
            } else {
                // winpthread, in the renderer's GCC runtime, starts its threads through the
                // UCRT's import pointers, which a statically linked UCRT does not have.
                println!(
                    "cargo:rustc-link-lib=static:+whole-archive,+verbatim=dxc-windows-static-ucrt.lib"
                );
            }
        }
        WindowsCrt::Dynamic => {
            // vcruntime from VCRUNTIME140.dll, and no stock static runtime beside it: see the
            // directives in src/boundary.rs.
            println!("cargo:rustc-cfg=windows_crt_dynamic");
        }
    }

    // What Skia, the window, the toasts and the Kotlin runtime call into. All of them are
    // part of Windows; nothing here is a DLL an application would have to carry.
    for library in [
        "user32",
        "gdi32",
        "ole32",
        "oleaut32",
        "imm32",
        "uiautomationcore",
        "d3d12",
        "dxgi",
        "d3dcompiler",
        "dxguid",
        "dwrite",
        "usp10",
        "fontsub",
        "windowscodecs",
        "opengl32",
        "advapi32",
        "shell32",
        "bcrypt",
        "runtimeobject",
    ] {
        println!("cargo:rustc-link-lib=dylib={library}");
    }
    // The MSVC runtime's spellings of what MinGW code asks for: POSIX names such as `write`,
    // and the printf family, which the UCRT defines only inline in its headers.
    println!("cargo:rustc-link-lib=oldnames");
    println!("cargo:rustc-link-lib=legacy_stdio_definitions");
    println!("cargo:rustc-cfg=renderer_linked");
}

/// Copies a prebuilt MSVC library with its runtime directives blanked
/// (`build/windows_crt.rs`), or stops the build naming it when it was built against a
/// runtime that cannot share an executable with the others.
fn rewrite_runtime_directives(source: &Path, destination: &Path) {
    let mut bytes = match std::fs::read(source) {
        Ok(bytes) => bytes,
        Err(error) => panic!(
            "\n\ncompose-rust: could not read {}: {error}\n\n\
             renderer/desktop/scripts/build-windows.sh writes every library the \
             Windows renderer links; build the renderer again.\n\n",
            source.display()
        ),
    };
    let found = blank_runtime_directives(&mut bytes);
    if !found.unreconcilable.is_empty() {
        panic!(
            "\n\n{}\n\n",
            unreconcilable_message(source, &found.unreconcilable)
        );
    }
    if let Err(error) = std::fs::write(destination, &bytes) {
        panic!(
            "\n\ncompose-rust: could not write {}: {error}\n\n",
            destination.display()
        );
    }
}

/// A library from the MSVC installation the application is being built with.
fn msvc_library(name: &str) -> PathBuf {
    let mut tools = Vec::new();
    if let Some(dir) = std::env::var_os("VCToolsInstallDir") {
        tools.push(PathBuf::from(dir));
    }
    tools.extend(visual_studio_tools());
    let mut xwin = Vec::new();
    if let Some(dir) = std::env::var_os("XWIN_DIR") {
        xwin.push(PathBuf::from(dir));
    }
    if let Some(home) = std::env::var_os("HOME").or_else(|| std::env::var_os("USERPROFILE")) {
        let home = PathBuf::from(home);
        xwin.push(home.join(".cache/cargo-xwin/xwin"));
        xwin.push(home.join("Library/Caches/cargo-xwin/xwin"));
    }
    let lib = std::env::var("LIB").ok();
    find_msvc_library(name, lib.as_deref(), &tools, &xwin).unwrap_or_else(|| {
        panic!(
            "\n\ncompose-rust: could not find {name}, the MSVC library the Windows renderer \
             links the C++ standard library from.\n\n\
             It is part of Visual Studio's C++ build tools (\"Desktop development with C++\"), \
             which linking any Rust program for Windows already needs. It was looked for on \
             LIB, under VCToolsInstallDir, in the newest Visual Studio vswhere reports, and \
             where cargo-xwin keeps the MSVC libraries.\n\n"
        )
    })
}

/// The MSVC tool directory of the newest Visual Studio with the C++ tools, as vswhere finds
/// it. Empty where there is no vswhere, which is every machine but a Windows one.
fn visual_studio_tools() -> Vec<PathBuf> {
    let vswhere = PathBuf::from(
        std::env::var_os("ProgramFiles(x86)").unwrap_or_else(|| "C:\\Program Files (x86)".into()),
    )
    .join("Microsoft Visual Studio")
    .join("Installer")
    .join("vswhere.exe");
    if !vswhere.is_file() {
        return Vec::new();
    }
    let Ok(output) = Command::new(&vswhere)
        .args([
            "-latest",
            "-products",
            "*",
            "-requires",
            "Microsoft.VisualStudio.Component.VC.Tools.x86.x64",
            "-property",
            "installationPath",
        ])
        .output()
    else {
        return Vec::new();
    };
    let installation = String::from_utf8_lossy(&output.stdout).trim().to_string();
    if installation.is_empty() {
        return Vec::new();
    }
    let installation = PathBuf::from(installation);
    let version_file = installation
        .join("VC")
        .join("Auxiliary")
        .join("Build")
        .join("Microsoft.VCToolsVersion.default.txt");
    match std::fs::read_to_string(version_file) {
        Ok(version) => vec![
            installation
                .join("VC")
                .join("Tools")
                .join("MSVC")
                .join(version.trim()),
        ],
        Err(_) => Vec::new(),
    }
}

/// A variable under its current name, or under the name it had before the project was
/// called compose-rust. The old name still works for this release and says so on every
/// build, so a setup that relied on it keeps building and learns what to rename.
fn renamed_var(name: &str, old_name: &str) -> Option<std::ffi::OsString> {
    if let Some(value) = std::env::var_os(name) {
        return Some(value);
    }
    let value = std::env::var_os(old_name)?;
    println!(
        "cargo:warning=compose-rust: {old_name} is the old name of {name}; it is read for now and will stop being read in a later release"
    );
    Some(value)
}

/// Where downloads are cached. This is a property of the machine running the build, not
/// of the target being built for, so it reads the host's own environment.
fn cache_root() -> Option<PathBuf> {
    if let Some(dir) = renamed_var(CACHE_DIR_ENV, "DIOXUS_COMPOSE_CACHE_DIR") {
        return Some(PathBuf::from(dir));
    }
    default_cache_root(
        std::env::var_os("HOME").map(PathBuf::from).as_deref(),
        std::env::var_os("LOCALAPPDATA")
            .map(PathBuf::from)
            .as_deref(),
        cfg!(windows),
    )
}

/// One GET, with whatever this machine already has to make it with.
///
/// No HTTP client in `build-dependencies`: that would put a TLS stack and its tree into
/// every consumer's build for two downloads that happen once per version. `curl` ships
/// with macOS, with Windows since 10 1803, and with nearly every Linux image; `wget`
/// covers the images that leave curl out.
fn fetch(url: &str, destination: &Path) -> Result<(), FetchError> {
    match which("curl") {
        true => curl(url, destination),
        false if which("wget") => wget(url, destination),
        false => Err(FetchError::Unreachable(
            "neither curl nor wget is on PATH".to_string(),
        )),
    }
}

fn which(program: &str) -> bool {
    let finder = if cfg!(windows) { "where" } else { "which" };
    Command::new(finder)
        .arg(program)
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .status()
        .is_ok_and(|status| status.success())
}

fn curl(url: &str, destination: &Path) -> Result<(), FetchError> {
    // --location because a release download is a redirect to object storage, and
    // --write-out because the status code is the difference between "this platform has no
    // artifact" and "something is wrong", and curl otherwise reports both as exit 22.
    let output = Command::new("curl")
        .args(["--silent", "--show-error", "--location"])
        .args(["--connect-timeout", "30", "--retry", "2"])
        .arg("--output")
        .arg(destination)
        .args(["--write-out", "%{http_code}"])
        .arg(url)
        .output()
        .map_err(|error| FetchError::Unreachable(format!("could not run curl: {error}")))?;

    if !output.status.success() {
        let detail = String::from_utf8_lossy(&output.stderr).trim().to_string();
        let code = output.status.code().unwrap_or(-1);
        // 6 is an unresolved host, 7 a refused connection, 28 a timeout: all of them mean
        // the request never got an answer, which is the case worth telling someone how to
        // work around without a network.
        return Err(if matches!(code, 6 | 7 | 28) {
            FetchError::Unreachable(if detail.is_empty() {
                format!("curl exited {code}")
            } else {
                detail
            })
        } else {
            FetchError::Failed(format!("curl exited {code}: {detail}"))
        });
    }

    match String::from_utf8_lossy(&output.stdout).trim() {
        "200" => Ok(()),
        "404" => Err(FetchError::NotFound),
        code => Err(FetchError::Failed(format!("the server answered {code}"))),
    }
}

fn wget(url: &str, destination: &Path) -> Result<(), FetchError> {
    let output = Command::new("wget")
        .args(["--quiet", "--timeout=30", "--tries=2"])
        .arg("--output-document")
        .arg(destination)
        .arg(url)
        .output()
        .map_err(|error| FetchError::Unreachable(format!("could not run wget: {error}")))?;

    if output.status.success() {
        return Ok(());
    }
    let detail = String::from_utf8_lossy(&output.stderr).trim().to_string();
    let code = output.status.code().unwrap_or(-1);
    // wget's exit codes stop at "server error response" and never say which one, so a
    // missing artifact cannot be told from a failing server here. The failure message
    // prints the address for that reason: opening it answers the question in one click.
    Err(match code {
        4 => FetchError::Unreachable(format!("wget could not reach the network: {detail}")),
        _ => FetchError::Failed(format!("wget exited {code}: {detail}")),
    })
}

/// The Android renderer's Kotlin, copied into the Gradle project that is building this.
///
/// Android is the one platform where the Kotlin cannot travel as a compiled artifact. The
/// desktop ships a native-image shared library and iOS a Kotlin/Native archive; Android
/// runs on ART, so the Kotlin is compiled by the application's own Gradle build and has
/// to be there in source form when it runs. dx's template accepts Maven coordinates and
/// nothing else for dependencies, so handing it a compiled library is not possible even
/// if one existed.
///
/// `COMPOSE_RUST_ANDROID_KOTLIN_DIR` says where the Gradle project's Kotlin source
/// directory is. dx sets it, or a person building by hand does. Without it this does
/// nothing and says so once: a cargo build of the crate on its own is a perfectly
/// ordinary thing to do and is not the moment to fail.
fn unpack_android_kotlin() {
    println!("cargo:rerun-if-env-changed={ANDROID_KOTLIN_DIR_ENV}");
    println!("cargo:rerun-if-env-changed={DX_KOTLIN_DIR_ENV}");
    let Some((destination, package)) = android_gradle_kotlin() else {
        println!(
            "cargo:warning=compose-rust: neither {ANDROID_KOTLIN_DIR_ENV} nor \
             {DX_KOTLIN_DIR_ENV} is set, so the renderer's Kotlin was not unpacked. An \
             Android application needs it in its own source directory, because ART \
             compiles it rather than us."
        );
        return;
    };
    let staged = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join(ANDROID_KOTLIN_DIR);
    if !staged.is_dir() {
        panic!(
            "\n\ncompose-rust: the Android Kotlin is missing from this copy of the \
             crate.\n\nIt should be at {}. A checkout regenerates it with \
             scripts/stage-android-kotlin.sh; a published crate carries it.\n\n",
            staged.display()
        );
    }
    println!("cargo:rerun-if-changed={}", staged.display());
    if let Err(error) = copy_tree(&staged, &destination) {
        panic!(
            "\n\ncompose-rust: could not put the renderer's Kotlin into {}: {error}\n\n",
            destination.display()
        );
    }
    write_android_activity(&destination, package);
    add_compose_to_gradle(&destination);
    add_notification_permission(&destination);
}

/// Declares the notification permission in the application's generated manifest.
///
/// Android 13 and later show a notification only for an application that both declares
/// `POST_NOTIFICATIONS` and was granted it at run time. The renderer asks at run time; the
/// declaration has to be in the manifest, and the renderer travels as source with no
/// manifest of its own to merge, so it goes into the application's. The manifest sits beside
/// the Kotlin source root, `<module>/src/main/AndroidManifest.xml`.
///
/// A manifest that already declares it, or that is not where the CLI puts one, is left as
/// it is.
fn add_notification_permission(destination: &Path) {
    let Some(main_dir) = destination.parent() else {
        return;
    };
    let manifest_path = main_dir.join("AndroidManifest.xml");
    println!("cargo:rerun-if-changed={}", manifest_path.display());
    let Ok(manifest) = std::fs::read_to_string(&manifest_path) else {
        return;
    };
    let Some(text) = renderer_dir::with_notification_permission(&manifest) else {
        return;
    };
    if let Err(error) = std::fs::write(&manifest_path, text) {
        panic!(
            "\n\ncompose-rust: could not declare the notification permission in {}: \
             {error}\n\n",
            manifest_path.display()
        );
    }
}

/// Puts Compose into the application module's generated build file.
///
/// `destination` is the Gradle source root, so the module's own directory is three levels
/// above it: `<module>/src/main/kotlin`. The CLI regenerates both files on every build, so
/// this runs every time rather than once, and it runs before Gradle does because the
/// project is written before this crate is compiled.
///
/// A project that does not look like the one the CLI generates is left alone. Someone
/// building by hand has their own build file and it is not this crate's to rewrite.
fn add_compose_to_gradle(destination: &Path) {
    let Some(module_dir) = destination
        .parent()
        .and_then(Path::parent)
        .and_then(Path::parent)
    else {
        return;
    };
    let module_path = module_dir.join("build.gradle.kts");
    let Some(root_dir) = module_dir.parent() else {
        return;
    };
    let root_path = root_dir.join("build.gradle.kts");
    // The application's tooling regenerates both files on every build, so the build has
    // to run again whenever they change or a rebuild would leave them as the template
    // wrote them and nothing would compile against Compose.
    println!("cargo:rerun-if-changed={}", module_path.display());
    println!("cargo:rerun-if-changed={}", root_path.display());
    let (Ok(module), Ok(root)) = (
        std::fs::read_to_string(&module_path),
        std::fs::read_to_string(&root_path),
    ) else {
        return;
    };
    let Some((module_text, root_text)) = renderer_dir::with_compose(&module, &root) else {
        return;
    };
    for (path, text) in [(module_path, Some(module_text)), (root_path, root_text)] {
        let Some(text) = text else { continue };
        if let Err(error) = std::fs::write(&path, text) {
            panic!(
                "\n\ncompose-rust: could not put Compose into {}: {error}\n\n",
                path.display()
            );
        }
    }
}

fn android_gradle_kotlin() -> Option<(PathBuf, Option<String>)> {
    println!("cargo:rerun-if-env-changed={ANDROID_PACKAGE_ENV}");
    println!("cargo:rerun-if-env-changed={DX_PACKAGE_ENV}");
    let read = |name: &str| std::env::var(name).ok();
    renderer_dir::android_gradle_kotlin(
        read(ANDROID_KOTLIN_DIR_ENV).as_deref(),
        read(ANDROID_PACKAGE_ENV).as_deref(),
        read(DX_KOTLIN_DIR_ENV).as_deref(),
        read(DX_PACKAGE_ENV).as_deref(),
    )
}

/// The Activity the application starts at, in the package its own tooling expects.
///
/// Generated rather than shipped. The tooling fixes the package a generated project uses
/// and writes the application id into a build config alias, so an Activity handed over as
/// a static file would sit in the wrong package and never be found. The one the renderer
/// carries is in this crate's own package, which is right for this repository's own
/// application and wrong for everyone else's.
///
/// `COMPOSE_RUST_ANDROID_PACKAGE` names the package. Without it nothing is written,
/// which is what a project supplying its own Activity wants: it has one already, and a
/// second in the same package would not compile.
fn write_android_activity(destination: &Path, package: Option<String>) {
    let Some(package) = package else {
        return;
    };
    // Into the package's own directory, which is where the Kotlin compiler looks for it
    // and where dx put the one it generates. Ours replaces that one: dx writes an Activity
    // that extends wry's, and there is no webview in this application to extend it with.
    let mut path = destination.to_path_buf();
    for component in package.split('.') {
        path = path.join(component);
    }
    if let Err(error) = std::fs::create_dir_all(&path) {
        panic!(
            "\n\ncompose-rust: could not make {} for the generated Activity: \
             {error}\n\n",
            path.display()
        );
    }
    let path = path.join("MainActivity.kt");
    // The file name of the application's own cdylib, without the `lib` prefix and the
    // extension. It is the application's to choose and its tooling knows it, so the
    // Activity carries the answer rather than the runtime guessing at one.
    println!("cargo:rerun-if-env-changed={DX_LIBRARY_ENV}");
    let library = std::env::var(DX_LIBRARY_ENV).unwrap_or_else(|_| "main".to_owned());
    let source = format!(
        r#"// Generated by compose-rust. DO NOT EDIT.
//
// The Android host. Kotlin owns the process and the frame loop: this Activity puts a
// ComposeView on screen and the interpreter draws the Host's tree inside it. Rust runs as
// a cdylib in the same process and never starts a loop of its own.
//
// The Host outlives the Activity, so a configuration change recomposes and nothing else.
package {package}

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.ui.platform.AndroidNotifications
import dev.darkpyonix.composerust.ui.platform.ComposeRustRuntime
import dev.darkpyonix.composerust.ui.platform.installSystemChrome

class MainActivity : ComponentActivity() {{
    // Registered before the Activity starts, which is the only time the platform allows.
    // The prompt is shown when the application first posts or asks.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {{
            AndroidNotifications.permissionAnswered()
        }}

    override fun onCreate(savedInstanceState: Bundle?) {{
        // The window draws under the system's strips with nothing of the system's own
        // over them, and the renderer decides whether the clock and the gesture bar are
        // dark or light against what it drew.
        installSystemChrome(this)
        super.onCreate(savedInstanceState)
        ComposeRustRuntime.load("{library}")
        // Before the Host: a press on a notification that started this process is in the
        // intent, and it is the Host's first event once the Host exists.
        AndroidNotifications.install(this) {{
            notificationPermission.launch("android.permission.POST_NOTIFICATIONS")
        }}
        AndroidNotifications.handle(intent)
        val host = ComposeRustRuntime.host()
        ComposeRustRuntime.attached()
        val view = ComposeView(this)
        view.setContent {{
            // The whole window. Where the system bars are is the renderer's to decide:
            // a bar that opens the tree grows up into the status bar and a navigation
            // grows down into the gesture bar, and whatever is left over is kept off the
            // page there.
            ComposeRustContent(host, Modifier.fillMaxSize())
        }}
        setContentView(view)
    }}

    override fun onStart() {{
        super.onStart()
        ComposeRustRuntime.start()
    }}

    override fun onStop() {{
        ComposeRustRuntime.stop()
        super.onStop()
    }}

    // A press on a notification while this Activity is already running.
    override fun onNewIntent(intent: Intent) {{
        super.onNewIntent(intent)
        AndroidNotifications.handle(intent)
    }}

    override fun onDestroy() {{
        // The process may stay without an Activity, and a worker's notification still has
        // to go out from it.
        ComposeRustRuntime.detached()
        super.onDestroy()
    }}
}}
"#
    );
    if let Err(error) = std::fs::write(&path, source) {
        panic!(
            "\n\ncompose-rust: could not write the Activity to {}: {error}\n\n",
            path.display()
        );
    }
}

/// Every file under `from`, into the same shape under `to`.
/// Stops the build when the renderer was generated from a different schema than this
/// crate.
///
/// The two sides check this at the first boundary call and that check works, but it runs
/// at run time: the program builds, starts, opens a window and draws nothing, and the
/// report comes back as a white window rather than as two artifacts that do not match.
/// Both numbers are already on disk while there is still a build to stop.
fn check_schema_agreement(lib_dir: &Path) {
    let renderer_hash = read_hash(&lib_dir.join("..").join("schema-hash.txt"))
        .or_else(|| read_hash(&lib_dir.join("schema-hash.txt")));
    let ours = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("schema-hash.txt");
    println!("cargo:rerun-if-changed={}", ours.display());
    let crate_hash = read_hash(&ours);
    match renderer_dir::schema_agreement(renderer_hash.as_deref(), crate_hash.as_deref()) {
        renderer_dir::SchemaAgreement::Same => {}
        // A distribution published before renderers began carrying their schema hash. It
        // may match and it may not, and the first anyone knows either way is a window that
        // opens and stays empty, which has been reported as several other things. Saying
        // so here costs a line and saves that.
        renderer_dir::SchemaAgreement::Unknown => println!(
            "cargo:warning=compose-rust: the renderer at {} does not say which schema it \
             was built from, so this build cannot tell whether the two agree. If the \
             window comes up empty, that is why: use a renderer built from this source \
             tree, or set COMPOSE_RUST_RENDERER_DIR to one.",
            lib_dir.display()
        ),
        renderer_dir::SchemaAgreement::Different {
            renderer,
            crate_hash,
        } => panic!(
            "\n\ncompose-rust: this renderer was generated from a different schema than \
             this crate.\n\n  renderer: {renderer}\n  crate:    {crate_hash}\n\nA program \
             built from the two would compile, start, open a window and draw nothing, \
             because the first call across the boundary is refused.\n\nThe renderer at \
             {} is the one to replace. A published renderer only matches the crate \
             version it was published with, so a checkout whose schema has moved needs one \
             built from that checkout: run the renderer build for this platform, or point \
             COMPOSE_RUST_RENDERER_DIR at one that was.\n\n",
            lib_dir.display()
        ),
    }
}

fn read_hash(path: &Path) -> Option<String> {
    std::fs::read_to_string(path)
        .ok()
        .map(|value| value.trim().to_owned())
        .filter(|value| !value.is_empty())
}

/// Puts the renderer where a Windows loader and AWT will both find it.
///
/// Two separate requirements, and satisfying only the first is worse than satisfying
/// neither, because the program then starts and dies later with a message about a file
/// nobody asked for.
///
/// The loader searches the directory the executable is in, so the libraries are copied
/// there flat. That alone is what a program needs in order to start, and it is what the
/// code generator in this package needs, which links the renderer only because it shares a
/// package with it and died on startup before generating a line.
///
/// AWT then wants a distribution around it. The renderer sets `java.home` to the parent of
/// wherever it was loaded from, and AWT resolves its own libraries against `java.home/bin`.
/// A flat copy beside an executable in `target/release/examples` therefore sends AWT to
/// `target/release/bin`, which is a directory nobody made. So the distribution is copied
/// whole, keeping `bin` and `lib`, into the parent of each place an executable lands.
///
/// Cargo does not tell a build script where binaries land. OUT_DIR is
/// `<target>/<profile>/build/<crate>-<hash>/out`, so three levels up is the profile
/// directory, `examples/` and `deps/` beside it are where examples and tests land, and the
/// parents of those two are the profile directory and the target directory.
fn copy_renderer_beside_executables(lib_dir: &Path) {
    let Some(out_dir) = std::env::var_os("OUT_DIR") else {
        return;
    };
    let out_dir = PathBuf::from(out_dir);
    let Some(profile_dir) = out_dir
        .parent()
        .and_then(Path::parent)
        .and_then(Path::parent)
    else {
        return;
    };

    // Flat, for the loader: every executable's own directory.
    for directory in [
        profile_dir.to_path_buf(),
        profile_dir.join("examples"),
        profile_dir.join("deps"),
    ] {
        copy_newer_files(lib_dir, &directory);
    }

    // Whole, for AWT: the parent of every executable's directory. The distribution root is
    // the parent of the directory the libraries are in, which is `bin` on Windows and
    // `lib` everywhere else.
    let Some(distribution) = lib_dir.parent() else {
        return;
    };
    let mut roots = vec![profile_dir.to_path_buf()];
    if let Some(target_dir) = profile_dir.parent() {
        roots.push(target_dir.to_path_buf());
    }
    for root in roots {
        for child in ["bin", "lib"] {
            let source = distribution.join(child);
            if source.is_dir() {
                copy_newer_files(&source, &root.join(child));
            }
        }
    }
}

/// Copies the files of one directory into another, skipping what is already newer there.
///
/// These are tens of megabytes and they are copied into five places, so every build would
/// otherwise move half a gigabyte to no purpose.
fn copy_newer_files(from: &Path, to: &Path) {
    let Ok(entries) = std::fs::read_dir(from) else {
        return;
    };
    if std::fs::create_dir_all(to).is_err() {
        return;
    }
    for entry in entries.flatten() {
        let source = entry.path();
        if !source.is_file() {
            continue;
        }
        let destination = to.join(entry.file_name());
        let newer = match (source.metadata(), destination.metadata()) {
            (Ok(from), Ok(to)) => match (from.modified(), to.modified()) {
                (Ok(from), Ok(to)) => from > to,
                _ => true,
            },
            (Ok(_), Err(_)) => true,
            _ => false,
        };
        if newer {
            let _ = std::fs::copy(&source, &destination);
        }
    }
}

fn copy_tree(from: &Path, to: &Path) -> std::io::Result<()> {
    std::fs::create_dir_all(to)?;
    for entry in std::fs::read_dir(from)? {
        let entry = entry?;
        let target = to.join(entry.file_name());
        if entry.file_type()?.is_dir() {
            copy_tree(&entry.path(), &target)?;
        } else {
            std::fs::copy(entry.path(), &target)?;
        }
    }
    Ok(())
}
