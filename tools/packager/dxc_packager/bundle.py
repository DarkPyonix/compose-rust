"""Making, signing and wrapping the .app.

Layout of what this makes:

    Name.app/Contents/
        Info.plist, PkgInfo
        MacOS/<executable>
        Frameworks/lib/        the renderer's own lib directory, whole, when the
                               executable loads the renderer as a library
        Frameworks/Sparkle.framework     when the bundle updates itself
        Resources/AppIcon.icns

The renderer directory goes to Frameworks/lib and not straight into Frameworks because AWT
reads its companions from the parent of the renderer's directory plus "lib"; that is
scripts/bundle-renderer.sh's finding, and that script is what points the executable at the
copy. An executable that is the whole application (one native image with the renderer
linked in) has no renderer library, and then there is nothing to copy.

Every bundle is signed ad hoc (`codesign --sign -`). That is not optional: Apple silicon
runs no arm64 code without a signature, and copying a library or rewriting its load
commands invalidates the one the linker left. An ad hoc signature names no developer, so
Gatekeeper does not let a downloaded copy open without the person's say-so (see
install-notes.txt), but once it is open nothing else differs, and Sparkle's updates are
verified by their EdDSA signature rather than by who signed the code.
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
INSTALL_NOTES = Path(__file__).resolve().parent / "install-notes.txt"

# What the Sparkle framework contains that is code, innermost first. Signing goes from the
# inside out, each piece before whatever contains it, so that every seal covers signatures
# that are already final. `codesign --deep` would reach the same pieces but in an order
# it chooses and without keeping the Downloader service's own entitlements.
SPARKLE_NESTED = (
    ("Versions/B/XPCServices/Installer.xpc", False),
    ("Versions/B/XPCServices/Downloader.xpc", True),
    ("Versions/B/Autoupdate", False),
    ("Versions/B/Updater.app", False),
)


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
            f"warning: {source} is {width} pixels wide and is scaled up to the 1024 pixels "
            f"a Retina Dock and Finder draw it at; give a 1024 pixel icon to keep it sharp."
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
    *,
    sparkle=None,
    sparkle_framework=None,
    extra_plist=None,
    renderer_dir=None,
):
    """Build Name.app in `out_dir` and return its path. Replaces one already there.

    With `sparkle` settings the bundle updates itself, and `sparkle_framework` (from a
    Sparkle 2 release archive) goes inside it. Without them it is a plain application.
    """
    executable = Path(executable)
    if not executable.is_file():
        raise PackagingError(f"no executable at {executable}")
    info = plists.info_plist(metadata, sparkle=sparkle, extra=extra_plist)
    if sparkle is not None:
        if sparkle_framework is None:
            raise PackagingError(
                "a bundle that updates itself needs --sparkle-framework, the "
                "Sparkle.framework from a Sparkle 2 release archive"
            )
        sparkle_framework = Path(sparkle_framework)
        if not (sparkle_framework / "Versions" / "B" / "Sparkle").exists():
            raise PackagingError(f"{sparkle_framework} is not a Sparkle 2 framework")
    elif sparkle_framework is not None:
        raise PackagingError(
            "Sparkle.framework was given for a bundle with no feed to update from; pass "
            "--updater sparkle with its settings, or leave the framework out"
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

    (contents / "Info.plist").write_bytes(plists.dumps(info))
    (contents / "PkgInfo").write_text("APPL????")
    return app


def _codesign(path, *, preserve_entitlements=False):
    command = ["codesign", "--force", "--sign", "-"]
    if preserve_entitlements:
        command.append("--preserve-metadata=entitlements")
    command.append(str(path))
    run(command)


def sign(app):
    """Sign every piece of code in `app` ad hoc, innermost first, then the app itself."""
    app = Path(app)
    contents = app / "Contents"

    libraries = contents / "Frameworks" / "lib"
    if libraries.is_dir():
        for path in sorted(libraries.rglob("*")):
            if path.is_file() and not path.is_symlink() and is_mach_o(path):
                _codesign(path)

    framework = contents / "Frameworks" / "Sparkle.framework"
    if framework.exists():
        for relative, keep_entitlements in SPARKLE_NESTED:
            nested = framework / relative
            if nested.exists():
                _codesign(nested, preserve_entitlements=keep_entitlements)
        _codesign(framework)

    _codesign(app)
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
    """A disk image for the first install: the app, a link to /Applications, and the notes
    that say how to open an application macOS cannot attribute to a developer."""
    out = Path(out)
    if out.exists():
        out.unlink()
    with tempfile.TemporaryDirectory() as scratch:
        staging = Path(scratch) / volume_name
        staging.mkdir()
        run(["ditto", str(app), str(staging / Path(app).name)])
        (staging / "Applications").symlink_to("/Applications")
        shutil.copyfile(INSTALL_NOTES, staging / "If macOS will not open the app.txt")
        run(
            [
                "hdiutil", "create", "-volname", volume_name, "-srcfolder", str(staging),
                "-ov", "-format", "UDZO", str(out),
            ]
        )
    return out


def sparkle_in(app):
    return (Path(app) / "Contents" / "Frameworks" / "Sparkle.framework").exists()


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


def unsigned_code(app):
    """Code in the bundle that has no valid signature, which Apple silicon will not run."""
    app = Path(app)
    problems = []
    for path in sorted(app.rglob("*")):
        if path.is_file() and not path.is_symlink() and is_mach_o(path):
            result = subprocess.run(
                ["codesign", "--verify", "--strict", str(path)], capture_output=True, text=True
            )
            if result.returncode != 0:
                problems.append(f"{path.relative_to(app)}: {result.stderr.strip()}")
    return problems
