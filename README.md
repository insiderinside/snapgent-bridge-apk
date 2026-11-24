# Snapgent Bridge APK

Android-native (Java 17) on-device bridge. It runs a loopback-only WebSocket server inside the phone and speaks both the MCP JSON-RPC protocol and the SnapgentV3 extension's native WebSocket protocol, so the Snapgent extension running in an Android browser can read files, edit a workspace, and drive Git on the device — without the desktop Python bridge.

Current build: **0.1.9** (`dev.snapgent.bridge`, minSdk 26, targetSdk 34, versionCode 11).

## Download the release APK

- Latest release: [Releases v0.1.9](https://github.com/insiderinside/snapgent-bridge-apk/releases/tag/v0.1.9) — asset `snapgent-bridge-0.1.9.apk`.
- Install over ADB: `adb install -r snapgent-bridge-0.1.9.apk`, or open the asset link on the phone.
- Upgrading from the debug-signed `v0.1.0` needs `adb uninstall dev.snapgent.bridge` first; every release since `v0.1.1` upgrades in place because they all use the release key.

`v0.1.1` onwards are signed with a dedicated **4096-bit RSA release keystore** (APK Signature Scheme v2):

```text
Signer DN:      CN=Snapgent Bridge, OU=Release, O=insiderinside, L=Jakarta, ST=Jakarta, C=ID
SHA-256 cert:   CA:67:7C:77:F2:F2:75:2A:1B:1E:7F:AE:6F:1D:B2:06:AA:D0:94:EF:74:9E:96:99:7F:2D:9A:DC:7B:3E:94:FB
Valid until:    2056-11-16
```

The keystore itself is **not in this repository**. `app/build.gradle` loads it from `~/.android/snapgent-release/keystore.properties` (or the path in `SNAPGENT_KEYSTORE_PROPERTIES`) and falls back to the debug signing config with a warning when that file is absent, so a plain clone still builds. Keep the keystore backed up: Android only accepts updates signed by the same key, so losing it means users must uninstall before installing a future build.

The earlier `v0.1.0` asset was signed with the Android debug keystore and is kept only for history.

Since `v0.1.2` the version every endpoint reports is read from the installed package instead of a literal: `device_info.bridgeVersion`, the MCP `serverInfo.version`, the About screen, and the extension `connected` frame (`bridge_version: android-<versionName>`) all match `build.gradle`.

## Browser companion

Snapgent runs as a browser extension, so it needs a browser that supports extensions.

**Titanium Browser for Android** — Chromium-based, open source, built on Vanadium (GrapheneOS), and formerly named Helium Browser for Android. Chrome for Android does not support extensions, which is why Titanium is the Android option here.

- Source: [jqssun/android-titanium-browser](https://github.com/jqssun/android-titanium-browser)
- Install: [Play Store](https://play.google.com/store/apps/details?id=io.github.jqssun.helium) or the APK builds in that repo's Releases.
- Install the extension one of three ways: from Chrome Web Store / Opera Add-ons / Edge Add-ons; as a local CRX/ZIP through the built-in *Titanium Extension for Android* ([jqssun/android-titanium-extension](https://github.com/jqssun/android-titanium-extension)); or by loading it unpacked for testing — open `chrome://extensions`, enable **Developer mode**, choose **Load unpacked**, and pick the `snapgent-extension` folder through the SAF picker.
- To use it in Incognito: Manage extensions → Details → **Allow in Incognito**.

The extension itself lives in the [snapgent-connector](https://github.com/insiderinside/snapgent-connector) repository. Once it is installed in Titanium, point it at this APK bridge on `127.0.0.1` (loopback WebSocket, see below). On desktop, Chrome with the same extension and the desktop Python bridge is the equivalent pair.

## Endpoints

Both listeners bind to loopback only and require the same bearer token (a 32-byte Base64URL secret generated on first launch and persisted in app preferences). The token is never displayed as text: Settings offers **Copy MCP connection URL**, which carries it, plus **Regenerate access token**, which invalidates the old URL.

| Purpose | URL |
| --- | --- |
| MCP JSON-RPC 2.0 (`initialize`, `ping`, `tools/list`, `tools/call`) | `ws://127.0.0.1:8765/ws?token=<token>` |
| SnapgentV3 native protocol (`connected`, `ping`, `list_tools`, `call_tool`, `restart_mcp`) | `ws://127.0.0.1:17613/ws?token=<token>` |

The MCP port is configurable in the app (1024–65535, excluding the native port). The bridge runs as a `dataSync` foreground service with a low-importance notification.

## Tools (65)

**Device and files (6):** `device_info`, `list_files`, `read_file`, `write_file`, `write_file_base64`, `read_file_base64` — app-private storage only. Text stays capped at 512 KiB; the base64 pair moves binary assets (voice-overs, renders) in 4 MiB chunks with `append=true` for larger files.

**Device control, adb style (12):** `device_status`, `screen_dump`, `screen_screenshot`, `screen_tap`, `screen_tap_text`, `screen_swipe`, `screen_long_press`, `screen_key`, `screen_text`, `screen_wait`, `app_list`, `app_launch` — the parts of `adb shell input`, `uiautomator dump`, `screencap` and `am start` an agent normally needs, without USB and without root. They run through an accessibility service (the `DeviceAutomationService` in `Settings > Accessibility`): `screen_dump` returns every visible node with its text, view id and tap coordinates, `screen_tap_text` clicks by label with a coordinate fallback, `screen_screenshot` saves a PNG into the workspace (`screenshots/`, Android 11+), and `screen_wait` blocks until an element appears. With the service off, these tools answer with the exact steps to enable it instead of failing silently.

**Workspace, VS Code style (18):** `get_workspace_info`, `get_project_info`, `list_dir`, `list_tree`, `find_files`, `search_files`, `read_files`, `read_file_lines`, `get_file_info`, `get_file_outline`, `create_file`, `edit_file`, `create_directory`, `move_file`, `copy_file`, `delete_file`, `apply_patch`, `run_command` (allowlisted utilities only, no shell, 30 s ceiling).

**Git connector (19):** `git_init`, `git_status`, `git_diff`, `git_log`, `git_branches`, `git_add`, `git_commit`, `git_clone`, `git_remotes`, `git_remote_add`, `git_remote_remove`, `git_fetch`, `git_pull`, `git_push`, `git_checkout`, `git_branch_create`, `git_stash`, `git_stash_list`, `git_stash_apply` — backed by JGit 6.10 and confined to the app-private workspace, so the extension can review and commit changes on the phone itself.

**Embedded Python (2):** `run_python` (execute a snippet or a script file in the bundled CPython 3.8 runtime) and `python_info` (version, platform, bundled packages) — Chaquopy, prebuilt, so no NDK and no system Python are required on the phone. It ships the standard library plus `requests` 2.32.4 and `Pillow` 9.2.0 (with the `_imaging`/freetype/libjpeg native parts), packed at build time; there is no runtime `pip install`.

**Render (4):** `render_still` (one HTML/Canvas/Three.js frame from an off-screen WebView, saved as PNG), `render_video` (H.264 MP4 through `MediaCodec`/`MediaMuxer`, calling `window.__snapgentSeek(ms)` per frame when the page defines it), `encode_frames` (PNG sequence to MP4) and `render_info` (WebView version plus the available H.264/AAC encoders). An optional 16-bit PCM WAV soundtrack is muxed as AAC, so no Playwright, FFmpeg or Termux is needed on the phone. `render_still` and `render_video` take `render_mode: gpu|software` (default `gpu`): the GPU path keeps the WebView hardware accelerated on an off-screen overlay and reads the frame back from a RenderNode through `HardwareRenderer` into an `ImageReader`, falling back to a `VirtualDisplay` and only then to the legacy software layer. That default matters because a software layer cannot contain WebGL at all — on a 640x360 raw WebGL shader the GPU path returns 146,814 unique colours against 1 for the software path.

**Tool registry (4):** `tool_create`, `tool_delete`, `tool_list`, `tool_set_enabled` — the client can define its own MCP tools on the phone. A `python` definition runs inside the embedded interpreter with its arguments pre-parsed into the dict `ARGS`; an `http` definition sends a request with `{{arg}}` substitution in the URL, headers and body. Both appear in `tools/list` immediately, so a Mac-side agent can extend the bridge without rebuilding the APK.

A definition is one JSON file under `custom-tools/` in the workspace, so custom tools belong to whoever wrote them: an app update, a settings reset or one unreadable file cannot take the rest with it. `tool_list` reports `custom_tools_dir` and `custom_tools_files`, and the folder shows up in the Files page, which is also how a definition gets backed up or restored — dropping `<name>.json` in by hand makes it a working tool. Definitions written by builds before 0.1.9 are moved out of the preferences file automatically on first start.

**Optional add-ons (`addons/`):** nothing here is built into the APK or enabled by default — skipping an add-on means not running its scripts. The local ASR add-on (`asr_transcribe`, `asr_job`, `asr_health`) transcribes a WAV on the phone with the real CTranslate2/faster-whisper engine and returns word-level timestamps, which Android's own recogniser does not provide. Install, proof and how to remove it again: [addons/README.md](addons/README.md); engine contract: [docs/ct2-android-asr.md](docs/ct2-android-asr.md).

## App screens

- **Home** — transport switch (native 17613 / MCP 8765), start/stop, live status, and quick actions into the other pages.
- **Tools** — every built-in tool as a card with its own on/off switch (65 of 65 by default), plus the custom-tool list. A custom tool is defined on the phone: `python` (source or script path, run by the embedded interpreter) or `http` (method, URL, headers, body with `{{arg}}` substitution). The four `tool_*` management tools are always on, so the registry can never lock itself out.
- **Files** — browses the app-private workspace with its path and free space, imports a file from device storage through the system file picker (SAF), creates folders, and deletes entries — the way a voice-over or a render reaches `write_file_base64`. Folders open in place (breadcrumb at the top, back arrow steps out one level), and tapping a file opens an in-app preview: text/code/log files render selectable, images are decoded downsampled to fit, and anything else shows its MIME type plus a hex dump of the first KiB. *Open with* and *Share* hand the file to another app through the read-only `dev.snapgent.bridge.files` provider (no `androidx` dependency, not exported, every URI granted per Intent).
- **Settings** — native/MCP ports, bearer token (show, copy, regenerate), appearance, auto-start, a **Device control** card that shows whether the accessibility service is on, opens the system Accessibility screen, reports whether background app launching is allowed and offers the **Display over other apps** permission (`app_launch` needs it: Android blocks activity starts from the background, and a visible 1×1 overlay is the exemption an ordinary app can grant itself), and the **GitHub** card: paste a Personal Access Token, tap *Validate and save*, and the username comes back from the API. The token is encrypted with the Android Keystore (AES-GCM) and never written in plaintext; saving an empty field keeps the existing token.
- **Activity Logs** — bridge events filterable by ALL/INFO/WARN/ERROR, with a clear action.

## Security model

- Listeners are loopback-only; every connection carries the token in the URL.
- No root required, and the bridge never reads outside its own app-private directory: `..`/absolute paths and symlink escapes are rejected.
- File reads/writes are capped at 512 KiB; `delete_file` refuses directories; patch application is all-or-nothing.
- `run_command` runs a fixed allowlist of utilities (`pwd`, `ls`, `cat`, `head`, `tail`, `wc`, `grep`, `stat`, `sha256sum`, `date`, `uname`, `df`) with per-utility argument checks, at most 32 arguments, and no shell.
- `run_python` executes code with the app's own UID inside the same app-private boundary: `cwd` cannot escape it, output is capped at 256 KiB, the soft timeout is 15 s, and dependencies are resolved at build time (no runtime `pip`). Only packages that ship an arm64 wheel can be added; an unavailable one fails the build instead of the tool call.

## Lifecycle: what keeps the bridge reachable

Two different mechanisms keep remote control working while the app is not on screen:

| Part | Mechanism | Dies when |
|---|---|---|
| The WebSocket server (`8765` / `17613`) | `BridgeService` is a **foreground service** (`foregroundServiceType="dataSync"`, notification `Snapgent local bridge`) and returns `START_STICKY`, so the system recreates it with a null intent after an eviction | the user taps Stop, or the OEM battery manager kills the process and never restarts it |
| Device control (the 12 `device_*` / `screen_*` tools) | `DeviceAutomationService` is an **accessibility service**, bound by the system, same process, no notification | the service is switched off or accessibility is revoked |
| `app_launch` bringing another app forward | `LaunchOverlay`, a visible 1×1 overlay that exempts the start from the background-activity-launch rule | the *Display over other apps* permission is revoked (Android resets it on every reinstall) |

With **Start when app opens** enabled, the accessibility service also calls `BridgeService.startIfEnabled()` when it rebinds, so a process the system killed comes back with its socket instead of staying a device-control shell the extension cannot reach. Android 12+ may still refuse to start a foreground service from the background; the bridge then records `Auto-start refused: …` and the app's Start button is the way back.

Practical limits: taps land on whatever is in front (with a browser in front you are driving the browser, so call `app_launch` first); the accessibility dump exposes native UI reliably but Chromium page content only as the URL bar plus a loading label; the screen must be on and unlocked. On MIUI, also allow autostart and remove the bridge from battery restrictions, otherwise the process may not come back at all.

## Compatibility

The extension wire protocol was taken from the SnapgentV3 extension: the native endpoint greets a client with a `connected` frame (reporting `bridge_version: android-<installed version>` plus the full tool catalog), answers `ping`/`pong`, serves `list_tools`, executes `call_tool`, and accepts `restart_mcp`. `stream` and `progress` frames are accepted as fire-and-forget telemetry.

Telemetry-only caveat: the bridge exposes no desktop `studio` runtime, so `studio_status` intentionally returns nulls.

## Build

Requires JDK 17, Android SDK Platform 35 with Build Tools, and Gradle 8.7 (Android Gradle Plugin 8.6.1), plus a host `python3` for Chaquopy's build-time pip (3.9.6 was used here; no virtualenv and no NDK needed).

```bash
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

`assembleRelease` signs with the release keystore described above when `keystore.properties` is present. The file uses four keys: `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. To build with your own key, point `SNAPGENT_KEYSTORE_PROPERTIES` at your properties file. Verify any built APK with `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`.

The build is `arm64-v8a` only (`abiFilters`), because Chaquopy ships one CPython per ABI and that single slice is what makes the APK 17 MB instead of far more. The first build downloads Chaquopy's CPython and the `requests`/`Pillow` wheels; later builds are cached under `app/build`. Adding a package without an arm64 wheel fails the build, not the device.

## Tests

End-to-end tests drive the real device over ADB (`adb -s <serial> forward tcp:1876x tcp:876x`):

```bash
python3 scripts/test_mcp_ws.py          # MCP JSON-RPC over the 8765 endpoint
python3 scripts/test_mcp_device.py      # same MCP coverage, run on-device (release APK)
python3 scripts/test_snapgent_ws.py     # SnapgentV3 native protocol on 17613
python3 scripts/test_workspace_tools.py # workspace + tool catalog (expects 65 tools)
python3 scripts/test_tool_registry.py   # custom python/http tools, switches, catalog count
python3 scripts/test_python_packages.py # run_python: stdlib, requests, Pillow, output cap
python3 scripts/test_render_tools.py    # render_still / encode_frames / render_info
python3 scripts/test_render_gpu.py      # gpu vs software colour comparison on a real device
python3 scripts/test_custom_tool_store.py  # custom tools are files and survive a restart
python3 scripts/test_device_tools.py    # device control: dump, screenshot, taps, keys, app_launch
```

Every script drives the running bridge over ADB and fails with a non-zero exit code, so they work as a release gate. `test_device_tools.py` needs the accessibility service enabled and the overlay permission granted, because it proves that `app_launch` really brings another app to the foreground.

`test_mcp_ws.py` reads the bearer token from `shared_prefs` with `run-as`, which only works on a debug APK; on a release build it asks for `SNAPGENT_TOKEN` instead. `test_mcp_device.py` covers the same ground without that limitation: it writes a small client into the app-private workspace and runs it with `run_python`, so the token stays on the phone and only counts are printed.

## Roadmap

- Auto-start after a reboot before Android rebinds the accessibility service (a `BOOT_COMPLETED` receiver); today the preference covers service eviction plus accessibility rebind, not a cold boot with accessibility off.
- Versioned changelog per APK; CI workflow that builds and signs on tag (keystore injected as repository secrets).
