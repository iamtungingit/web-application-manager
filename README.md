# Web Application Manager (WAM)

A native Android app that creates, imports, installs, runs, manages and distributes
web applications packaged as **`.wap`** files (WAP = *Web Application Package*).
WAM runs every `.wap` you add in its **built-in browser** — a hardened in-app viewer
(default) — or, optionally, in your device's real browser (Chrome = Chromium, or
Firefox) through its localhost preview server. No separate APK per web app.

- **Offline WAP** — a ZIP with `manifest.json` + HTML/CSS/JS/assets, runs without internet.
- **Online WAP** — just a manifest (plus optional icon) that opens an https URL under a
  user-controlled network policy.

## What's new in v1.6.0 — official JS API, cleaner widgets, smarter navigation

- **Official JS API (`window.WAP`).** Apps with the data bridge enabled can now call the
  device from JavaScript — every method is gated by the per-app permission toggles
  (Apps → long-press → App settings) and documented in the in-app **JS API reference**:
  - `WAP.camera.capture()` → photo via the system camera app, downscaled to ≤1280 px
    JPEG, saved into the app's data (offline apps also get a ready `/.wam/cam-….jpg`
    URL for `<img>` tags).
  - `WAP.mic.record({seconds})` → AAC `.m4a` voice recording (1–30 s) with the Android
    runtime prompt; `WAP.mic.stop()` ends early.
  - `WAP.net.send(url, {method, headers, body, base64})` → POST/PUT/PATCH to an https
    server — only when the **dangerous** "requests to send data" permission is ON.
  - `WAP.permissions.get()` → the app's current grants, so it can adapt its UI.
  - `WAP.data.readDataUrl(name)` → read saved binaries back as `data:` URLs.
- **Permission examples.** App settings now has an **Examples** row — one tap applies a
  realistic preset: *Camera app*, *Voice recorder*, *Story reader* (TTS), *Offline game*
  (nothing) and *Full access\** (asks extra confirmation because it includes send-data).
- **Widgets hold exactly 1 app space.** The label/subtitle stack under the widget icon
  ("that tall crap") is gone: a widget is now a single app icon, fixed 1×1
  (`resizeMode=none`). The configure screen dropped the label fields accordingly.
- **The last project stays open.** Switch Projects → Apps → Projects and the same
  project editor reopens (the pointer survives even process death); system back from
  the editor returns to the project list.
- **Useful docs only.** The seven `docs/*.md` spec files are removed, the "docs/" line
  at the end of Settings is gone, and the in-app Guide is now a compact, genuinely
  useful **JS API & help** reference (quick start + every API + permission examples +
  troubleshooting). Settings gained an "Official JS API" section with a reference button.
- **Icons are plain PNG drawables.** Every UI/file-type icon now ships as a real PNG in
  `res/drawable/` instead of XML vector files (device-side builders are happier, and
  the launcher foreground is a PNG too).
- **GitHub Actions workflow** (`.github/workflows/build.yml`) builds signed release APKs
  for **arm64-v8a, armeabi-v7a (arm32), x86 and x86_64** on every `v*` tag or manual run.
- **More file-manager controls:** sort the file list (name A→Z / Z→A / largest first)
  and **export any file to device storage** from its long-press menu.
- Version 1.6.0 (versionCode 8).

## What's new in v1.5.0 — per-app permissions, mount-to-folder, About + update check

- **Per-app settings (the big one).** Long-press any installed app → **App settings**
  gives each app its own permissions, all OFF by default:
  - **Camera** and **Voice recorder (microphone)** — enforced in the WebView permission
    prompt: the app can only get the device when this toggle is ON *and* you approve the
    Android runtime prompt (WAM declares CAMERA/RECORD_AUDIO and requests them on the fly).
  - **Text to speech** — new `WAP.tts.speak(text)` / `WAP.tts.stop()` bridge APIs
    (rate-limited, promise-based); denied apps get a clear `tts_disabled` error and a
    console line instead of silence.
  - **Internet** — the network policy (inherit / allow / ask / deny), same storage as
    the Info dialog.
  - **Requests to send data** — marked with a **red asterisk (*) as dangerous**: with it
    ON the app may POST/PUT your personal data to a server; OFF (default) the internet is
    read-only (GET). Enforced in request interception (POST/PUT/PATCH/DELETE → 403 +
    console log) and confirmed with a warning dialog before it can be enabled.
- **Built-in browser is the default preview engine.** Apps open inside WAM again
  (Settings → Runtime & preview lets you switch back to the device-browser hand-off —
  useful on devices whose WebView is broken; all the never-black hardening stays).
- **One Create button + Import + Mount in the file manager** (replaces New file /
  New folder):
  - **Create** opens a dialog with a file-name field (validated live while you type) and
    a type selector — HTML, CSS, JavaScript (each starts with the exact template code),
    Folder, or **Other**, which reveals an **Extension** field for `.php`, `.ts`,
    `.htaccess` and anything else.
  - **Import** copies single/multiple files or a whole folder from device storage (SAF)
    into the current folder, with automatic name de-duplication.
  - **Mount** (button below the file manager) mirrors the project into a SAF folder you
    pick: every WAM change (create/save/rename/delete/import) is pushed to the folder
    automatically, external edits made by other apps are pulled on open and via **Sync**,
    and **Unmount** stops mirroring. Dotfiles like `.htaccess` round-trip correctly.
- **More file-manager controls.** Up-navigation (parent folder — previously impossible),
  live search filter, Duplicate in the long-press menu, dotfiles visible, and
  **every file type has its own icon** (image / movie / audio / HTML / CSS / JS /
  JSON / XML / TXT / archives) with per-type tint colors in both light and dark themes.
- **About + update check.** New toolbar **About** entry and a full About card in
  Settings: "Web Application Manager - v1.5.0", "Author: iamtungingit (Made using
  GLM 5.3-Flash)", a clickable GitHub link, and **Check for update** — queries the
  GitHub releases API, compares versions properly (semver) and offers to open the
  release page when a newer release exists; friendly failure when offline.
- **Fixed icons & buttons.** The weird "new folder" glyph is replaced by a clean
  outlined folder + plus; the code-editor Close button is now a filled tonal circle.
- 12-section in-app Guide updated (Create/Import/Mount, App permissions, new engine
  default). Version 1.5.0 (versionCode 7).

## What's new in v1.4.0 — the WebView is gone from the default path

- **Projects now run in your own browser by default.** The in-app WebView stayed
  black on the target device even for `example.com`, through three rounds of
  hardening — so per the request to "use Chromium or Firefox instead of WebView",
  WAM now ships a tiny zero-dependency HTTP server (`LocalPreviewServer`) that
  serves the project from `http://127.0.0.1:<random port>/p/<token>/…` (loopback
  only, token-gated, path-contained) and opens it in Chrome (Chromium) or Firefox.
  A real browser cannot produce WAM's black screen. Offline apps stay offline:
  the deny/allow policy is enforced with `Content-Security-Policy` response headers.
- **Hand-off screen instead of a black screen.** WAM shows a plain-Views screen
  ("Running in your browser") with the local address, an **Open in browser**
  button, a one-launch **Open in built-in viewer** switch, and an explicit message
  if no browser is installed. It cannot render black — there is no WebView in it.
- **Preview engine setting.** Settings → Runtime & preview → *Preview engine*:
  Browser (default) or Built-in viewer (WebView). All the previous hardening
  (watchdog, first-paint check, software fallback, error screens) is retained for
  the built-in path.
- **Editor Save returns to the file manager.** Tapping Save writes the file and
  drops you back on the Files list with a "Saved" toast; background autosaves
  still never switch modes.
- **Developer console is a Settings option.** Previews no longer force the console
  overlay open; Settings → Developer → *Show console overlay* controls it, and it
  still opens automatically on any error screen.
- **Touch fix in the built-in viewer.** A statically inflated, never-loaded
  `<WebView>` sat invisibly on top of the real one in the runtime layout and could
  swallow touches — removed; the runtime now creates its WebView in code.
- 6 new unit tests cover the preview server (MIME/UTF-8, CSP deny/allow, token
  gate, 404, traversal block, 405). 82/82 tests pass.
- Version bumped to 1.4.0 (versionCode 6) so it installs cleanly over v1.3.0.

## What's new in v1.3.0 — the runtime can no longer fail silently

- **No more unexplained black screens.** The launch chain (manifest probe → resolver →
  extraction → WebView) used to swallow every exception into an invisible logcat line —
  any failure left the dark loading screen forever, which is exactly what "still black,
  even example.com" was. Every failure now becomes an explicit error screen with the
  exception type/message, the failing address, an **Open in browser** escape hatch and
  the developer console opened alongside.
- **Load watchdog.** If a page produces no `onPageFinished` within 15 s (server down,
  wrong URL, dead renderer), an error screen appears instead of an eternal spinner —
  while downloads that still make progress keep waiting.
- **Solid-black-frame auto-recovery.** A GPU/compositing bug on some budget devices
  (Redmi 14C included) makes the WebView paint a perfectly uniform black frame even for
  example.com. After the first paint WAM samples the frame: uniform near-black → the
  WebView is automatically recreated in **software-rendering compatibility mode** (with
  a toast + console note). If even that stays black, the error screen says exactly that
  it is a device WebView problem and what to update.
- **One-tap runtime diagnostics.** A terminal icon in the runtime screen opens the
  console anytime; before every error WAM logs a diagnostics line with the WebView
  package/version, hardware-acceleration state, screen size + density bucket and the
  network state. A missing WebView provider is detected and explained.
- **Live project-name validation.** Typing "my pro" now warns WHILE typing —
  "Spaces are not allowed — use dash (-) or underscore (_), e.g. my_pro" — instead of
  only after tapping Create. Create stays inert until the name is valid, and the
  auto-derived package id is previewed live ("my pro" → `my-pro`). New names allow
  letters, digits, dash and underscore only.
- **Code editor fixes.** Long lines now scroll horizontally instead of wrapping, so the
  line-number gutter can never misalign on wrapped rows; the gutter rebuilds only when
  the line count changes (typing no longer lags on big files); a stale-callback race
  that could show file A's content under file B's header after fast taps is fixed;
  undo/redo keep the cursor position and correctly mark the file dirty.
- **In-app Guide & help.** The toolbar menu now has "Guide & help" — ten sections of
  documentation inside the app (quick start, name rules, editing, packaging, widgets,
  per-app network, troubleshooting for the black screen / packaging / widget issues,
  storage locations). Plain Views only — it opens even when the WebView is broken.
- **Widget DPI is now visible.** The configure screen prints the detected density
  bucket (mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi, in-between buckets too) with the dpi value
  and scale factor, so the DPI-based sizing can be verified on the device.
- Version bumped to 1.3.0 (versionCode 5) so it installs cleanly over v1.2.1.

## What's new in v1.2.1

- **Runtime can no longer look black.** The WebView's base background was
  *transparent*, so the dark Material Night activity showed through wherever the
  page hadn't painted yet (first frame, unstyled HTML, a failed stylesheet) — the
  "I see black instead of the project" report. The base is now opaque **white**
  (what every browser paints before a page's own colors arrive); a manifest
  `backgroundColor` still wins.
- **Missing CSS/JS no longer silent.** A failed stylesheet or script used to
  render a wrong-looking page with no hint why. Subresource errors (404/403) are
  now logged to the Preview console with the exact URL.
- **"Force dark" setting actually works on Android 16.** The old
  `WebSettings.forceDark` API is a no-op on Android 13+; the renderer now uses
  the androidx.webkit algorithmic darkening API.
- **Widgets sized from the real DPI.** The launcher reports the widget cell in
  dp; the renderer now converts it to pixels with the device's true density
  (mdpi 1.0×, hdpi 1.5×, xhdpi 2.0×, xxhdpi 3.0×, xxxhdpi 4.0× and in-between
  buckets like the Redmi 14C's 280 dpi) and draws the background at the FULL cell
  size, so the widget fills its cell edge-to-edge like an adaptive icon on any
  density. The icon uses the 72/108 adaptive safe zone; debug builds log the
  detected bucket (`WAM.Widget` tag).
- **"Show label" toggle honored.** The widget configure screen saved the toggle
  but the renderer never read it back — labels now hide when it is off.
- **Hello/Blank templates are light & bright.** The old dark-green gradient could
  read as a "black screen" on a low-brightness LCD. New projects start on a light
  page with a green accent card. (Existing projects keep their own `style.css` —
  open it in the editor and restyle freely.)
- Version bumped to 1.2.1 (versionCode 4) so it installs cleanly over v1.2.0.

## What's new in v1.2.0

- **Action buttons never disappear — and they moved to the top.** The extended FAB that
  anchored itself to the bottom navigation (and stayed hidden after returning from the
  editor) is gone. Every tab now has a persistent action row right under the toolbar:
  *New project* + *Import project* on Projects, *Import WAP* on Apps, *New file* +
  *New folder* at the top of the editor's Files panel. All with icons (Material 3
  tonal/outlined styles).
- **Online apps/preview fixed for real** — the URL builder glued the entry URL onto its
  own origin (`https://example.comhttps://example.com`), so every *online*-type WAP
  failed to load and looked like a dead black screen. Full-URL entries now load as-is;
  path-only entry overrides resolve against the manifest origin.
- **Code editor: no more mysterious empty black panel.** Opening *Code* with no file
  open — or closing a file — used to leave a silent dead editor. It now shows an
  explicit empty state ("No file open — pick one in the Files tab") and closing a file
  returns you to the Files list. Line:column is now live while typing.
- Version bumped to 1.2.0 (versionCode 3) so it installs cleanly over v1.1.0.

## What's new in v1.1.0

- **Packaging fixed** — the old build failed on *every* project with
  `[ARCHIVE_UNSUPPORTED] zip: duplicate entry: manifest.json` (the manifest was zipped
  twice and the toast hid the message). The custom packager v2 pre-validates, writes
  atomically and reports typed errors in a full dialog. Round-trip covered by tests.
- **Widgets are now one adaptive launcher icon** — 1×1 cell, 108dp adaptive canvas
  scaled per density (mdpi…xxxhdpi), mask-shaped background, label only when room.
- **Online WAPs run & preview correctly** — bad sample URL fixed, online Preview
  supported, clearer error screen with the failing address.
- **No more Recents flooding** — one WAM entry; launching apps/previews reuses the
  runtime instead of creating a task per click.
- **Code tab upgraded** — custom syntax highlighting (HTML/CSS/JS/JSON), line-number
  gutter, file header with unsaved-dot + close, horizontal scrolling.
- **Material 3 Night polish** — dark surface palette, nav-bar contrast, theme choice
  applied from app start; content no longer hidden behind the bottom navigation, FAB
  anchored above it, icons across the UI.

## Quick start (Android Code Studio on your phone)

1. Copy the whole `WebApplicationManager` folder to your phone storage (or extract the
   provided `WebApplicationManager-source.zip`).
2. In **Android Code Studio**: *Open project* → select the `WebApplicationManager` folder.
   ACS requires AGP ≥ 7.2 — this project uses AGP 8.7.3, Gradle 8.9, JDK 17
   (ACS ships JDK 11 & 17; make sure the project JDK is set to **17**).
3. First build needs internet: Gradle downloads itself and the AndroidX/Material
   dependencies (several hundred MB — use Wi-Fi, be patient on the first build).
4. Build debug: **Build → assembleDebug** → install `app/build/outputs/apk/debug/app-debug.apk`
   (debug builds stay single-APK; the per-ABI split APKs are release-only).
5. Build release: **assembleRelease** →
   `app/build/outputs/apk/release/` now contains one APK per CPU —
   `app-arm64-v8a-release.apk` (Redmi 14C / most phones), `app-armeabi-v7a-release.apk`
   (arm32), `app-x86-release.apk`, `app-x86_64-release.apk` (emulators) and
   `app-universal-release.apk` (installs anywhere) — all already signed (see *Signing*).

### If your ACS build complains about the toolchain

The project pins versions in `gradle/libs.versions.toml` (AGP 8.7.3, Kotlin 1.9.24) and
`gradle/wrapper/gradle-wrapper.properties` (Gradle 8.9). Known-good fallbacks:

| ACS situation | Change |
|---|---|
| "requires Gradle X" / older bundled Gradle | AGP `8.2.2` + Gradle `8.2` + add `android.suppressUnsupportedCompileSdk=35` to `gradle.properties` |
| Only JDK 11 available | Not supported by AGP 8.x — switch the IDE JDK to 17 |
| No SDK platform 35 downloadable | `compileSdk = 34` and `targetSdk = 34` in `app/build.gradle.kts` + the suppress flag above |

All source code is plain Kotlin + XML Views — no Compose, no kapt, no annotation
processors, no coroutines — so on-device builds stay fast and memory-friendly
(`gradle.properties` already caps the heap at 2 GB and compiles Kotlin in-process).

## Quick start (Android Studio on a PC)

Open the project folder, let the IDE sync, then *Build → Generate signed APK* or run
`./gradlew assembleDebug`. `minSdk 26` / `targetSdk 35`, so it installs on Android 8.0+ —
verified toolchain: **Redmi 14C, HyperOS 3, Android 16 (API 36)**.

## Using WAM

- **Apps tab** — import a `.wap` (FAB), open apps, create home-screen widgets, export,
  share, per-app network policy, clear data, uninstall.
- **Projects tab** — the developer workspace: create projects from templates (Blank /
  Hello / Calculator demo / Online site), edit files & manifest in-app, **Preview**
  instantly, **Package** to a `.wap`, **Package & install**, or **Export** the `.wap`.
- **Settings** — network default policy, per-app overrides, text zoom, appearance,
  storage usage & cleanup, developer options, WAP compatibility matrix.
- Try the bundled samples in `samples/`: `calculator.wap`, `notes.wap`,
  `online-example.wap` (the `malicious/` fixtures must always be **rejected**).

## Signing

A project keystore ships at `keystore/wam-release.jks` so that on-device builds can
produce a signed release APK without extra tooling:

- alias `wam`, store/key password `wam-release-key`

For real distribution **regenerate your own keystore** and update the values in
`app/build.gradle.kts` (or remove the signingConfig):

```bash
keytool -genkeypair -v -keystore my-release.jks -alias myapp \
  -keyalg RSA -keysize 2048 -validity 10000
```

## Documentation

No separate spec files anymore — the documentation lives **inside the app** where it is
actually useful: toolbar menu → **JS API & help** (or Settings → Official JS API →
*JS API reference*). It covers the quick start, every `window.WAP` method with examples,
the permission presets and troubleshooting.

## License note

This project is your application code — no third-party code is embedded beyond the
declared Maven dependencies (AndroidX, Material Components, androidx.webkit).
