# packaging-templates

The format templates every desktop packager here fills, and the placeholder map they are
filled by. They are not this repository's: they are a copy of
`extended/packaging/` in the Compose fork (`thisisthepy/compose-multiplatform-core-extended`)
at the commit `PIN` names, with the git blob id of each file.

| File | Filled by |
|---|---|
| `templates/Info.plist.in` | packager-macos (`.app`, so the `.dmg`, the `.app.zip` and the store `.pkg`) |
| `templates/app.desktop.in` | packager-linux (AppImage, `.deb`, `.rpm`, Flatpak) |
| `templates/app.metainfo.xml.in` | packager-linux |
| `templates/flatpak-manifest.json.in` | packager-linux (Flathub manifest) |
| `templates/AppxManifest.xml.in` | packager-windows (`.msix`) |
| `templates/application.manifest.in` | the Windows application manifest |
| `metadata-map.md` | what each `{{placeholder}}` is and its default |

A packager renders the template text first (each value escaped for its format, then put in
place verbatim; a placeholder left unfilled is an error), and may then only add to it: lines,
keys or elements the template does not have, or children inside an element the template
leaves empty. It never changes or drops what the template rendered.

Do not edit these files here. Change the template in the fork, then move the pin:

```sh
python3 scripts/check-packaging-templates.py --bump <fork commit>
```

`python3 scripts/check-packaging-templates.py` checks the copy against `PIN` (no network);
`--fetch` also checks `PIN` against the fork. `scripts/tests/packaging-templates.test.sh`
runs both, the second on CI.

This pin is separate from the renderer's (`renderer/scripts/build-compose.sh`), so a
template change does not rebuild the renderer.
