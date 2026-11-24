#!/usr/bin/env bash
# Jalankan matriks preset ASR CTranslate2 di HP dan cetak baris timing-nya.
#
# Prasyarat di device (lihat docs/ct2-android-asr.md):
#   /data/local/tmp/ct2/{libctranslate2.so,libc++_shared.so,whisper_ct2_arm64,mel80.bin,voice16k.wav}
#   /data/local/tmp/ct2/model      -> Systran/faster-whisper-small (484 MB)
#   /data/local/tmp/ct2/model-base -> Systran/faster-whisper-base  (145 MB)
# Model diunduh dari https://huggingface.co/Systran/faster-whisper-<name>/resolve/main/
# (model.bin, config.json, tokenizer.json, vocabulary.txt) dan TIDAK dibundel ke APK.
#
# Contoh: addons/ct2-whisper/bench-asr-presets.sh
# Sebelum membandingkan konfigurasi, diamkan HP ~90 detik: di Snapdragon 865 suhu
# 47-50 C membuat run yang sama 24 s (dingin) vs ~33 s (panas).
set -euo pipefail

ADB_BIN=${ADB_BIN:-adb}
DIR=${DEVICE_DIR:-/data/local/tmp/ct2}
WAV=${WAV:-voice16k.wav}
LANG_CODE=${LANG_CODE:-id}

run() {
  local model=$1 beam=$2 threads=$3 tag=$4
  echo "=== $tag (model=$model beam=$beam threads=$threads) ==="
  "$ADB_BIN" shell "cd $DIR && s=\$(date +%s); \
    LD_LIBRARY_PATH=$DIR ./whisper_ct2_arm64 ./$model ./$WAV $LANG_CODE ./mel80.bin ./out-$tag.json $beam $threads int8 2>&1 \
    | grep -E '^(timing|\{)'; \
    e=\$(date +%s); echo WALL \$((e-s))s"
}

# Preset terdokumentasi (Snapdragon 865, int8, audio 65 s).
run model-base 1 8 base-b1-t8
run model-base 5 6 base-b5-t6
run model          1 8 small-b1-t8
run model          5 6 small-b5-t6

echo
echo "Bandingkan akurasi hasil terbaru:"
echo "  addons/ct2-whisper/compare-word-timings.py ~/kontent/asr_words.json <hasil.json>"
