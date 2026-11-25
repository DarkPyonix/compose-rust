# Metadata mapping

An application describes itself once. Each packager reads these five values and the
templates receive them as `{{name}}` placeholders. Placeholders are replaced verbatim after
XML or desktop-entry escaping; there is no logic in a template.

A template is the part of the file every application has. A packager renders it first and may
then only add to it: lines, keys, attributes, elements or array items the template does not
have, or children inside an element the template leaves empty, keeping the order of what the
template has. It never changes or drops what the template rendered. When a packager needs to,
the template changes here.

| Value | Placeholder | Meaning |
|---|---|---|
| name | `{{name}}` | Human readable application name |
| identifier | `{{identifier}}` | Reverse-DNS id, for example `dev.example.hello`. Letters, digits, `-`, at least two dot separated parts |
| version | `{{version}}` | Semantic version `MAJOR.MINOR.PATCH` |
| icon | `{{icon}}` | One source image (PNG 1024 px or SVG); every size is derived from it |
| description | `{{description}}` | One sentence |

Derived placeholders (computed by the implementation, never read from the app):

| Placeholder | Rule |
|---|---|
| `{{executable}}` | file name of the single executable, without a path |
| `{{version4}}` | `version` plus a fourth `.0` (MSIX needs four parts); the fourth part is a revision counter |
| `{{build}}` | integer build number (`CFBundleVersion`), default `1` |
| `{{publisher}}` | publisher / developer name; default is `name` |
| `{{publisher_id}}` | Windows publisher subject (`CN=...`); default `CN={{publisher}}` |
| `{{summary}}` | `description` without its final full stop (AppStream and desktop entries read a summary as a fragment) |
| `{{developer_id}}` | AppStream developer id, reverse DNS of the developer; default `identifier` |
| `{{metadata_license}}` | SPDX license of the metainfo file itself, default `CC0-1.0` |
| `{{identity_name}}` | MSIX `Identity Name`; for the Store, the name Partner Center reserved; default `identifier` with unsupported characters replaced by `-` |
| `{{arch}}` | MSIX processor architecture, `x64` or `arm64`; default `x64` |
| `{{language}}` | the first MSIX resource language; default `en-us`. More languages are additions |
| `{{background_color}}` | MSIX tile background, a `#RRGGBB` colour or `transparent`; default `transparent` |
| `{{date}}` | release date `YYYY-MM-DD` |
| `{{category_mac}}` | `LSApplicationCategoryType`, default `public.app-category.utilities` |
| `{{category_xdg}}` | desktop entry categories, `;` terminated, default `Utility;` |
| `{{license}}` | SPDX license id, default `LicenseRef-proprietary` |
| `{{runtime_version}}` | Flatpak runtime branch, default `25.08` (the newest the `flathub` remote installs) |
| `{{app_id}}` | MSIX application id: `name` reduced to ASCII letters and digits, `App` if it does not start with a letter |
| `{{min_windows}}` / `{{max_windows_tested}}` | `10.0.17763.0` / `10.0.26100.0` |
| `{{min_macos}}` | `13.0` |

How each value maps per format:

| Format | name | identifier | version | icon | description |
|---|---|---|---|---|---|
| `.app` / `.dmg` / `.pkg` | `CFBundleName`, `CFBundleDisplayName` | `CFBundleIdentifier` | `CFBundleShortVersionString` | `AppIcon.icns` (`CFBundleIconFile`) | `.dmg` volume name only; `.pkg` product title |
| `.AppImage` (Velopack) | `Name=` in the `.desktop` file (`--packTitle`) | `.desktop` file name, `Icon=` (`--packId`) | `--packVersion` | `.DirIcon` and `{{identifier}}.png` at the AppDir root | none (vpk writes `Comment=` from the title and version) |
| `.deb` / `.rpm` | `Package`/`Name` (lowercased, `-`) | desktop file name | `Version` | hicolor icons | `Description` |
| Flatpak | `<name>` in metainfo | manifest `id`, desktop and metainfo file names | metainfo `<release version>` | `share/icons/hicolor/<size>/apps/{{identifier}}.png` | metainfo `<summary>` and `<description>` |
| `.msix` | `DisplayName`, `VisualElements DisplayName` | `Identity Name` (`{{identity_name}}`) | `Identity Version` (`{{version4}}`) | the four logo assets in `Assets\` | `VisualElements Description` |
| `.exe` installer (Velopack) | `--packTitle` | `--packId` (letters, digits, `.`, `-`, `_`) | `--packVersion` (semver) | `--icon` (`.ico` derived from the icon) | none (use `--releaseNotes` for notes) |
| `.msi` (Velopack, `vpk pack --msi`) | `ProductName` (`--packTitle`) | `UpgradeCode` derived from `--packId` | `ProductVersion` | `--icon` | none |

Rules:

- The identifier is the same string in every format. A format that cannot hold it as is
  (Velopack ids, MSIX identity names) gets the same characters with the unsupported ones
  replaced by `-`, and the replacement is recorded in the build log.
- The version is read once. Formats that need a different shape derive it (`{{version4}}`).
