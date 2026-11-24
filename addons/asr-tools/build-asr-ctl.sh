#!/usr/bin/env bash
# Build asr-ctl (the loopback start/stop controller) for the phone.
#
#   addons/asr-tools/build-asr-ctl.sh            # -> build/asr-ctl
#   addons/asr-tools/build-asr-ctl.sh --push     # also push it to the device
#
# Plain C, so the only dependency is the NDK toolchain that already builds the
# Whisper runner. No CMake, no C++ runtime, one small static-ish executable.
set -euo pipefail

NDK=${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/29.0.14206865}
API=${ASR_CTL_API:-24}
HOST_TAG=darwin-x86_64
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
CC="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang"

[ -x "$CC" ] || { echo "NDK clang not found at $CC" >&2; exit 1; }

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
OUT_DIR="$SCRIPT_DIR/build"
mkdir -p "$OUT_DIR"

"$CC" -O2 -Wall -Wextra -Wno-unused-parameter \
  -o "$OUT_DIR/asr-ctl" "$SCRIPT_DIR/asr-ctl.c"

echo "built $OUT_DIR/asr-ctl ($(wc -c < "$OUT_DIR/asr-ctl") bytes)"
"$TOOLCHAIN/bin/llvm-strip" "$OUT_DIR/asr-ctl" 2>/dev/null || true

if [ "${1:-}" = "--push" ]; then
  adb push "$OUT_DIR/asr-ctl" /data/local/tmp/ct2/asr-ctl >/dev/null
  adb shell chmod 755 /data/local/tmp/ct2/asr-ctl
  echo "pushed to /data/local/tmp/ct2/asr-ctl"
fi
