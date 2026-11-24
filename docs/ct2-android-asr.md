# CTranslate2 / faster-whisper on Android arm64 (word timestamps)

This is an optional add-on (`addons/`): none of it is compiled into the APK or
enabled by default. Not running these scripts leaves the app exactly as released.

Status: **proven on device** — 2026-11-24, phone `23043RP34G` (Snapdragon 865,
Android 14, arm64-v8a), model `Systran/faster-whisper-small`, Indonesian audio.

This is the real faster-whisper engine (CTranslate2), not a reimplementation:
the same `libctranslate2` that backs the Python package, cross-compiled for bionic,
with word-level timestamps produced by the same DTW cross-attention path
(`WhisperReplica::align()`).

## Why a build was needed at all

`ctranslate2` wheels on PyPI cover macOS, manylinux (glibc) and Windows only.
Android uses bionic and ships no official CPython, so `pip install faster-whisper`
cannot work there. The library itself has no Android-specific code — the port is a
build problem, not a rewrite.

## Build

```bash
addons/ct2-whisper/build-ct2-android.sh /tmp/ct2-android
```

Key decisions, all required:

| Item | Value | Why |
|---|---|---|
| NDK | 29.0.14206865, `ANDROID_PLATFORM=android-24`, `ANDROID_ABI=arm64-v8a` | Only arm64 device target |
| `WITH_MKL` / `WITH_DNNL` | OFF | x86-only backends |
| `WITH_RUY` | ON | ARM GEMM path |
| `OPENMP_RUNTIME` | NONE | NDK has no OpenMP runtime; built-in thread pool is used |
| `CMAKE_POLICY_VERSION_MINIMUM=3.5` | set | CMake 4 rejects the old `cmake_minimum_required` in `cpu_features` |
| submodules | `spdlog`, `ruy`, `cpu_features`, `ruy/third_party/cpuinfo` | `ruy_cpuinfo` links `cpuinfo::cpuinfo` |
| `src/thread_pool.cc` | guard `pthread_setaffinity_np` with `defined(__ANDROID__)` | bionic does not declare it; affinity is not useful on a phone |

Result: `libctranslate2.so` 58.5 MB, `NEEDED` only `libdl/liblog/libm/libc`;
runner `whisper_ct2_arm64` 128 KB, interpreter `/system/bin/linker64`.

## Runner contract

```
whisper_ct2_arm64 <model_dir> <wav16k_mono> <lang|auto> <mel_filters.bin> <out.json> \
                  [beam=5] [threads=4] [int8|float32|float16|auto] [min_speech=0.25]

whisper_ct2_arm64 --serve <port> <model_dir> <mel_filters.bin> \
                  [beam=5] [threads=6] [int8] [min_speech=0.25]
```

`min_speech` is the VAD threshold: a 30 s window whose longest speech run is shorter
than this is skipped entirely instead of being decoded.

`model_dir` holds a stock CT2 model (`model.bin`, `config.json`, `vocabulary.txt`,
`tokenizer.json`) — `Systran/faster-whisper-small` needs no conversion.

Output:

```json
{"language":"id","duration":64.91,"chunks":3,"backend":"ctranslate2",
 "segments":[{"start":0.0,"end":5.56,"text":"...",
              "words":[{"word":"Dari","start":0.0,"end":0.30}]}]}
```

Word timing is extracted the way faster-whisper's `find_alignment()` does it: take the
DTW path (pairs of `text_index`/`frame_index`), keep the first frame at which each text
token index is reached, group BPE tokens into words on the leading `Ġ`/space, then
`word.start = time[first_token]`, `word.end = time[next_word_first_token]`.
`frame_index * 0.02 s`; each chunk is offset by `chunk * 30 s`.
When the DTW path does not reach every token (short boundary windows), the segment
falls back to character-weighted proportional timing instead of one collapsed span.

The runner also prints a timing line to stderr and embeds the same numbers in the JSON:

```
timing: load=3.51s mel=0.67s generate=56.60s align=16.71s total=77.50s (duration=64.91s chunks=3)
```

## Speed engineering — what actually made it fast

Baseline before this round: `small`, int8, 8 threads, one `align()` per segment —
**2m37-2m43s** for 65 s of audio (2.5x slower than realtime). Three changes took that
to **78 s** (`small` beam 5) and **24-33 s** (`base` beam 1), with better timing accuracy:

1. **One `align()` per 30 s window, not per segment.** `align()` runs the encoder
   (`maybe_encode`, the expensive part) exactly once per call, then decodes every text
   sequence of the batch against that single encoder pass. Calling it once per segment
   meant 19 encoder passes over overlapping windows of the same audio.
   Batching segments as separate batch entries does **not** work: CT2's decoder needs
   the memory batch to match the token batch and throws
   `std::invalid_argument: MatMul: batch dimension of inputs a and b should match`.
   The supported shape is the one faster-whisper itself uses: concatenate the token
   ids of every segment in the window into **one** text sequence, call `align()` once,
   then split the returned DTW path back per segment through the recorded token
   offsets. Effect on `small`: align **86 s → 19 s**.
2. **One batched `generate()` across all windows.** `Whisper::generate()` accepts
   `features` with batch N and one prompt per window, and `post_batch` runs it as a
   single job, so the encoder/decoder work on all three 30 s windows together instead
   of three separate calls. Effect on `base` beam 5: generate **34.5 s → 23 s**.
   (`small` barely profits: 51.8 s → 47.5 s, its encoder saturates the cores already.)
3. **Thread count after batching.** Batched generation is memory-bandwidth bound, so 6
   threads beat 8 on this 1+3+4 core SoC: `base` beam 5 went **44 s @8t → 33 s @6t**.
   Beam 1 still prefers 8 threads (generate is short, align dominates).

Thermal note: under sustained load the SoC settles around 47-50 °C and a run that
takes 24 s while cool takes ~33 s warm. Every number below is a single fresh run; treat
±30% as normal warm-device variance.

## Measured quality

Against `~/kontent/asr_words.json` (Python faster-whisper, `small` float16, beam 5),
same audio, matched words only (`addons/ct2-whisper/compare-word-timings.py`). Precision/recall are
computed against exactly the words that were emitted, so a model that transcribes more
text does not automatically look better; the row to watch is p90 start delta.

| Model | Beam | Threads | Wall | Matched words | p90 start | p90 end | within 0.2 s |
|---|---|---|---|---|---|---|---|
| `small` | 5 | 6 | 78 s | 103 | **0.080 s** | 0.380 s | 94.2% |
| `small` | 1 | 8 | 71 s | 99 | 0.080 s | 0.380 s | 92.9% |
| `base` | 5 | 6 | 33-36 s | 96 | 0.120 s | 0.320 s | 94.8% |
| `base` | 1 | 8 | 24-33 s | 96-97 | 0.120-0.160 s | 0.260-0.280 s | 91.7-92.8% |
| `tiny` | 5 | 8 | 44 s | 77 | 0.385 s | 0.420 s | 70.1% |

Word-start accuracy is now median **0.000-0.020 s** for every usable preset. Word ends
stay looser (p90 0.26-0.38 s) because `end` is the start of the next word, so a pause
after a word inflates it; the ground truth has the same convention but different
segmentation, so the two disagree on pauses rather than on speech.

Degenerate segments (a complete DTW path that still pins every token to the window's
last frame) are detected and redistributed proportionally by character length, which
removed the earlier 1.7 s outliers. `tiny` is not usable: it drops whole sentences
(77 matched words, p90 0.385 s), so `base` is the floor.

## Measured speed summary (Snapdragon 865, int8, 65 s audio)

| Preset | Wall time | vs realtime | Use for |
|---|---|---|---|
| `small` beam 5, 6 threads | 78 s | 1.2x slower | Best text + best timing (103 words) |
| `base` beam 5, 6 threads | 33-36 s | 0.5x (2x faster) | Default: near-`small` timing, half the time |
| `base` beam 1, 8 threads | 24-33 s | 0.4x (2.5x faster) | Fastest acceptable: draft / pre-sync |

float16 weights are converted to float32/float32-compute on this backend if compute
type is left at `auto` (4m02s with the old code), so `int8` is the right default.

## On-device run

```bash
adb push libctranslate2.so libc++_shared.so whisper_ct2_arm64 mel80.bin /data/local/tmp/ct2/
adb push model/* /data/local/tmp/ct2/model/
adb push voice16k.wav /data/local/tmp/ct2/
adb shell "cd /data/local/tmp/ct2 && LD_LIBRARY_PATH=. ./whisper_ct2_arm64 ./model-base ./voice16k.wav id ./mel80.bin ./out.json 5 6 int8"
```

`libc++_shared.so` comes from the NDK sysroot
(`toolchains/llvm/prebuilt/darwin-x86_64/sysroot/usr/lib/aarch64-linux-android/`).

## Packaging notes (before this ships in the APK)

- `libctranslate2.so` (58.5 MB) + `libc++_shared.so` (9.3 MB) would add ~68 MB per ABI.
  Only `arm64-v8a` is configured in `app/build.gradle`, so that is 68 MB once.
- The `small` model is 484 MB and must **not** be bundled: download it on first use
  (HuggingFace `Systran/faster-whisper-small`) or side-load it into app storage.
- `base` beam 5 at 6 threads turns a 60 s clip around in ~33 s (`small` beam 5: ~78 s),
  so a blocking tool call is now defensible for short clips; anything longer should
  still run as a background job with progress events.
- The bridge reaches the engine today through a loopback daemon plus custom tools
  (next section), so no native library has to ship in the APK. Bundling the library
  and exposing the same call over JNI remains the option if the daemon is unwanted.

## Silence-aware VAD

Every 30 s window is scored before it is decoded: frames are split into 10 ms hops,
RMS energy is compared against the recording's noise floor, and the window is dropped
when its longest speech run is shorter than `min_speech`. Two things follow:

- Silence-heavy recordings get much faster, because no decoder work and no DTW happen
  for a silent window — and no hallucinated text is produced for one either.
- A window that is mostly speech is processed exactly as before, so timings do not move.

Measured on the same 65 s Indonesian clip padded with silence (`base`, beam 5, 6 threads):

| Test audio | Without VAD | With VAD | Words | Matched vs ground truth |
|---|---|---|---|---|
| 65 s clip, 30 s lead + 120 s tail silence (215 s) | 58.8 s | 35.6 s | 130 → 114 | 96 → 97 (p90 0.12 → 0.10 s) |
| Same clip, short tail (95 s) | — | 36.4 s | 118 | 97 (p90 start 0.12 s, 94.8% ≤0.2 s) |

The 16 extra words in the non-VAD run were hallucinated inside the silent tail.

## Bridge integration: loopback daemon + custom tools

The app process cannot exec binaries from its own storage (`PermissionError` on the
extracted runner), but it *can* open a socket to `127.0.0.1`. So the engine runs as the
shell user and the bridge talks HTTP to it:

```
adb shell daemon  ──HTTP──▶  127.0.0.1:18125  (whisper_ct2_arm64 --serve)
        ▲                              ▲
        │ custom tool (Python, app uid) │
   MCP client ──────────────▶ Snapgent Bridge app
```

Why this avoids a new APK: a bridge custom tool is a Python snippet stored on the phone
by the bridge itself (`tool_create`), executed by the embedded interpreter. Updating the
tool never rebuilds the app.

### Daemon

```bash
addons/asr-tools/asr-daemon.sh start     # after every reboot; prints the ready line
addons/asr-tools/asr-daemon.sh status
addons/asr-tools/asr-daemon.sh logs 40
addons/asr-tools/asr-daemon.sh stop
```

Environment overrides: `ASR_PORT`, `ASR_MODEL`, `ASR_BEAM`, `ASR_THREADS`,
`ASR_COMPUTE`, `ASR_MIN_SPEECH`. Two daemons can run side by side on different ports
(e.g. `base` on 18125 for speed, `small` on 18126 for accuracy) — the tools take a
`port` argument.

HTTP contract:

| Request | Meaning |
|---|---|
| `GET /health` | model, beam, threads, load time |
| `POST /asr?vad=1&async=1&lang=id` with `{"wav": "/abs/path"}` | queue a file the daemon can read |
| `POST /asr?vad=1&async=1&lang=id` with raw WAV bytes | upload audio the app owns (daemon writes a temp file) |
| `GET /job?id=jN` | `state=running|done|error`, `result` when done |

The orchestrator keeps the last 16 jobs in memory, so a caller that hits the 60 s tool
timeout can collect the result later instead of re-running the decode.

### Tools

```bash
addons/asr-tools/install-asr-bridge-tools.py            # installs/refreshes all three
addons/asr-tools/install-asr-bridge-tools.py --only asr_transcribe
```

| Tool | Arguments | Returns |
|---|---|---|
| `asr_transcribe` | `path`, `lang=id`, `vad=1`, `wait_s=45`, `port=18125` | `text`, `segments[]`, `words[]`, `timing`, or `state=running` + `job` |
| `asr_job` | `id`, `wait_s=5`, `port` | the finished payload |
| `asr_health` | `port` | daemon `/health` |

`asr_transcribe` uploads the WAV bytes, so the file only has to be readable by the app
(its own storage, `/sdcard/Download`, or `/data/local/tmp` where the mode allows it).
`words[]` is exactly the shape `asr_sync` consumes (`word`, `start`, `end`).

Measured through the bridge (not the CLI): 65 s clip → `words_total=118`,
`total=36.4 s` in the payload; a 215 s clip submitted with `wait_s=2` came back
`state=running`, and `asr_job` returned `done` after 33 s of polling.
