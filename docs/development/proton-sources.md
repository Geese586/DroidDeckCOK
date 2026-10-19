# The ARM64 Protons and the win components: what can be installed, and from where

Checked 2026-10-12 against each project's own release API (`api.github.com/repos/<owner>/<repo>/releases`)
and against each component hub's own index — every asset name below was read back, none of it is recalled
from the version numbers in a changelog. An Android/aarch64 device can only use a Proton that was built
for **aarch64**, so that is the single filter this note applies. (The GE-Proton and DroidDeck-Proton rows
were re-read on 2026-10-18 from the two archives now carried in `FirstLocalInstall/`; the cachyos row
and everything below them still stand from the 10-12 check.)

See also `steam-client-packages.txt` (component download speeds) and the archive at
`00Design/custom-changes/README.md` (what this fork changed, in Chinese).

---

## 1. Proton

### 1.1 The three the app offers

`session/ProtonExtras.kt` holds a fixed list (`tools`); each row of the Proton page is one entry. As of
2026-10-12 it has three.

| row | repo | newest tag | ARM64 asset | size |
|---|---|---|---|---|
| GE-Proton | `GloriousEggroll/proton-ge-custom` | `GE-Proton11-9` | `GE-Proton11-9-aarch64.tar.gz` | 647.9 MB |
| proton-cachyos | `CachyOS/proton-cachyos` | `cachyos-11.0-20261005-slr` | `proton-cachyos-11.0-20261005-slr-arm64.tar.xz` | 356.3 MB |
| DroidDeck-Proton | `The412Banner/DroidDeck-Proton` | `DroidDeck-Proton-11-9.1` | `DroidDeck-Proton-11-9.1-aarch64.tar.gz` | 625.4 MB |

Download URLs are the usual release paths:

```
https://github.com/GloriousEggroll/proton-ge-custom/releases/download/GE-Proton11-9/GE-Proton11-9-aarch64.tar.gz
https://github.com/CachyOS/proton-cachyos/releases/download/cachyos-11.0-20261005-slr/proton-cachyos-11.0-20261005-slr-arm64.tar.xz
https://github.com/The412Banner/DroidDeck-Proton/releases/download/DroidDeck-Proton-11-9.1/DroidDeck-Proton-11-9.1-aarch64.tar.gz
```

**2026-10-18**: the two rows above are the two archives now carried in `FirstLocalInstall/` — the
`GE-Proton11-7` and `DroidDeck-Proton-11-7.5` they replace have been removed from it. The one-key
install reads the **newest** archive of each build by the numbers in its name rather than the first
name the listing sorts to, and a build is counted as already in only when what is installed is that
version or newer, so a folder that gains an archive still installs it (see Z ㉙ in
`00Design/custom-changes/README.md`).

**DroidDeck-Proton** is this project's own build and the only one written for it: GE-Proton as the base
(the `proton` script, DXVK, vkd3d-proton, FEX, protonfixes and the media stack are GE's, unchanged) with
Wine built in seven tiers — 1 Valve `bleeding-edge`, 2 everything GE applies, 3 CachyOS' game fixes GE
does not already carry, 4 the Bannerlator portability fixes, 5 **DroidDeck esync built in**, 6 SD card
fixes, 7 media fixes. Each tier is a tag on the Wine branch, so one can be bisected or dropped alone.
Its own README installs it by hand ("extract into `compatibilitytools.d`"); the app does the same thing
through `droiddeck-proton-extra`, so a row here only ever needed the one `Tool(...)` line.

Note cachyos publishes its ARM64 build **only in the `-slr` variant** — every release of the last twelve
carries `…-slr-arm64.tar.xz` and nothing else ARM64.

### 1.2 Valve's ARM64 Proton

`ValveSoftware/Proton` releases carry **no binaries at all** (0 assets on `proton-11.0-2`), so there is
nothing to point a `Tool(...)` at. The client gets it instead: `runtime/DesktopCatalog` lays down the
`proton-arm64` seed from `steam-seed.json` on first run —

| id | name | version | size |
|---|---|---|---|
| `proton-arm64` | Proton Experimental (ARM64) | `25502785` | 398,113,534 B |

with `appmanifest_4427310.acf`, so the client adopts it at first sign-in and updates it itself from then
on. That is why the app has no Valve row: there is no download to offer.

### 1.3 Ruled out

| candidate | why not |
|---|---|
| DWProton (`dawn-winery/dwproton`) | only `dwproton-11.0-14-x86_64.tar.xz` — no ARM64 asset |
| Proton EM (`Etaash-mathamsetty/Proton`) | `EM-11.0-0` publishes x86_64 only |
| proton-ge-rtsp (`SpookySkeletons/proton-ge-rtsp`) | x86_64 only |
| `afeimod/Debian-proot` | *is* ARM64 (`proton-proton-wine-11.0-1-beta2-…-wow64-xuser.tar.xz`, 94.8 MB) but it is a Termux/glibc build whose tree is laid out for `/data/data/com.termux/files/usr/glibc`. Nothing in it matches `compatibilitytools.d`; an experiment, not a source |

The Proton wcp offered by the Winlator hubs (see §2.4) are the WinNative/bionic line — ARM64EC trees bound
to FEXCore's bionic unixlib. The app's component model only knows `fex` / `dxvk` / `vkd3d`, so a Proton
cannot arrive that way at all, and a bionic one would not run here anyway.

---

## 2. FEX / DXVK / VKD3D-Proton

### 2.1 The catalog in the app is already the broadest one

`session/ComponentsManager.kt` reads **five release tags** of `The412Banner/Nightlies` and offers **every**
`.wcp` in them that GitHub published a sha256 for. All five were last refreshed 2026-09-26:

| tag | component | what it carries |
|---|---|---|
| `Dxvk-Linux` | dxvk | 15 packages — 1.7.2, 1.10.3, 2.3.1, 2.4.1, 2.4.1-pre-reg, 2.4.1-special-d8, 2.5.3, 2.6.2, 2.7.1, 3.0, 3.0.2, 3.1, 3.1.1, low-latency-3.1.1-2, v2.3.1-stripped |
| `Dxvk-gplasync-Linux` | dxvk | 12 — 2.6-2-gplasync → 3.1.1-1, including 3.1-1-binsem |
| `Dxvk-arm64ec-Linux` | dxvk | 11 — 2.3.1 → 3.1.1, including low-latency-arm64ec-3.1.1-2 |
| `Vkd3d-proton-Linux` | vkd3d | 11 — 2.7, 2.9, 2.12, 2.14.1, 3.0a, 3.0b, 3.0b-Tfix, 3.0b-tilting, 3.0.1, low-latency-3.0.1-2, low-latency-arm64ec-3.0.1-2 |
| `FexCore-Linux` | fex | 17 — 2507 → 2610, including 2609-d2rfix25 and 2610-d2rfix28 |

Everything the hub publishes for the *Android* stack lives in the same repo's rolling `nightly-*` release
(`DXVK-v3.1.1.wcp`, `FEXCore-2610-stable.wcp`, `Box64-…`, `WOWBox64-…`, `Turnip-…`). Those are deliberately
**not** in the catalog — the map in `ComponentsManager` says so in as many words: *"Only the Linux repacks:
the Android releases carry bionic unixlibs."* A user paging through the Nightlies releases on the web will
see those names first and must not pick them.

### 2.2 ⚠️ It has to be a `-Linux` package

Where a Proton reads each component, from the class comment in `ComponentsManager.kt` (ARM64 host):

```
DXVK   files/lib/wine/dxvk/{aarch64-windows,i386-windows}          -> the prefix's system32 / syswow64
VKD3D  files/lib/wine/vkd3d-proton/{aarch64-windows,i386-windows}  -> the same, on every launch
FEX    files/lib/wine/aarch64-windows/lib{arm64ec,wow64}fex.dll
       files/lib/wine/aarch64-unix/lib{arm64ec,wow64}fex.so        (FEX 2607 and newer)
```

with `PE_ARCHES = listOf("aarch64-windows", "i386-windows")`. A Winlator/GameNative package is built for
the other stack — `x64`/`x86` PE directories and a **bionic** `libwow64fex.so` — so importing one gives a
package that installs and then does nothing. Importing is not refused; the layout simply does not match.

### 2.3 Importing one by hand

Components page → **Import .wcp** (`ui/ComponentsPage.kt`, string `comp_import_wcp`) →
`MainActivity.importComponent` → `ComponentsManager.importPackage`, which copies it into
`filesDir/components/packages/` and reads its `profile.json` for the version. It is then swapped into a
Proton like any catalog entry. The picker takes any file the app can read, from any hub — the package
format is all that matters, not where it came from.

### 2.4 Other hubs, if a version is missing

| hub | index |
|---|---|
| Nightlies (the app's own source) | `https://github.com/The412Banner/Nightlies/releases` |
| winlator-contents (the app's other catalogs live here too) | `https://raw.githubusercontent.com/The412Banner/winlator-contents/main/contents.json` |
| WinNative-components | `https://raw.githubusercontent.com/nicholasx417/WinNative-components/refs/heads/main/contents.json` |
| Xnick's Nightly | `https://raw.githubusercontent.com/Xnick417x/winlator-nightly-wcp/refs/heads/main/contents.json` |
| Winlator WCP Hub | `https://raw.githubusercontent.com/Arihany/WinlatorWCPHub/refs/heads/main/pack.json` |
| GameNative | `https://downloads.gamenative.app/manifest.json` (`driver` / `dxvk` / `vkd3d` / `fexcore` / `proton` / `box64` / `wowbox64`, every entry `"variant": "bionic"`) |
| upstream, to build one | `doitsujin/dxvk`, `HansKristian-Work/vkd3d-proton`, `FEX-Emu/FEX`, `pythonlover02/DXVK-Sarek`, `Ph42oN/dxvk-gplasync` (GitLab) |

`The412Banner/winlator-contents` matters twice over: besides its WCP index it is where the app's
`linuxfs.json` (Linux runtime), `desktop.json` (desktop and emulators), `steam-seed.json` (the Proton
seed) and `components.json` (Windows components) come from. The binaries those indexes point at live in
the sibling repo `The412Banner/winlator-contents` releases and in `Droid-Deck/DroidDeck-Components`
(`steam-proton-arm64-…`, `steamdeck-desktop-r1`, `system-libraries-v1`, `droiddeck-esync-…`).

https://raw.githubusercontent.com/ is **not reachable from a mainland network** — it is the same problem
the update page has. Fetch these through a mirror (`core/DownloadSource.kt` lists `gh-proxy.com`,
`ghproxy.net`, `ghfast.top`) or over a VPN.

---

## 3. Adding a source

One line in `ProtonExtras.kt`:

```kotlin
Tool(id, displayName, dirPrefix, "owner/repo", Regex("…"))
```

* `id` — internal key; also what `FirstLocalInstall` and every `firstOrNull { it.id == id }` uses.
* `displayName` — the row's title, shown as is. Nothing localises it, so a new row costs **no** new
  string keys.
* `dirPrefix` — matched against the unpacked directory name by `installed()`, and against file names by
  the one-key install. The tree must contain `toolmanifest.vdf` at that top level.
* `owner/repo` — the releases API is read for downloads: `install()` picks the first asset matching
  `Regex`, sorted by name, and looks for a sibling checksum named `<asset>.substringBefore(".tar")` +
  `sha512`.
* the `Regex` also decides which file in a one-key-install folder is this row's build.

`installed()` compares `dirPrefix` with `startsWith`, so **the prefix has to survive what the tarball
calls itself**. GE unpacks to `GE-Proton11-9-aarch64` (the directory keeps the arch, `tar -tf | head -1`
says so); DroidDeck-Proton unpacks to `DroidDeck-Proton 11-9.1` — a **space**, not a hyphen.
`"DroidDeck-Proton"` covers both spellings; `"DroidDeck-Proton-11"` would not.

Which archive of a build the one-key install takes is decided by the **numbers** in the names, not by
the names: `FirstLocalInstall.found` keeps the newest per row (`ProtonExtras.compareVersions`), and a
build counts as already in only when the installed version is that one or newer
(`FirstLocalInstall.holdsBuild`). The three vendors spell the same build three ways — the archive is
`DroidDeck-Proton-11-9.1-aarch64.tar.gz`, its directory is `DroidDeck-Proton 11-9.1`, and GE's
directory repeats the arch — so `versionKey` (which drops `aarch64`/`arm64`) is what compares them:
`[11,9,1]`, `[11,9,1]` and `[11,9]` + `[11,9]`.

The guest script that does the unpacking, `usr/local/bin/droiddeck-proton-extra`, carries its own
`SOURCES` map with only `ge` and `cachyos`. That is the *manual* entry point
(`droiddeck-proton-extra <steam root> ge --tag …`); the app always hands it an absolute tarball path, so a
new row needs **no** edit there.

The space floor is 4 GB, in two places — `ProtonExtras.NEED_BYTES` and the guest script's — because the
tarball plus the tree it unpacks to both have to fit.

---

## 4. Traps

* **Local import used to refuse by file name.** `ProtonMenu.installProtonFromFile` returned early with
  `proton_local_wrong_build` when the name did not match the row's regex, which made any build without a
  release of its own impossible to install. Since 2026-10-12 the name is only used to *look up* a release
  to check against; when nothing vouches for the archive the existing "could not be checked … install it
  anyway?" dialog asks instead. `proton_local_wrong_build` is now unreferenced but kept — it is defined in
  all seven complete languages and deleting it would mean touching seven files for no gain.
* **GE's pattern also matches a DroidDeck-Proton file name**: `aarch64\.tar\.(gz|xz)$` fits
  `DroidDeck-Proton-11-9.1-aarch64.tar.gz`. What keeps the two apart is `dirPrefix`, which the one-key
  install tests together with the pattern.
* **A Proton installed on one row and looked for on another** — the archive unpacks to its own directory
  name, so dropping a DroidDeck-Proton tarball on the GE row installs a working tool Steam will list while
  the GE row still reads "not installed". That is the price of §4's first bullet; pick the matching row.
* **Never judge "is this a new build" by file size.** Two builds of this checkout have landed on exactly
  the same byte count before. Check the sha256, or grep a symbol that only the new source has.
* The Nightlies `nightly-*` release and the five `-Linux` tags are not interchangeable — see §2.1.
