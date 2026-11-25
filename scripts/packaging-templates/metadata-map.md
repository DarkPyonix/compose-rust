# Metadata mapping

An application describes itself once. Each packager reads these five values and the
templates receive them as `{{name}}` placeholders. Placeholders are replaced verbatim after
XML or desktop-entry escaping; there is no logic in a template.

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
| `{{date}}` | release date `YYYY-MM-DD` |
| `{{category_mac}}` | `LSApplicationCategoryType`, default `public.app-category.utilities` |
| `{{category_xdg}}` | desktop entry categories, `;` terminated, default `Utility;` |
| `{{license}}` | SPDX license id, default `LicenseRef-proprietary` |
| `{{runtime_version}}` | Flatpak runtime branch, default `24.08` |
| `{{app_id}}` | MSIX application id: `name` reduced to ASCII letters and digits, `App` if it does not start with a letter |
| `{{min_windows}}` / `{{max_windows_tested}}` | `10.0.17763.0` / `10.0.26100.0` |
| `{{min_macos}}` | `13.0` |

How each value maps per format:

| Format | name | identifier | version | icon | description |
|---|---|---|---|---|---|
| `.app` / `.dmg` / `.pkg` | `CFBundleName`, `CFBundleDisplayName` | `CFBundleIdentifier` | `CFBundleShortVersionString` | `AppIcon.icns` (`CFBundleIconFile`) | `.dmg` volume name only; `.pkg` product title |
| `.AppImage` | `Name=` in the `.desktop` file | `.desktop` file name, `Icon=` | file name suffix, metainfo `<release>` | `.DirIcon` and `{{identifier}}.png` at the AppDir root | `Comment=` |
| `.deb` / `.rpm` | `Package`/`Name` (lowercased, `-`) | desktop file name | `Version` | hicolor icons | `Description` |
| Flatpak | `<name>` in metainfo | manifest `id`, desktop and metainfo file names | metainfo `<release version>` | `share/icons/hicolor/<size>/apps/{{identifier}}.png` | metainfo `<summary>` and `<description>` |
| `.msix` | `DisplayName`, `VisualElements DisplayName` | `Identity Name` | `Identity Version` (`{{version4}}`) | the four logo assets in `Assets\` | `VisualElements Description` |
| `.exe` installer (Velopack) | `--packTitle` | `--packId` (letters, digits, `.`, `-`, `_`) | `--packVersion` (semver) | `--icon` (`.ico` derived from the icon) | none (use `--releaseNotes` for notes) |
| `.msi` (on hold) | `Product Name` | `UpgradeCode` derived from identifier | `Product Version` | `ARPPRODUCTICON` | `ARPCOMMENTS` |

Rules:

- The identifier is the same string in every format. A format that cannot hold it as is
  (Velopack ids, MSIX identity names) gets the same characters with the unsupported ones
  replaced by `-`, and the replacement is recorded in the build log.
- The version is read once. Formats that need a different shape derive it (`{{version4}}`).
