#!/usr/bin/env bash
# Cross-compiles CTranslate2 and the whisper_ct2 runner for Android arm64-v8a.
#
# This is the build proven on 2026-11-24: CTranslate2 4.9.1 + NDK 29.0.14206865,
# producing an AArch64 ELF that runs on a Snapdragon 865 (Android 14, arm64-v8a)
# and emits word-level timestamps (DTW cross-attention, same engine faster-whisper uses).
#
# Usage:
#   scripts/build-ct2-android.sh [workdir]
#
# Outputs in <workdir>:
#   ct2/build-android/libctranslate2.so
#   wrt/whisper_ct2_arm64
set -euo pipefail

WORK=${1:-/tmp/ct2-android}
NDK=${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/29.0.14206865}
API=24
ABI=arm64-v8a
CT2_TAG=v4.9.1
REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/darwin-x86_64"
[ -x "$TOOLCHAIN/bin/aarch64-linux-android${API}-clang++" ] || {
	echo "NDK toolchain not found at $TOOLCHAIN" >&2
	exit 1
}

mkdir -p "$WORK"
cd "$WORK"

# 1. Source + submodules. The cpuinfo submodule is required: ruy_cpuinfo links to it.
if [ ! -d ct2/.git ]; then
	git clone --depth 1 --branch "$CT2_TAG" https://github.com/OpenNMT/CTranslate2.git ct2
fi
cd ct2
for m in third_party/spdlog third_party/ruy third_party/cpu_features third_party/ruy/third_party/cpuinfo; do
	[ -n "$(ls -A "$m" 2>/dev/null)" ] || git submodule update --init --depth 1 "$m"
done

# 2. Bionic has no pthread_setaffinity_np. CPU affinity is not useful on a phone anyway,
#    so the thread pool falls back to the plain pthread path on Android.
grep -q "__ANDROID__" src/thread_pool.cc || python3 - <<'PY'
import re
p = "src/thread_pool.cc"
s = open(p).read()
s = s.replace("#ifndef __linux__", "#if !defined(__linux__) || defined(__ANDROID__)", 1)
open(p, "w").write(s)
PY

# 3. Cross-compile. MKL/DNNL are x86-only; ruy provides the ARM GEMM path.
#    CMake >= 4 rejects the old cmake_minimum_required in cpu_features.
cmake -B build-android \
	-DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
	-DANDROID_ABI="$ABI" \
	-DANDROID_PLATFORM="android-$API" \
	-DCMAKE_BUILD_TYPE=Release \
	-DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
	-DBUILD_SHARED_LIBS=ON \
	-DWITH_MKL=OFF -DWITH_DNNL=OFF -DWITH_RUY=ON \
	-DOPENMP_RUNTIME=NONE \
	-DWITH_CUDA=OFF -DWITH_CUDNN=OFF -DWITH_MPS=OFF \
	-DBUILD_TESTS=OFF -DBUILD_CLI=OFF
cmake --build build-android -j "$(getconf _NPROCESSORS_ONLN)" --target ctranslate2

cd "$WORK"
mkdir -p wrt

# 4. Mel filterbank (80 x 201 float32) used by the runner.
if [ ! -f wrt/mel80.bin ]; then
	curl -sL -o wrt/mel_filters.npz \
		https://raw.githubusercontent.com/openai/whisper/main/whisper/assets/mel_filters.npz
	python3 "$REPO_ROOT/scripts/export-mel-filters.py" wrt/mel_filters.npz wrt/mel80.bin
fi

# 5. Runner binary (JNI-ready C++ entry point, currently a CLI).
"$TOOLCHAIN/bin/aarch64-linux-android${API}-clang++" -O2 -std=c++17 \
	-I"$WORK/ct2/include" \
	-Wno-deprecated-literal-operator \
	"$REPO_ROOT/app/src/main/cpp/ct2-whisper/runner.cc" \
	"$REPO_ROOT/app/src/main/cpp/ct2-whisper/mel.cc" \
	-L"$WORK/ct2/build-android" -lctranslate2 \
	-o wrt/whisper_ct2_arm64

file wrt/whisper_ct2_arm64
echo "built: $WORK/wrt/whisper_ct2_arm64 + $WORK/ct2/build-android/libctranslate2.so"
