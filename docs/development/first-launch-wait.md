# The first-launch wait: what the loading screen can and cannot say

Measured on device 2026-10-09: after a local install, tapping **Play Steam** sat on a screen titled
"正在启动 Steam" for 7–8 minutes on one phone and 20+ on another. This note is the analysis of that
window: what the app actually knows, what it does not, and where the minutes can go.

## 1. The screen has four stages and the last one has no exit

`ui/SessionLoading.kt` paints a checklist of four rows for a Steam session
(`loadStages(steam = true)`):

| stage | label | detail while it runs |
|---|---|---|
| 0 | Linux 运行时 | 正在准备 Linux 运行时 |
| 1 | 会话 | 正在启动显示和音频 |
| 2 | Steam 客户端 | 正在检查 Steam 及其兼容性工具 |
| 3 | 正在打开 Steam | **正在等待 Steam 的第一帧** |

- **The title never changes.** `SessionActivity.loadingTitle()` returns
  `R.string.load_starting_steam` for the whole session, so "正在启动 Steam" is on screen from the
  first second to the last, whatever is really happening.
- The second line is *not* the stage's own text most of the time: `LoadingOverlay` prefers
  `step` whenever the line is `readable`, and `LoadingState.read()` marks the session script's
  install lines readable (`readableScriptStep`). Those lines are written by the guest script in
  **English** ("checking the Steam client (a first run fetches it - the loading screen shows that
  progress)").
- The stage only advances on a `== STEP` line in the session log, mapped by
  `LoadingState.topicOf` / `stageOf`. `usr/local/bin/droiddeck-session` prints `step "starting the
  Steam client"` immediately before it execs the client, and **nothing after that**. So stage 3 is
  terminal: from that moment until gamescope presents the client's first frame, the screen has
  literally nothing new to say — four full segments and "正在等待 Steam 的第一帧", for as long as it
  takes.
- The clock it keeps is thrown away: `LoadingState.tick()` builds `loadstate_elapsed`
  ("已用时 %1$d:%2$02d · 仍在处理") every second, and `LoadingOverlay` receives `elapsed` but never
  renders it. A user watching this screen has no elapsed time and no percentage unless one of the
  two log patterns below happens to match.

## 2. The only two things that can produce a percentage

At the time, `LoadingState.read()` had exactly two regexes besides the `== STEP` milestone (a third,
the install script's own `== PROGRESS`, came later — see 7.2):

- `downloading Steam: (\S+) \((\d+)/(\d+)\)` — the **session script's own** client download
  (`droiddeck-steam-install`), shown as "正在下载 Steam 客户端 · <component>".
- `Downloading update \((\d+) of (\d+) KB\)` — the **client's own** bootstrapper, shown as
  "正在下载 Steam 客户端更新 · N%". That string exists inside `steamrtarm64/steam`
  ("Downloading update (%s of %s KB)...").

If neither appears, the wait is neither of those two downloads, and the screen will sit on
"正在等待 Steam 的第一帧" for its whole duration. That is the state the two phones were in.

**A third progress display exists and is invisible.** Valve's bootstrapper draws its own update
window from a child process (`CUpdateUILinux`, `-child-update-ui`, "UpdateUI: showing logo" in the
binary's strings). The loading screen is a full-screen **opaque** Compose layer added above the
`SurfaceView` (`root.addView(sessionOverlay)` in `SessionActivity.onCreate`), so anything the guest
draws while it is up — including that progress window — is covered.

## 3. Where the minutes can go, in order

1. **The Steam client's own bootstrap** (suspected first). It decides whether the tree on disk is
   current and downloads what it is not, from `client-update.steamstatic.com` — the same
   mirror-less CDN `droiddeck-steam-install` uses. Its bookkeeping is
   `package/steam_client_<branch>_<arch>.installed` / `.manifest` plus the packages
   `package/<file>`, where `<file>` is the manifest's `file` field, i.e. `xxx.zip.<sha1>`.
   `droiddeck-steam-install` used to unpack those zips into the tree, **delete them, and write no
   `.installed`** — so the bootstrapper could conclude nothing was installed and fetch the components
   itself. 344 MB with no China mirror is 7 minutes on a good link and 20+ on a bad one.
   *Check*: `…/Steam/package/` growing `*.zip.<sha1>` files during the wait, and
   `logs/bootstrap_log.txt` afterwards.
2. **A client update that is genuinely due.** Our package set lags Valve's live manifest (on
   2026-10-09 they had replaced 5 of the 17 components), so a first launch updates even when the
   local install is otherwise perfect. Fixed for the next device by refreshing
   `FirstLocalInstall/steam-client` (done — see `steam-client-packages.txt`).
3. **The session script downloading the client** — only when the local install's stamp is not
   recognised (`[ -x …/steamrtarm64/steam ] && [ -f …/package/droiddeck-installed ]`). This one
   *does* show a percentage, so if the screen showed one early on, it was this.
4. **Smaller silent tails**: `ComponentsManager.ensureForcedPackages` runs *before* the guest starts
   and downloads from GitHub Nightlies with no UI at all (Adreno A6xx → `dxvk-2.7.1-linux.wcp`);
   the proot-side Python helpers (`steam-compatibility`, `droiddeck-steam-library`,
   `droiddeck-seed-redists`) take tens of seconds to a minute; the client compiles shaders before
   its first frame.
5. `steam://install/4427310` (Valve's ARM64 Proton) is asked for on the first session, but the
   local install's Proton seed writes `appmanifest_4427310.acf`, which suppresses it.

## 4. How to tell which one it was

Everything needed is in the **public** session folder
(`SessionPaths.beginOrCurrent`), which `Share logs` also sends:

```
/sdcard/Download/DroidDeck/2026-10-09-01-steam/
    session.log    proot, gamescope and the client's own stdout, with "== STEP <UTC time>" milestones
    steam/         the client's logs, copied out of the runtime when the session ended
    device.txt     the device and every setting the session ran with
```

- `grep "== STEP" session.log` — the gap between two stamps is where the time went.
- `grep -c "downloading Steam:" session.log` — the script downloaded the client itself.
- `steam/bootstrap_log.txt` — the bootstrapper's own account: "Downloading update (N of M KB)",
  "Download skipped: X version A, installed version B", "Preparing to update...", "Installing
  update...", "Extracting package...", "Update complete, launching...".

The client's own tree (`filesDir/<DIR>/root/.local/share/Steam`) is inside the app's private
storage and is not reachable from a file manager, which is why the copy above exists.

## 5. The bootstrapper's own switches

The bootstrapper reads `Steam.cfg` out of the client's own directory — `steamrtarm64/steam` resolves
it from `STEAM_CLIENT_CONFIG_FILE`, else `steam.cfg`, else `Steam.cfg` — and its own binary carries
the keys it understands. The table below is the version read out of the binary on 2026-10-10, not
the one community notes list (§7.3 has the addresses):

| key, as `steamrtarm64/steam` spells it | what it really does |
|---|---|
| `BootStrapperInhibitAll` | sets `+0x6f` — the one byte that stops the update, **and** the install |
| `BootStrapperInhibitUpdateOnLaunch` | `+0x60` = 0 — the startup line is not logged. It does not stop the check |
| `BootStrapperInhibitClientChecksum`, `BootStrapperInhibitBootstrapperChecksum` | `+0x6e` = 1 — the two checksum passes |
| `DisableDownloads` | **nothing — a dead key.** The string is there, no write instruction behind it |
| `ClientDownloadHost` | a host, or a full `https://…/client/` URL, to fetch from instead of Valve's |

The same switches also exist on its command line: `-inhibitbootstrap`, `-skipinitialbootstrap`,
`-nobootstrapperupdate`, `-noverifyfiles`, `-norepairfiles`, `-forcesteamupdate`,
`-forcepackagedownload`, `-overridepackageurl` — and those write different bytes again, which is
what misdirected the 2026-10-10 attempts (§7.3). A value is read as the literal string the binary
carries — `enable`, not `Enabled` or `1`.

The client keeps an update path of its own once it is running ("Background update loop checking for
update. . .", "Suppressing Steam update" in the same binary), so holding the launch-time update back
would not mean the device never updates again.

**Nothing in this project writes that file any more.** It was written by the "start without
updating" switch, which was removed on 2026-10-10 (§7.3).

## 6. What changed in the app (2026-10-09, option A)

Kotlin only — no guest script, no asset rebuild. Three things on the loading screen:

1. **The title names the stage.** `SessionActivity.loadingTitles()` returns one headline per
   checklist stage (`load_title_runtime`, `load_title_session`, `load_title_steam`,
   `load_title_opening_steam`) and `LoadingOverlay` picks it by the stage it has reached, instead
   of holding `load_starting_steam` from the first second to the last. The desktop and program
   sessions keep one title across their three stages.
2. **The clock is drawn.** `LoadingState.elapsed` — built every second since long before this
   change and never rendered — is now a line under the segments (`loadstate_elapsed`,
   "已用时 0:45 · 仍在处理"). A wait that is still working now says so.
3. **The last stage explains itself.** `LoadingState` grew `stageSeconds`, a per-stage clock reset
   in `restage()`. After 45 s in the terminal stage with nothing better to show, the detail
   switches from "正在等待 Steam 的第一帧" to `load_opening_steam_slow`.

**And the client now reports what it is doing.** `LoadingState.clientLog` points at
`SteamClient.root(context)/logs/bootstrap_log.txt`. The guest writes that file *inside the app's
private storage*, so the app can read it live, and it is the only place the client's own
update/unpack/verify work announces itself. Once the script has said "starting the Steam client"
— the stage with no exit — `clientLine()` tails it and lifts out, in the bootstrapper's own words:

| its line | the screen shows |
|---|---|
| `Downloading update (N of M KB)...` | `loadstate_steam_update` with a live percentage |
| `Downloading manifest`, `Downloaded new manifest` | `load_client_download` |
| `Verifying installation...`, `Verifying all file checksums` | `load_client_verify` |
| `Preparing to update...`, `Installing update...`, `Extracting package...`, `Creating folder`, `Update complete, launching...` | `load_client_install` |
| `Download failed`, `Steam needs to be online to update`, `Failed to apply update`, `Failed to load manifest`, `Corrupt package file` | `load_client_failed` |

The details that make it safe:

- Every bootstrapper line is prefixed `[2026-10-09 21:14:02] `, so `clientMessage()` strips the
  clock before matching, and `startsWith` is never applied to the raw line.
- The match is the **last** hit in the tail, i.e. the current phase, not any phase.
- The log **outlives the session** — nothing rotates it when the client exits — so a file whose
  `lastModified()` is older than the moment this session's loading state was built is not ours at
  all: without that, a stale "Update complete, launching..." from a previous launch would be shown
  as this one's progress. Written since, it is trusted however long it has been quiet since, because
  the phases it names run for minutes on one line.
- It is consulted once the session has reached "starting the Steam client" — a fact held from the
  first time that milestone is read rather than re-derived from the log's tail (7.4), because the
  script writes more lines under a running client and those would otherwise take the screen back
  off it.
- The failure line is the one worth having: `Steam needs to be online to update` is exactly what a
  blocked `client-update.fastly.steamstatic.com` looks like, and until now it was invisible — the
  screen said "正在等待 Steam 的第一帧" while the client had already given up.

Nine new keys (`load_title_*` ×4, `load_client_*` ×4, `load_opening_steam_slow`), all present in
the seven locales this fork keeps complete; `values-fr` / `values-ru` stay byte-identical to
upstream as always.

Not addressed here: the bootstrapper's own update window is still covered by the opaque overlay,
and nothing above shortens the wait. §7 is what was tried for the wait itself.

## 7. Option B on the device (2026-10-10)

Three things came out of the device work. Two are in use — §7.1 keeps a first start from
downloading what the device already has, and §7.2 puts a moving percentage on the screen while it
does download. The third, a "start without updating Steam" switch, **was removed again the same
day**; §7.3 is the record of what it did, why it never worked, and what not to try again.

### 7.1 The packages are left where the bootstrapper looks for them

`droiddeck-steam-install` used to unpack each component and `rm` the zip. The bootstrapper, given a
`.installed` it does not have, checks `package/<file>` — the manifest's `file` field, i.e.
`xxx.zip.<40-hex sha1>` — before it downloads anything, and with the packages in place it goes
straight to `Extracting package...`/`Applying update...` at no network cost. So each verified
component is now `mv`ed into `$steam_root/package/` instead of deleted, and nothing else about the
install changed.

The manifest the install follows is now **Valve's live one** whenever it is reachable and newer than
the folder's; the folder's own manifest is what an offline device uses and the fallback otherwise.
A folder filled last week therefore supplies every component whose name has not changed and Valve's
manifest supplies the ones that have — previously the folder's manifest was used whenever it existed,
so a stale folder installed last week's build and the client re-fetched the same components from the
same CDN a minute later. Both manifests name a sha256 per component and every file is checked
against it wherever it came from; a local copy that fails is re-fetched rather than fatal.

One component, from whichever edge will serve it: `bl_fetch()` walks `client-update.fastly…` then
`client-update.akamai…` (`BL_STEAM_CDN` pins one). Moving to the second edge is announced as a
`== STEP` line, because on screen a retry reads as the wait making progress where two more minutes
of silence reads as a hang.

### 7.2 A percentage that moves (2026-10-10)

Two separate downloads can be on screen and each needed its own number:

* `droiddeck-steam-install` writes `== PROGRESS <n>.<d>` while a component is coming down. The
  curl it used to run in the foreground is now backgrounded and its destination file measured once
  a second, so a 105 MB component moves the number instead of only changing it when it lands; the
  total is summed from the manifest up front, and the whole thing is per tenth of a percent.
* The bootstrapper's own `Downloading update (N of M KB)` was already read; it goes to tenths now.
* `loadstate_steam_update` takes a string (`%1$s`) so the screen can say `42.3%` rather than `42%` –
  on a 344 MB install a whole percent is three and a half megabytes of nothing moving.

The loading line still prefers the client's log over the script's when the client has something to
say. **The bootstrapper's line is not the English one** it was read as until 7.5 — this bullet was
wrong when it was written.

### 7.3 The switch that was removed: "start without updating Steam" (2026-10-10)

A switch on the play page (and the same row in Steam → Client) that put `BL_STEAM_NO_UPDATE=1` into
the session, made `droiddeck-steam-install` follow the local folder's manifest instead of Valve's,
and wrote `Steam.cfg` beside the client to hold the bootstrapper back. It was added 2026-10-10,
reported broken three times, and **removed again the same day on the user's call** — "the button
does nothing, it always downloads the newest Steam client, so the button and the feature are not
needed; delete the code and keep only the download-the-client flow". Everything it touched is gone:
`SessionPrefs.steamNoUpdate`, both toggles, `BL_STEAM_NO_UPDATE`, the `Steam.cfg` block and the
bounded wait in `droiddeck-session`, the `mode_no_update*` / `loadstate_no_update` keys, and its
tests. What remains is the one flow that works: the session downloads the client, and the screen
shows it happening (7.1, 7.2).

**What went wrong, in the order it was found.**

| round | mechanism | why it did nothing |
|---|---|---|
| 1 | `BootStrapperInhibitUpdateOnLaunch` + the two checksum keys | They write `+0x60` / `+0x6e`: checksum and one log line. The download is gated by a different byte |
| 2 | `BootStrapperInhibitAll`, withheld until `package/<channel>.installed` existed | It *is* the key that sets `+0x6f` — but `+0x6f` also gates the **install** (`0xb0078`, the branch whose apply pass writes the `.installed` the withholding was waiting for). With no record the client never reached a window, and with the record the device still downloaded |
| 3 | `DisableDownloads` | **A dead key.** Its string is in the binary (`0x73bb30`) and the config parser's `strstr` finds it (`0xb36fc`), but no instruction after that writes a byte. It is not a `Steam.cfg` key at all |
| 4 | command line `-inhibitbootstrap` | The argument parser (`0xb4568`) writes `+0x70`, which is the *background update loop* branch ("Background update loop checking for update. . .") — not inhibition. The download moved off the blocking path, which is exactly the "still downloading about 20 s later" that was reported |

The byte that matters, read out of `steamrtarm64/steam` (config parser `0xb3388`, argument parser
`0xb4568`, updater entry point `0xbf920`):

* `0xbf920` does `ldrb w1,[x0,#0x6f]; cbnz w1, 0xbf974` — which logs `Suppressing Steam update`
  (`0x73d5a8`) and returns. **`+0x6f` is the update switch.**
* Only one config key sets it: `BootStrapperInhibitAll` (`strh 0x0101` at `[x20+0x6e]`, writing
  `+0x6e` **and** `+0x6f`). The three narrow keys write `+0x60` / `+0x6e` only.
* The install branch tests the same byte (`0xb0078`), which is why the "wait for `.installed`
  first" dance existed at all.
* `Steam.cfg` is resolved from `STEAM_CLIENT_CONFIG_FILE`, else `dirname(dirname(/proc/self/exe))`
  — i.e. `$steam_root` — trying `steam.cfg` then `Steam.cfg` (`0xb33a0` / `0xb3748` / `0xb37ac`).

A working version therefore needs **two starts**: the first writes only the narrow keys, so the
bootstrapper applies the packages already in `package/` and writes its own `.installed`; the second
adds `BootStrapperInhibitAll`. **None of this was ever confirmed on a device** — the last build
handed over was still reported as downloading at about 20 s, and the user called the feature off
rather than run a fourth round. Whether this binary reads `Steam.cfg` at all is therefore still
open.

**What was kept from the same investigation, because it is not about the switch:**

* The bootstrapper's own bookkeeping. It recognises an install by
  `package/steam_client_<branch>_<arch>.manifest` and `.installed`, and our install wrote neither —
  so it re-fetched Valve's manifest and re-verified on every launch. `droiddeck-steam-install` now
  writes the `.manifest` it actually followed, and backfills it from the local folder on the
  already-installed path. The `.installed` is the bootstrapper's own to write, which it does once a
  `.manifest` sits beside the packages.
* `LoadingState.clientLine()` and the phrases it recognises (7.2) — still what the screen reads
  while the client is coming down.
* The packages are left in `package/` (7.1) and the percentage moves (7.2).

**Do not re-add it as it was.** If the wait ever needs shortening again, start from the byte table
above: the only thing that stops the update is `+0x6f` via `BootStrapperInhibitAll`, and it must not
be set before `package/<channel>.installed` exists.

### 7.4 The line that stopped moving (2026-10-10)

The user's report: after the local one-key install, Play Steam downloads or installs the client
update and **the percentage does not appear by itself** — but leave the page, press Play again, and
there it is, "33.1%".

The percentage was there the whole time and the screen was not reading it. `LoadingState.read()`
looked **only at the tail of the session log** and derived *everything* from what it found there,
including which milestone the session had reached:

* `== STEP …` — the script's milestone. Its last one is `starting the Steam client`, which is the
  gate for consulting the client's own `bootstrap_log.txt` (the only place the client's update
  reports itself).
* `== PROGRESS n.d` — the script's byte count, while *it* downloads.

The client's own launch writes megabytes to that same log — CEF, ANGLE, the compositor, proot — all
of it after that last milestone. Once more than `TAIL_BYTES` had been written, the window held no
milestone at all: `stepText` was null, so the client's log was never consulted (the gate is "the
last step is `starting the Steam client`"), `progressAt ≥ 0` was false, and `read()` returned
**null**. `update()` returns early on null, so the line and the bar froze on whatever they had said
last — while `tick()` kept the clock moving, which is why the screen looked alive and stuck at the
same time. Starting a fresh session was the only thing that reset the window, which is why the
percentage appeared after leaving the page and coming back.

Fixed, in `LoadingState` only:

* The milestone, the two progress values and the component line are now **held on the instance with
  absolute file offsets that only move forward**. A window that has scrolled past a marker keeps
  using it, so the tail no longer has to contain the milestone for the screen to know about it.
* `clientStarted` is latched the first time the session reaches `starting the Steam client`, and the
  client's log is consulted from then on — a later script line ("signed in: asking the client again
  for the ARM64 Proton", which the background sub-shell writes under a running client) no longer
  takes the screen off it.
* The component-counter line (`downloading Steam: <block> (i/17, 105 MB)`) now carries the
  percentage too, in front of the name: it is the one phase with no number of its own, and one
  component is 105 MB — the count only moves when one lands.
* A tail that names nothing keeps the last line instead of clearing it, and `TAIL_BYTES` is
  128 KB rather than 48 KB.

### 7.5 The client's log is not in English (2026-10-10)

The user's report, after 7.4 was on the device: the first Play still shows **"正在下载 STEAM 客户端
更新" with no number** for the whole client update. Pressing B and playing again does show one. So
7.4 was necessary but not sufficient — the line was now *reached* and still produced no number.

`LoadingState.clientLine()` recognised the bootstrapper by English prefixes, and the number with
`Regex("""Downloading update \((\d+) of (\d+) KB\)""")`. Both are wrong, and the client's own files
say so. Every line the bootstrapper prints for the user is a `SteamBootstrapper_*` **translated
string** — the translations ship with the client, as `public/steambootstrapper_<language>.txt`
inside the `strings_all` component:

| language | `SteamBootstrapper_UpdateDownloading` |
|---|---|
| english | `Downloading update (%bytes% of %size% KB)...` |
| schinese | `正在下载更新 (已下载 %bytes%，共 %size% KB)...` |
| tchinese | `正在下載更新（已下載 %bytes% KB，共 %size% KB）…` |
| japanese | `更新をダウンロード中（%bytes% / %size% KB）...` |
| german | `Update wird heruntergeladen (%bytes% von %size% KB) …` |
| french | `Téléchargement : (%bytes% sur %size% Ko)` |
| russian | `Загрузка обновления (%bytes% из %size% КБ)...` |
| brazilian | `Baixando atualização (%bytes% de %size% KB)...` |

Two separate reasons the number never appeared:

1. **The words are translated.** A client in Chinese writes `正在下载更新 (已下载 35,320，共 67,349
   KB)...`, which no English prefix matches. The only line the app ever *did* recognise was the
   client's debug line `Downloaded new manifest` — one of the few it never translates — so the
   screen settled on "downloading the client update" and stayed there.
2. **The numbers are not plain ASCII.** `%bytes%` and `%size%` are `%s`, formatted by the client as
   `218,353` — thousands separators — and the unit is not always KB (`Ko`, `КБ`). `(\d+)` cannot
   match either half of `(218,353 of 247,576 KB)`, so even the English line fell through to the
   label-only branch.

Both real log lines, as they appear in the wild:

```
[2023-10-20 20:32:14] Baixando atualização (218,353 de 247,576 KB)...          (client in Portuguese)
[2024-01-18 19:43:23] 正在下载更新 (已下载 35,320，共 67,349 KB)...              (client in Chinese)
```

**The fix** (`LoadingState` only — no guest script, no new string key): the bootstrapper is no
longer recognised by its words but by the *shape* of its own templates, read off the device.

* `bootstrapperPhrases()` reads `public/steambootstrapper_*.txt` out of the client tree — all
  thirty of them, because which language the client runs in is not something the app can know — and
  takes the values of the seventeen `SteamBootstrapper_*` keys the screen has something to say
  about (checking, downloading, preparing, extracting, installing, cleaning up, verifying, complete,
  and the failures). A client that has not unpacked them falls back to the English list.
* A template becomes a `Phrase`: its literal pieces, in order, with a number expected between each
  pair — `["Downloading update (", " of ", " KB)"]`, `["正在下载更新 (已下载 ", "，共 ", " KB)"]`,
  `["Téléchargement : (", " sur ", " Ko)"]`. Matching finds the pieces in order and reads what falls
  between them; nothing about the words or the unit is assumed.
* `Phrase.number()` strips the separators and needs the rest to be digits, so `218,353`, `35 320`
  and `67.349` are all numbers.
* Lines that carry a number win over the same line without one (`Downloading update (1 of 2 KB)`
  also reads as `Downloading update`), and a failure beats both — `priority()`.
* The phrases are indexed by their first character, so the twice-a-second read only tries the few
  that can match.

Verified offline against the client's own files rather than on a device:
`.upgrade-work/check_bootstrapper.py` mirrors the Kotlin and runs 43 lines — English, Chinese and
Portuguese logs as found in the wild, plus the client's untranslated debug lines and our own
script's `== STEP` / `== PROGRESS`, which must **not** be mistaken for the client's. 43/43.
`(218,353 of 247,576 KB)` reads as **88.1%** and `(已下载 35,320，共 67,349 KB)` as **52.4%** — the
same two numbers the client's own window shows.

⚠️ The English file spells its keys unquoted (`SteamBootstrapper_UpdatingSteam   "Updating
%appname%..."`) and the translated ones quoted (`"SteamBootstrapper_UpdateInstalling"  "正在安装
更新..."`). A reader that assumes one of the two silently returns the whitespace between them as the
value — which is how the first version of this read 16 phrases out of 31 files instead of 527. The
quote after the key is taken only when it is there (`"?`), and the lookahead is what keeps
`…UpdateDownload` from matching `…UpdateDownloading`.

