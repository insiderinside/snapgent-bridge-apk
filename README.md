# Snapgent Bridge APK

Android-native Java 17 project for an on-device local WebSocket bridge and MCP-compatible tool handler. Intended to connect the SnapgentV3 extension running in an Android browser to services inside the same phone, without a desktop bridge.

## Planned MVP

- User-controlled Android foreground service.
- WebSocket listener on `127.0.0.1:8765` (loopback-only).
- JSON-RPC 2.0 handler with MCP `initialize`, `tools/list`, and `tools/call`.
- Initial safe tools to report Android device/app status and access the app's own files.
- Explicit opt-in for expanded Android access; no root required for basic tools.

## Compatibility caveat

The exact SnapgentV3 extension-to-bridge wire protocol has not yet been inspected. The WebSocket envelope, port, and discovery handshake must be verified against the existing SnapgentV3 extension/desktop bridge before claiming plug-and-play compatibility.

## Build

Requires JDK 17, Android SDK Platform 35 / Build Tools, and Gradle 8.7 compatible with Android Gradle Plugin 8.6.1. The connected Mac currently has JDK 17 and ADB, but has no Android SDK or Gradle and only approximately 2.5 GiB available disk space. Avoid downloading build dependencies until disk space is adequate.
