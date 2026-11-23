# Snapgent Bridge APK

Android-native (Java 17) on-device bridge. It runs a loopback-only WebSocket server inside the phone and speaks both the MCP JSON-RPC protocol and the SnapgentV3 extension's native WebSocket protocol, so the Snapgent extension running in an Android browser can read files, edit a workspace, and drive Git on the device — without the desktop Python bridge.

Current build: **0.1.0** (`dev.snapgent.bridge`, minSdk 26, targetSdk 34).

## Download the release APK

- Latest release: [Releases v0.1.0](https://github.com/insiderinside/snapgent-bridge-apk/releases/tag/v0.1.0) — asset `snapgent-bridge-0.1.0.apk`.
- Install over ADB: `adb install -r snapgent-bridge-0.1.0.apk`.

The published APK is signed with the standard Android **debug keystore** (`signingConfig signingConfigs.debug`) for internal testing. Re-sign with a private keystore before any wider distribution.

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

## Tools (29)

**Device and files (4):** `device_info`, `list_files`, `read_file`, `write_file` — app-private storage only, 512 KiB per file.

**Workspace, VS Code style (18):** `get_workspace_info`, `get_project_info`, `list_dir`, `list_tree`, `find_files`, `search_files`, `read_files`, `read_file_lines`, `get_file_info`, `get_file_outline`, `create_file`, `edit_file`, `create_directory`, `move_file`, `copy_file`, `delete_file`, `apply_patch`, `run_command` (allowlisted utilities only, no shell, 30 s ceiling).

**Git connector (7):** `git_init`, `git_status`, `git_diff`, `git_log`, `git_branches`, `git_add`, `git_commit` — backed by JGit 6.10 and confined to the app-private workspace, so the extension can review and commit changes on the phone itself.

## Security model

- Listeners are loopback-only; every connection carries the token in the URL.
- No root required, and the bridge never reads outside its own app-private directory: `..`/absolute paths and symlink escapes are rejected.
- File reads/writes are capped at 512 KiB; `delete_file` refuses directories; patch application is all-or-nothing.
- `run_command` runs a fixed allowlist of utilities (`pwd`, `ls`, `cat`, `head`, `tail`, `wc`, `grep`, `stat`, `sha256sum`, `date`, `uname`, `df`) with per-utility argument checks, at most 32 arguments, and no shell.

## Compatibility

The extension wire protocol was taken from the SnapgentV3 extension: the native endpoint greets a client with a `connected` frame (reporting `bridge_version: android-0.2.0` plus the full tool catalog), answers `ping`/`pong`, serves `list_tools`, executes `call_tool`, and accepts `restart_mcp`. `stream` and `progress` frames are accepted as fire-and-forget telemetry.

Telemetry-only caveat: the bridge exposes no desktop `studio` runtime, so `studio_status` intentionally returns nulls.

## Build

Requires JDK 17, Android SDK Platform 35 with Build Tools, and Gradle 8.7 (Android Gradle Plugin 8.6.1).

```bash
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

## Tests

End-to-end tests drive the real device over ADB (`adb -s <serial> forward tcp:1876x tcp:876x`):

```bash
python3 scripts/test_mcp_ws.py         # MCP JSON-RPC over the 8765 endpoint
python3 scripts/test_snapgent_ws.py    # SnapgentV3 native protocol on 17613
python3 scripts/test_workspace_tools.py # workspace + tool catalog (expects 29 tools)
```

## Roadmap

- Optional user-controlled auto-start on boot (preference exists, not wired to a boot receiver yet).
- Release signing with a dedicated keystore and a versioned changelog per APK.
