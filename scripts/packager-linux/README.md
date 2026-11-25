# packager-linux

Packages a built compose-rust application for Linux in two ways, both described by the
application's `Dioxus.toml`:

- an **AppImage**. It carries no update information: direct distribution is packaged and
  updated with Velopack, not here;
- a **Flatpak** manifest in the form Flathub reviews. A Flatpak is updated by the store that
  installed it.

| File | What it does |
|---|---|
| `src/` | The `packager-linux` command: desktop entry, AppStream metainfo, icons, AppDir, Flatpak manifest |
| `appimage.sh` | Builds an AppImage with pinned AppImage tools |
| `flatpak.sh` | Writes the manifest, then builds, installs and lints it the way Flathub does |
| `flathub/` | Ember's Flathub listing (`dev.darkpyonix.Ember`) and `ember.sh`, which generates the submission from an Ember release |
| `FLATHUB.md` | Checklist for submitting to Flathub |
| `samples.toml` | Store facts shared by the sample applications, passed as an overlay |
| `demo/` | The small program the packaging workflow wraps and, as a Flatpak, updates from 1.0.0 to 1.0.1 |

Run `packager-linux` with no arguments for its options. `.github/workflows/packager-linux.yml`
runs everything here on GitHub's Ubuntu runners.

## Secrets

The owner creates these in this repository's settings (Settings, Secrets and variables,
Actions).

### APPIMAGE_SIGNING_KEY

The armoured private key of the AppImage signing key (see below), for an AppImage built
with `appimage.sh --sign-key`. No workflow reads it today: the sample releases
(`test-samples.yml`) ship Velopack's self-updating AppImage, made by vpk, which this key
does not sign.

## AppImage signing key

Sign from the first public AppImage onwards, so a downloaded file can be checked against
one key:

- Make the key without an expiry date, keep an offline backup, and store only the armoured
  private key in `APPIMAGE_SIGNING_KEY`.

```sh
gpg --quick-gen-key "DarkPyonix AppImage signing" ed25519 sign never
gpg --armor --export-secret-keys <fingerprint>   # the value of APPIMAGE_SIGNING_KEY
gpg --armor --export <fingerprint>               # the public key, to publish
```

Fingerprint: `<to be filled in by the owner>`

Until the key exists, AppImages built here are unsigned and only for testing. The sample
releases' AppImages are Velopack's and are not signed with this key either.
