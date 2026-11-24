# Add-ons

Everything in this folder is optional. An add-on ships nothing in the APK: the
Gradle build never compiles or packages any of it, so a user who does not want
the feature sees no extra size, permission, or behaviour change.

Each add-on is used by running its own scripts and reading its own docs. Skipping
an add-on means simply not running those scripts; nothing is enabled by default.

## Local ASR (CTranslate2 / faster-whisper with word timestamps)

Transcribes a WAV on the phone with the real faster-whisper engine and returns
word-level timestamps (`word`, `start`, `end`) — the shape Snapgent's voice-over
sync expects. Android's own recogniser returns no word timings, and
`faster-whisper` cannot be installed with pip on Android (CTranslate2 ships no
bionic wheels), so the engine is cross-compiled for `arm64-v8a`.

| Path | What it is |
|---|---|
| `ct2-whisper/runner.cc`, `mel.cc`, `mel.h` | Standalone C++ runner: beam search, int8, word alignment, `--serve`, silence-aware VAD |
| `ct2-whisper/build-ct2-android.sh` | Cross-compiles CTranslate2 + the runner with the NDK |
| `ct2-whisper/export-mel-filters.py` | Turns OpenAI's mel filterbank into the flat float32 the runner reads |
| `ct2-whisper/bench-asr-presets.sh` | Runs the measured presets on the device and prints timings |
| `ct2-whisper/compare-word-timings.py` | Compares a result against ground truth (precision/recall, start/end error) |
| `asr-tools/asr-daemon.sh` | Starts, stops and reports the shell-user daemon |
| `asr-tools/install-asr-bridge-tools.py` | Installs the three bridge tools over the loopback WebSocket |
| `asr-tools/asr-bridge-tools/*.py` | The tool snippets: `asr_transcribe`, `asr_job`, `asr_health` |

The engine cannot run inside the app — an app process may not exec a binary from
its own storage, but it may open a loopback socket — so it runs as a shell-user
daemon and the tools are plain Python snippets that call it.

### Install

```bash
addons/ct2-whisper/build-ct2-android.sh /tmp/ct2-android   # once, needs the NDK
# push libctranslate2.so, libc++_shared.so, whisper_ct2_arm64, mel80.bin and a
# model under /data/local/tmp/ct2 (see the doc below)
addons/asr-tools/asr-daemon.sh start            # after every reboot
addons/asr-tools/install-asr-bridge-tools.py    # installs/refreshes all three tools
```

Remove the add-on again with `asr-tools/asr-daemon.sh stop` and `tool_delete` for
the three tools; nothing stays behind in the app or the APK.

### Proof

Measured on a Snapdragon 865 (Android 14): 118 words in 36.4 s for a 65 s clip;
the async path returned a 215 s clip 33 s after a submit that took 2 s. The
silence-aware VAD cut that same 215 s clip from 58.8 s to 35.6 s and removed 16
words the decoder hallucinated on the silence, while the gate against ground
truth stayed at 97 of 126 words with a p90 start error of 0.12 s.

Full contract, build flags, presets and gotchas: [docs/ct2-android-asr.md](../docs/ct2-android-asr.md).
