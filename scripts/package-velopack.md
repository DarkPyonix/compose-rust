# package-velopack

`scripts/package-velopack.py` packs a built application with Velopack's `vpk` and writes the
update feed the Velopack SDK reads. It is how compose-rust applications are distributed
outside the stores on all three desktop systems: one tool installs and updates them
everywhere, so there is one update path to test instead of three.

One run packs one version for the system it runs on, because vpk packs only for the
system it runs on. A release runs it three times, on a macOS, a Linux and a Windows runner.

## What the application needs

- The `velopack` crate at the version of the vpk that packs it (`velopack = "=1.2.161"` with
  vpk 1.2.161). Velopack asks for the two to match, because the updater vpk puts in the
  package and the SDK in the application talk to each other.
- `VelopackApp::build().run()` as the first thing `main` does. The updater starts the
  application with hook arguments during install, update and uninstall, and `run()`
  answers them and exits.
- An `UpdateManager` with a source: `velopack::sources::HttpSource` for a URL the feed is
  served from, `GithubSource` for a GitHub repository, `FileSource` for a directory.
  `AutoSource::new` picks one from the string. `scripts/tests/velopack-fixture` is the
  smallest application that does all of this.

## What it makes

`<id>` is the pack id, `<v>` the version, `<ch>` the channel (`osx`, `linux` or `win` unless
`--channel` says otherwise). The names are vpk's own, except the `.dmg`.

| System | Files |
|---|---|
| macOS | `<id>-<ch>.dmg`, `<id>-<ch>-Portable.zip`, `<id>-<v>-<ch>-full.nupkg`, `<id>-<v>-<ch>-delta.nupkg` |
| Linux | `<id>.AppImage` (`<id>-<ch>.AppImage` on a channel other than `linux`), `<id>-<v>-<ch>-full.nupkg`, `<id>-<v>-<ch>-delta.nupkg` |
| Windows | `<id>-<ch>-Setup.exe`, `<id>-<ch>-Portable.zip`, `<id>-<ch>.msi` with `--msi`, `<id>-<v>-full.nupkg`, `<id>-<v>-delta.nupkg` (on the `win` channel the nupkg names carry no channel) |
| All | `releases.<ch>.json` (the feed), `RELEASES` or `RELEASES-<ch>` (the feed for old Squirrel clients), `assets.<ch>.json` (what this run made) |

The delta exists only when `--delta-dir` held an older full package. A first release has
none.

On stdout the script prints one JSON object: the feed, the full and delta packages, the
installer or image, `upload` (the files to publish) and `sha256` (a checksum per uploaded
file). vpk's own output goes to stderr as it runs, so a failure shows what vpk said.

### macOS: signing happens inside vpk

vpk puts `UpdateMac` and `sq.version` into the `.app`, signs the bundle, then builds the
portable `.zip` and the full `.nupkg` from that signed bundle. The script therefore lets vpk
sign, with `--signAppIdentity -` (ad hoc) by default. An update delivers the `.app` inside
the `.nupkg`, so that is the copy whose signature has to hold; signing a copy after vpk
had finished would leave the update unsigned. vpk signs with
`codesign --force --timestamp --options runtime --entitlements <file> --deep`, which takes
`-` as the identity like any other.

Before vpk, the script signs the code inside the bundle innermost first, because
`codesign --deep` does not reach a renderer library under `Contents/Frameworks/lib`. After
vpk, it unpacks the portable `.zip`, checks it with `codesign --verify --deep --strict`,
and puts that same `.app` in the `.dmg` with `If macOS will not open the app.txt`.

An ad hoc signature names no developer, so Gatekeeper asks the person once before the first
open (the notes in the `.dmg` say how). With a Developer ID, pass `--sign-app-identity`
and `--notary-profile`, and vpk signs and notarizes the app instead.

vpk's `Setup.pkg` is not made (`--noInst`), because it installs into `/Applications` and
needs an installer signing identity to open. `--macos-installer` keeps it.

## Options

| Option | Meaning |
|---|---|
| `--executable PATH` | The built executable. Required. |
| `--output-dir DIR` | Where the packages and feed go. Use a new directory per release. Required. |
| `--project DIR` | Directory with `Cargo.toml` and `Dioxus.toml`, read for the id (`[bundle] identifier`), title (`[application] name`), authors (`[bundle] publisher`), version and icons (`[bundle] icon`). |
| `--pack-id`, `--pack-version`, `--pack-title`, `--pack-authors` | Override what the project says. The version must be SemVer 2. The id names the install directory, so one vpk refuses is an error, not rewritten. |
| `--delta-dir DIR` | The previous release's files. Its `*-full.nupkg` are copied into the output, where vpk looks for the base of the delta. |
| `--main-exe NAME` | The file name inside the package (default: the executable's; Windows adds `.exe`). |
| `--icon PATH` | An icon to use first. vpk takes `.ico` on Windows; Linux takes `.png` or `.svg`; macOS takes `.icns` or a `.png` the macOS packager converts. Without an `.ico`, `Setup.exe` gets vpk's default icon and a warning says so. |
| `--channel NAME` | Velopack channel. The installed app updates from `releases.<channel>.json` of its own channel. |
| `--release-notes PATH` | Markdown notes, carried in the feed. |
| `--msi` | Windows: also the `.msi`. |
| `--sign-app-identity ID`, `--sign-entitlements PATH`, `--notary-profile NAME`, `--keychain PATH` | macOS signing, passed to vpk. |
| `--macos-installer` | macOS: keep vpk's `Setup.pkg`. |
| `--vpk PATH` | The vpk command (default `vpk`). |
| `--vpk-arg ARG` | One more argument for `vpk pack`, repeatable, for example `--vpk-arg=--delta=None`. |
| `--dry-run` | Print the vpk command and stop; nothing is written. |

## A release, for example Ember

Each runner installs vpk (`dotnet tool install -g vpk --version 1.2.161`; vpk needs the .NET 8
SDK), builds the application, fetches the previous release's full package and packs:

```sh
vpk download github --repoUrl https://github.com/DarkPyonix/darkpyonix-ember \
    --channel osx --outputDir previous --token "$GITHUB_TOKEN"
uv run scripts/package-velopack.py --project ember/app \
    --executable target/release/ember --pack-version "$VERSION" \
    --icon ember/app/assets/icon.icns \
    --delta-dir previous --output-dir dist
```

`vpk download github` reads the newest release's `releases.<channel>.json` and downloads
its full package into `previous`; with no release yet it downloads nothing, and the first
release has no delta. On Linux the channel is `linux` and the icon a `.png`; on Windows the
channel is `win`, the executable `target/release/ember.exe`, the icon an `.ico`, and
`--msi` adds the `.msi`. Linux also needs `mksquashfs` (Ubuntu's `squashfs-tools`).

Upload every file in the JSON's `upload` list of all three runs to one GitHub Release. The
previous version's full package that `--delta-dir` copied in is listed in the feed but not
in `upload`: it is already in the release before, and no client downloads it again.

## Where the application looks for updates

Two sources work with GitHub Releases, and they differ in what a client several versions
behind gets.

- `HttpSource::new("https://github.com/<owner>/<repo>/releases/latest/download/")`. The SDK
  fetches `releases.<channel>.json` and then each package by its name under that URL,
  which GitHub redirects to the newest release's assets. It makes no API calls, so there
  is no API rate limit. Only the newest release's files are reachable, so a client one
  version behind takes the delta, and a client further behind takes the full package
  (Velopack falls back to the full package when a delta cannot be used).
- `GithubSource::new("https://github.com/<owner>/<repo>", None, false)`. The SDK lists the
  last ten releases through the GitHub API, merges their `releases.<channel>.json`, and
  downloads each package from whichever release has it, so a client several versions
  behind can chain deltas. Without a token the API allows 60 requests an hour per address.

`AutoSource` takes a `github.com` URL as a `GithubSource`, so the `latest/download` form
needs `HttpSource` named explicitly.

`vpk upload github --repoUrl ... --token ... --tag v<version> --channel <ch> --outputDir dist`
is the other way to publish: it uploads the files `assets.<channel>.json` lists and a feed of
just those to the release (`--merge` lets the second and third system add to a release that
exists, `--publish` takes it out of draft).

## The first update downloads the whole package

A delta applies to the full package of the installed version, which the updater keeps in
its package directory. `Setup.exe` puts it there on Windows (`%LocalAppData%\<id>\packages`),
so the first Windows update is a delta. An app copied out of the `.dmg`, or an AppImage that
was downloaded and run, has no such package (`~/Library/Caches/velopack/<id>/packages` on
macOS, `/var/tmp/velopack/<id>/packages` on Linux), so its first update is the full package
and later ones are deltas.

## Install and update, per system

- Windows: `Setup.exe` installs per user into `%LocalAppData%\<id>` (the app in `current\`,
  the updater as `Update.exe` beside it) and starts the app. `--silent` installs without
  windows and does not start it; `--installto DIR` picks another directory.
- macOS: the person opens the `.dmg` and drags the app to Applications. `UpdateMac` swaps
  the whole bundle for the new one in one step.
- Linux: the AppImage runs where it was saved. `UpdateNix` replaces the file at the path the
  AppImage ran from (`$APPIMAGE`). Running it needs FUSE 2 (`libfuse2`, `libfuse2t64` on
  Ubuntu 24.04).

## Tests

`scripts/packager-macos/tests/test_velopack.py` checks the vpk commands and how the script
reads what vpk writes, against a stand-in vpk, with no build. `scripts/tests/fr22-velopack.test.sh
<macos|linux|windows>` does the real thing on a CI runner: it builds the fixture as 1.0.0 and
1.0.1, packs both, installs 1.0.0, updates it from the 1.0.1 feed and runs it again.
`.github/workflows/packager-macos.yml` runs it on all three systems.
