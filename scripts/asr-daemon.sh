#!/usr/bin/env bash
# Control the on-device CTranslate2/Whisper ASR daemon that the bridge tools call.
#
# The daemon must run as the shell user: the bridge app is not allowed to exec
# binaries from its own storage, but it can open a socket to 127.0.0.1, and the
# daemon can read files anywhere the shell user can (e.g. /sdcard/Download).
#
#   scripts/asr-daemon.sh start     # after every reboot
#   scripts/asr-daemon.sh status
#   scripts/asr-daemon.sh logs
#   scripts/asr-daemon.sh stop
#
# Environment overrides: ASR_PORT (18125), ASR_MODEL (/data/local/tmp/ct2/model-base),
# ASR_BEAM (5), ASR_THREADS (6), ASR_COMPUTE (int8), ASR_MIN_SPEECH (0.25).
set -euo pipefail

ROOT=/data/local/tmp/ct2
PORT=${ASR_PORT:-18125}
MODEL=${ASR_MODEL:-$ROOT/model-base}
BEAM=${ASR_BEAM:-5}
THREADS=${ASR_THREADS:-6}
COMPUTE=${ASR_COMPUTE:-int8}
MIN_SPEECH=${ASR_MIN_SPEECH:-0.25}
BIN=${ASR_BIN:-$ROOT/whisper_ct2_arm64}
LOG=$ROOT/serve-$PORT.log

# `ps -A -o PID,CMD` truncates the command to 15 characters on Android, so match the
# real command line from /proc instead. pidof still finds the process by binary name.
pids() {
  adb shell "for p in \$(pidof whisper_ct2_arm64 2>/dev/null); do tr '\\0' ' ' < /proc/\$p/cmdline | grep -q -- '--serve $PORT' && echo \$p; done" \
    | tr -d '\r' | tr '\n' ' '
}

start() {
  if [ -n "$(pids)" ]; then
    echo "daemon already running on $PORT (pid $(pids))"
    return 0
  fi
  adb shell "test -x $BIN" || { echo "missing $BIN on the device" >&2; return 1; }
  adb shell "test -d $MODEL" || { echo "missing model directory $MODEL on the device" >&2; return 1; }
  adb shell "cd $ROOT && LD_LIBRARY_PATH=$ROOT nohup $BIN --serve $PORT $MODEL $ROOT/mel80.bin $BEAM $THREADS $COMPUTE $MIN_SPEECH > $LOG 2>&1 &" >/dev/null 2>&1 || true

  for _ in $(seq 1 40); do
    sleep 0.5
    if adb shell "grep -q '^ready:' $LOG 2>/dev/null"; then
      adb shell "grep '^ready:' $LOG" | tr -d '\r'
      return 0
    fi
  done
  echo "daemon did not report ready; last log lines:" >&2
  adb shell "tail -5 $LOG 2>/dev/null" >&2 || true
  return 1
}

stop() {
  local current
  current=$(pids)
  if [ -z "$current" ]; then
    echo "no daemon on $PORT"
    return 0
  fi
  for pid in $current; do
    adb shell "kill -9 $pid" >/dev/null 2>&1 || true
  done
  echo "stopped daemon on $PORT (pid $(echo $current | tr ' ' ','))"
}

status() {
  if [ -z "$(pids)" ]; then
    echo "stopped (port $PORT)"
    return 1
  fi
  echo "running on $PORT: pid $(pids)"
  adb shell "grep '^ready:' $LOG 2>/dev/null | tail -1" | tr -d '\r' || true
}

case "${1:-start}" in
  start) start ;;
  stop) stop ;;
  restart) stop; start ;;
  status) status ;;
  logs) adb shell "tail -${2:-30} $LOG" | tr -d '\r' ;;
  *)
    echo "usage: $0 {start|stop|restart|status|logs [lines]}" >&2
    exit 2
    ;;
esac
