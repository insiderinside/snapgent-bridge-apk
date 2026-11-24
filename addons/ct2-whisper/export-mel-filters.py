#!/usr/bin/env python3
"""Exports the OpenAI Whisper mel filterbank as a flat float32 file.

The C++ runner reads it as (kNmels x (N_FFT/2 + 1)) little-endian float32.

  python3 addons/ct2-whisper/export-mel-filters.py /tmp/mel_filters.npz /tmp/mel80.bin

The npz comes from OpenAI Whisper assets:
  https://raw.githubusercontent.com/openai/whisper/main/whisper/assets/mel_filters.npz
"""
import sys

import numpy as np


def main(src: str, dst: str) -> int:
    data = np.load(src)
    mel = data["mel_80"]  # shape (80, 201) for N_FFT=400
    assert mel.shape == (80, 201), f"unexpected mel shape {mel.shape}"
    mel.astype(np.float32).tofile(dst)
    print(f"wrote {dst}: {mel.shape[0]}x{mel.shape[1]} float32")
    return 0


if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:3]))
