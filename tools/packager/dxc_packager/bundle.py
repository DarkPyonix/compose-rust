"""Making, signing and wrapping the .app.

Layout of what this makes:

    Name.app/Contents/
        Info.plist, PkgInfo
        MacOS/<executable>
        Frameworks/lib/        the renderer's own lib directory, whole, when the
                               executable loads the renderer as a library
        Frameworks/Sparkle.framework     sparkle channel only
        Resources/AppIcon.icns
        embedded.provisionprofile        app-store channel, when given

The renderer directory goes to Frameworks/lib and not straight into Frameworks because AWT
reads its companions from the parent of the renderer's directory plus "lib"; that is
scripts/bundle-renderer.sh's finding, and that script is what points the executable at the
copy. An executable that is the whole application (one native image with the renderer
linked in) has no renderer library, and then there is nothing to copy.
"""

import os
import plistlib
import shutil
import subprocess
import tempfile
from pathlib import Path

from . import plist as plists
from .metadata import PackagingError

RENDERER_LIBRARY = "libdioxus_compose_renderer.dylib"
REPOSITORY = Path(__file__).resolve().parents[3]
BUNDLE_RENDERER = REPOSITORY / "scripts" / "bundle-renderer.sh"

# What the Sparkle framework contains that is code, innermost first. Signing has to go
# from the inside out, each piece before whatever contains it, and `codesign --deep` is
# not a substitute: it signs everything with the outer entitlements, which gives Sparkle's
# Downloader service the wrong ones.
SPARKLE_NESTED = (
    ("Versions/B/XPCServices/Installer.xpc", False),
    ("Versions/B/XPCServices/Downloader.xpc", True),
    ("Versions/B/Autoupdate", False),
    ("Versions/B/Updater.app", False),
)

# Strings that are in any binary which can drive Sparkle. The store build is checked for
# them as well as for the framework, because a host compiled with the updater still names
# them even when the framework was left out, and App Review reads binaries.
SPARKLE_MARKERS = (b"SPUStandardUpdaterController", b"Sparkle.framework")


def run(command, **kwargs):
    try:
        return subprocess.run(command, check=True, text=True, capture_output=True, **kwargs)
    except FileNotFoundError as error:
        raise PackagingError(f"{command[0]} is not installed: {error}") from error
    except subprocess.CalledProcessError as error:
        raise PackagingError(
            f"{' '.join(map(str, command))} failed with status {error.returncode}:\n"
            f"{error.stdout}{error.stderr}"
        ) from error


def load_commands(path):
    """The libraries a Mach-O file names, from every architecture it has."""
    output = run(["otool", "-L", str(path)]).stdout
    # Libraries are indented; the unindented lines are "file:" or, for a universal file,
    # "file (architecture arm64):" headers, one per architecture. A library lists its own
    # install name first, which is what it is called and not something it loads.
    own = set(
        line.strip()
        for line in run(["otool", "-D", str(path)]).stdout.splitlines()
        if line.strip() and not line.rstrip().endswith(":")
    )
    return [
        name
        for name in (
            line.strip().split(" (", 1)[0]
            for line in output.splitlines()
            if line[:1].isspace() and line.strip()
        )
        if name not in own
    ]


def renderer_reference(executable):
    """How the executable names the renderer library, or None if it does not load one."""
    for path in load_commands(executable):
        if path.endswith("/" + RENDERER_LIBRARY):
            return path
    return None


def renderer_dir_of(executable, explicit=None):
    """The directory to copy the renderer from, or None for an executable without one.

    The crate's build records the renderer's absolute path in the executable, and that
    path is where the directory is. An executable that names it through @rpath (what the
    first published crate did) does not say where it is, and `explicit` has to.
    """
    reference = renderer_reference(executable)
    if reference is None:
        if explicit is not None:
            raise PackagingError(
                f"--renderer-dir was given, but {executable} does not load "
                f"{RENDERER_LIBRARY}; it is either the whole application already or not a "
                f"dioxus-compose executable"
            )
        return None
    if explicit is not None:
        return Path(explicit)
    if reference.startswith("@executable_path/"):
        raise PackagingError(
            f"{executable} already loads the renderer relative to itself ({reference}); "
            f"package the executable cargo built, not one taken from a bundle"
        )
    if not reference.startswith("/"):
        raise PackagingError(
            f"{executable} loads the renderer as {reference}, which does not say where the "
            f"renderer is. Pass --renderer-dir with the directory that holds "
            f"{RENDERER_LIBRARY}."
        )
    return Path(reference).parent


def is_mach_o(path):
    try:
        with open(path, "rb") as handle:
            magic = handle.read(4)
    except OSError:
        return False
    return magic in (
        b"\xcf\xfa\xed\xfe",  # 64 bit, little endian
        b"\xce\xfa\xed\xfe",
        b"\xca\xfe\xba\xbe",  # universal
        b"\xbe\xba\xfe\xca",
    )


def hardware_requirements(app):
    """What Sparkle should require of a Mac before offering this app: "arm64" or nothing."""
    info = plistlib.loads((Path(app) / "Contents" / "Info.plist").read_bytes())
    executable = Path(app) / "Contents" / "MacOS" / info["CFBundleExecutable"]
    architectures = set(run(["lipo", "-archs", str(executable)]).stdout.split())
    if architectures and architectures <= {"arm64", "arm64e"}:
        return "arm64"
    return None


def make_icns(source, destination):
    source = Path(source)
    if source.suffix.lower() == ".icns":
        shutil.copyfile(source, destination)
        return
    width = int(run(["sips", "-g", "pixelWidth", str(source)]).stdout.split()[-1])
    if width < 1024:
        print(
            f"warning: {source} is {width} pixels wide. The Mac App Store asks for a 1024 "
            f"pixel icon (512 points at 2x), so this one is scaled up."
        )
    with tempfile.TemporaryDirectory() as scratch:
        iconset = Path(scratch) / "AppIcon.iconset"
        iconset.mkdir()
        for points in (16, 32, 128, 256, 512):
            for scale in (1, 2):
                pixels = points * scale
                suffix = "" if scale == 1 else "@2x"
                run(
                    [
                        "sips", "-z", str(pixels), str(pixels), str(source),
                        "--out", str(iconset / f"icon_{points}x{points}{suffix}.png"),
                    ]
                )
        run(["iconutil", "-c", "icns", str(iconset), "-o", str(destination)])


def assemble(
    metadata,
    executable,
    out_dir,
    channel,
    *,
    sparkle=None,
    sparkle_framework=None,
    uses_non_exempt_encryption=None,
    extra_plist=None,
    provisioning_profile=None,
    renderer_dir=None,
):
    """Build Name.app in `out_dir` and return its path. Replaces one already there."""
    executable = Path(executable)
    if not executable.is_file():
        raise PackagingError(f"no executable at {executable}")
    info = plists.info_plist(
        metadata,
        channel,
        sparkle=sparkle,
        uses_non_exempt_encryption=uses_non_exempt_encryption,
        extra=extra_plist,
    )
    if channel == plists.SPARKLE:
        if sparkle_framework is None:
            raise PackagingError(
                "the sparkle channel needs --sparkle-framework, the Sparkle.framework "
                "from a Sparkle 2 release archive"
            )
        sparkle_framework = Path(sparkle_framework)
        if not (sparkle_framework / "Versions" / "B" / "Sparkle").exists():
            raise PackagingError(f"{sparkle_framework} is not a Sparkle 2 framework")
    elif sparkle_framework is not None:
        raise PackagingError("the app-store build must not contain Sparkle.framework")
    if channel == plists.APP_STORE and provisioning_profile is None:
        print(
            "warning: no provisioning profile. The bundle runs and can be checked, but App "
            "Store Connect refuses an upload without the profile for this identifier."
        )

    app = Path(out_dir) / f"{metadata.name}.app"
    if app.exists():
        shutil.rmtree(app)
    contents = app / "Contents"
    (contents / "MacOS").mkdir(parents=True)
    (contents / "Resources").mkdir()
    (contents / "Frameworks").mkdir()

    staged = contents / "MacOS" / metadata.executable
    shutil.copy2(executable, staged)
    os.chmod(staged, 0o755)

    renderer = renderer_dir_of(executable, renderer_dir)
    if renderer is not None:
        if not (renderer / RENDERER_LIBRARY).exists():
            raise PackagingError(f"there is no {RENDERER_LIBRARY} in {renderer}")
        # Contents/Frameworks may hold only code. codesign seals anything else there as an
        # unsigned subcomponent and refuses the bundle, so say which file it is now rather
        # than leave the person to read that out of a codesign failure. The macOS renderer
        # directory is all dylibs today.
        strays = [
            str(path.relative_to(renderer))
            for path in sorted(renderer.rglob("*"))
            if path.is_file() and not is_mach_o(path)
        ]
        if strays:
            raise PackagingError(
                f"the renderer directory {renderer} holds files that are not code "
                f"({', '.join(strays)}). Contents/Frameworks/lib may hold only code, so "
                f"these would make the bundle unsignable; data the renderer reads belongs "
                f"in Contents/Resources."
            )
        # symlinks=True keeps the directory as it was laid out; nothing in it is a link
        # today, and a link pointing outside the bundle would be refused by codesign.
        shutil.copytree(renderer, contents / "Frameworks" / "lib", symlinks=True)
        run(["bash", str(BUNDLE_RENDERER), str(staged), "../Frameworks/lib"])

    if sparkle_framework is not None:
        # symlinks=True: a framework is Versions/Current plus links into it, and copying
        # the links as files makes a framework codesign rejects as ambiguous.
        shutil.copytree(
            sparkle_framework, contents / "Frameworks" / "Sparkle.framework", symlinks=True
        )

    if metadata.icon is not None:
        make_icns(metadata.icon, contents / "Resources" / "AppIcon.icns")
    if provisioning_profile is not None:
        shutil.copyfile(provisioning_profile, contents / "embedded.provisionprofile")

    (contents / "Info.plist").write_bytes(plists.dumps(info))
    (contents / "PkgInfo").write_text("APPL????")
    return app


def _codesign(
    path, identity, *, entitlements=None, runtime=True, preserve_entitlements=False, keychain=None
):
    command = ["codesign", "--force", "--sign", identity]
    if keychain is not None:
        command += ["--keychain", str(keychain)]
    if identity != "-":
        # A secure timestamp is required for notarization. Ad-hoc signatures have no
        # certificate to timestamp.
        command.append("--timestamp")
    if runtime:
        command += ["--options", "runtime"]
    if entitlements is not None:
        command += ["--entitlements", str(entitlements)]
    elif preserve_entitlements:
        command.append("--preserve-metadata=entitlements")
    command.append(str(path))
    run(command)


def sign(app, identity, channel, entitlements, *, keychain=None):
    """Sign every piece of code in `app`, innermost first, then the app itself.

    `identity` is "-" for an ad-hoc signature, otherwise the name or hash of a certificate:
    "Developer ID Application: ..." for the sparkle channel, "3rd Party Mac Developer
    Application: ..." or "Apple Distribution: ..." for the app-store channel.
    """
    app = Path(app)
    contents = app / "Contents"
    # The store build runs sandboxed and the store re-checks it; the hardened runtime is
    # what notarization requires of the other one. Both get it: the sandboxed build loses
    # nothing by it and one signing path is one fewer thing to get wrong.
    runtime = True

    libraries = contents / "Frameworks" / "lib"
    if libraries.is_dir():
        for path in sorted(libraries.rglob("*")):
            if path.is_file() and not path.is_symlink() and is_mach_o(path):
                _codesign(path, identity, runtime=runtime, keychain=keychain)

    framework = contents / "Frameworks" / "Sparkle.framework"
    if framework.exists():
        if channel == plists.APP_STORE:
            raise PackagingError(f"{framework} is in an app-store build")
        for relative, keep_entitlements in SPARKLE_NESTED:
            nested = framework / relative
            if nested.exists():
                _codesign(
                    nested,
                    identity,
                    runtime=runtime,
                    preserve_entitlements=keep_entitlements,
                    keychain=keychain,
                )
        _codesign(framework, identity, runtime=runtime, keychain=keychain)

    handle, name = tempfile.mkstemp(suffix=".entitlements")
    os.close(handle)
    entitlements_file = Path(name)
    try:
        entitlements_file.write_bytes(plists.dumps(entitlements))
        _codesign(
            app, identity, entitlements=entitlements_file, runtime=runtime, keychain=keychain
        )
    finally:
        entitlements_file.unlink()
    run(["codesign", "--verify", "--strict", "--deep", "--verbose=2", str(app)])
    return app


def zip_for_sparkle(app, out):
    """The archive Sparkle downloads: a zip made by ditto, which keeps links and metadata."""
    out = Path(out)
    if out.exists():
        out.unlink()
    run(["ditto", "-c", "-k", "--sequesterRsrc", "--keepParent", str(app), str(out)])
    return out


def dmg(app, out, volume_name):
    """A disk image holding the app and a link to /Applications, for handing out directly."""
    out = Path(out)
    if out.exists():
        out.unlink()
    with tempfile.TemporaryDirectory() as scratch:
        staging = Path(scratch) / volume_name
        staging.mkdir()
        run(["ditto", str(app), str(staging / Path(app).name)])
        (staging / "Applications").symlink_to("/Applications")
        run(
            [
                "hdiutil", "create", "-volname", volume_name, "-srcfolder", str(staging),
                "-ov", "-format", "UDZO", str(out),
            ]
        )
    return out


def pkg(app, out, installer_identity=None):
    """The installer package App Store Connect takes, installing the app into /Applications."""
    out = Path(out)
    if out.exists():
        out.unlink()
    command = ["productbuild", "--component", str(app), "/Applications"]
    if installer_identity:
        command += ["--sign", installer_identity]
    command.append(str(out))
    run(command)
    return out


def notarize(path, keychain_profile):
    """Submit to Apple's notary service, wait, and staple the ticket to the app or disk image.

    `keychain_profile` is the name given to `xcrun notarytool store-credentials`, which
    keeps the Apple ID app password or App Store Connect API key out of command lines.
    """
    run(
        [
            "xcrun", "notarytool", "submit", str(path),
            "--keychain-profile", keychain_profile, "--wait",
        ]
    )


def staple(path):
    run(["xcrun", "stapler", "staple", str(path)])


def sparkle_residue(app):
    """Everything in `app` that would make it an application that updates itself."""
    app = Path(app)
    problems = []
    info_path = app / "Contents" / "Info.plist"
    if info_path.exists():
        info = plistlib.loads(info_path.read_bytes())
        problems += [f"Info.plist sets {key}" for key in sorted(info) if key.startswith("SU")]
    for path in sorted(app.rglob("*")):
        if path.name == "Sparkle.framework":
            problems.append(f"{path.relative_to(app)} is in the bundle")
        if path.is_file() and not path.is_symlink() and is_mach_o(path):
            data = path.read_bytes()
            for marker in SPARKLE_MARKERS:
                if marker in data:
                    problems.append(
                        f"{path.relative_to(app)} contains {marker.decode()}; build the "
                        f"store executable without the updater"
                    )
    return problems


def absolute_references(app):
    """Libraries the bundle's code loads from outside the bundle and outside the system."""
    app = Path(app)
    problems = []
    for path in sorted(app.rglob("*")):
        if not path.is_file() or path.is_symlink() or not is_mach_o(path):
            continue
        for target in load_commands(path):
            if target.startswith("/") and not target.startswith(("/usr/lib/", "/System/")):
                problems.append(f"{path.relative_to(app)} loads {target}")
    return problems


def entitlements_of(app):
    output = run(["codesign", "-d", "--entitlements", "-", "--xml", str(app)]).stdout
    if not output.strip():
        return {}
    start = output.find("<?xml")
    return plistlib.loads(output[start:].encode()) if start >= 0 else {}
