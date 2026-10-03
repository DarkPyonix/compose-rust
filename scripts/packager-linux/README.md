# packager-linux

Packages a built compose-rust application for Linux in two ways, both described by the
application's `Dioxus.toml`:

- an **AppImage** that updates itself. It carries zsync update information and
  `appimageupdatetool`, and the application asks before it updates, through
  `compose_rust::update`;
- a **Flatpak** manifest in the form Flathub reviews. A Flatpak is updated by the store that
  installed it.

| File | What it does |
|---|---|
| `src/` | The `packager-linux` command: desktop entry, AppStream metainfo, icons, AppDir, update information, Flatpak manifest |
| `appimage.sh` | Builds an AppImage and its `.zsync` with pinned AppImage tools |
| `flatpak.sh` | Writes the manifest, then builds, installs and lints it the way Flathub does |
| `flathub/` | Ember's Flathub listing (`dev.darkpyonix.Ember`) and `ember.sh`, which generates the submission from an Ember release |
| `FLATHUB.md` | Checklist for submitting to Flathub |
| `samples.toml` | Store facts shared by the sample applications, passed as an overlay |
| `demo/` | The small program the packaging workflow updates from 1.0.0 to 1.0.1 |
| `ci/` | Helpers the packaging workflow uses |

Run `packager-linux` with no arguments for its options. `.github/workflows/packager-linux.yml`
runs everything here on GitHub's Ubuntu runners.

## Where updates come from

Each application updates from the releases of its own repository. Its AppImage carries
`gh-releases-zsync|<owner>|<repo>|latest|<Name>-*-<arch>.AppImage.zsync`, which matches the
newest release that is not a pre-release. Ember updates from `DarkPyonix/darkpyonix-ember`.

The samples are released to `DarkPyonix/compose-rust-samples`, not to this repository. That
way `latest` means the newest samples rather than the newest library release.

## Secrets

The owner creates these in this repository's settings (Settings, Secrets and variables,
Actions).

### SAMPLES_RELEASE_TOKEN

The `Sample apps` workflow publishes to `DarkPyonix/compose-rust-samples`. The token a
workflow is given can only write to its own repository, so it needs another one.

1. On GitHub, open Settings, Developer settings, Personal access tokens, Fine-grained
   tokens, and choose Generate new token.
2. Resource owner: DarkPyonix. Repository access: Only select repositories, then
   `DarkPyonix/compose-rust-samples` only.
3. Repository permissions: Contents, Read and write. Leave everything else at No access.
4. Save the token as the Actions secret `SAMPLES_RELEASE_TOKEN` in `DarkPyonix/compose-rust`.

Without it, the workflow's first job fails within seconds and names the missing secret,
before any build runs. Fine-grained tokens expire, so renew it before the expiry date.

### APPIMAGE_SIGNING_KEY

The armoured private key of the AppImage signing key (see below). While it is missing, the
`Sample apps` workflow builds AppImages unsigned and warns that it did.

## AppImage signing key

An installed AppImage that was signed refuses any update that was not signed by the same key.
That makes the key a long-term commitment:

- Sign from the first public AppImage onwards. An AppImage that was installed unsigned
  accepts any file.
- Changing the key breaks updates for everyone who installed an AppImage signed with the old
  one; they have to download the new one by hand.
- Make the key without an expiry date, keep an offline backup, and store only the armoured
  private key in `APPIMAGE_SIGNING_KEY`.

```sh
gpg --quick-gen-key "DarkPyonix AppImage signing" ed25519 sign never
gpg --armor --export-secret-keys <fingerprint>   # the value of APPIMAGE_SIGNING_KEY
gpg --armor --export <fingerprint>               # the public key, to publish
```

Fingerprint: `<to be filled in by the owner>`

Until the key exists, AppImages are built unsigned and only for testing.
