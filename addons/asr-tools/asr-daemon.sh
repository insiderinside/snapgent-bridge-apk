#!/usr/bin/env bash
# Control the on-device CTranslate2/Whisper ASR daemon that the bridge tools call.
#
# The daemon must run as the shell user: the bridge app is not allowed to exec
# binaries from its own storage, but it can open a socket to 127.0.0.1, and the
# daemon can read files anywhere the shell user can (e.g. /sdcard/Download).
#
#   addons/asr-tools/asr-daemon.sh start     # after every reboot
#   addons/asr-tools/asr-daemon.sh status
#   addons/asr-tools/asr-daemon.sh logs
#   addons/asr-tools/asr-daemon.sh stop
#
# start and stop also switch the three ASR bridge tools in the registry, through
# asr-service-sync.py: they are enabled while any service answers and disabled
# once every service is down, so tool_list never offers a tool that cannot run.
#
# Environment overrides: ASR_PORT (18125), ASR_MODEL (/data/local/tmp/ct2/model-base),
# ASR_BEAM (5), ASR_THREADS (6), ASR_COMPUTE (int8), ASR_MIN_SPEECH (0.25),
# ASR_SYNC_TOOLS (1; set 0 to leave the tool registry untouched).
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

# Keep the registry in step with the daemons: enabled while any service answers,
# disabled once every service is down. Failures here must never fail start/stop.
sync_tools() {
  [ "${ASR_SYNC_TOOLS:-1}" = "0" ] && return 0
  local script
  script="$(cd "$(dirname "$0")" && pwd)/asr-service-sync.py"
  [ -f "$script" ] || return 1
  python3 "$script" --quiet >/dev/null 2>&1 || {
    echo "note: bridge not reachable, tool registry left as it was" >&2
    return 1
  }
}

# `ps -A -o PID,CMD` truncates the command to 15 characters on Android, so match the
# real command line from /proc instead. pidof still finds the process by binary name.
pids() {
  adb shell "for p in \$(pidof whisper_ct2_arm64 2>/dev/null); do tr '\\0' ' ' < /proc/\$p/cmdline | grep -q -- '--serve $PORT' && echo \$p; done" \
    | tr -d '\r' | tr '\n' ' '
}

start() {
  if [ -n "$(pids)" ]; then
    echo "daemon already running on $PORT (pid $(pids))"
    sync_tools
    return 0
  fi
  adb shell "test -x $BIN" || { echo "missing $BIN on the device" >&2; return 1; }
  adb shell "test -d $MODEL" || { echo "missing model directory $MODEL on the device" >&2; return 1; }
  # setsid detaches the daemon inside Android, and this adb call is backgrounded
  # too: it keeps waiting for the daemon it started, which is what used to make
  # `start` hang forever.
  adb shell "rm -f $LOG" >/dev/null 2>&1 || true
  adb shell "cd $ROOT && LD_LIBRARY_PATH=$ROOT setsid $BIN --serve $PORT $MODEL $ROOT/mel80.bin $BEAM $THREADS $COMPUTE $MIN_SPEECH > $LOG 2>&1 < /dev/null &" >/dev/null 2>&1 </dev/null &

  for _ in $(seq 1 40); do
    sleep 0.5
    if adb shell "grep -q '^ready:' $LOG 2>/dev/null"; then
      adb shell "grep '^ready:' $LOG" | tr -d '\r'
      sync_tools
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
    sync_tools
    return 0
  fi
  for pid in $current; do
    adb shell "kill -9 $pid" >/dev/null 2>&1 || true
  done
  echo "stopped daemon on $PORT (pid $(echo $current | tr ' ' ','))"
  sync_tools
}

status() {
  if [ -z "$(pids)" ]; then
    echo "stopped (port $PORT)"
    return 1
  fi
  echo "running on $PORT: pid $(pids)"
  adb shell "grep '^ready:' $LOG 2>/dev/null | tail -1" | tr -d '\r' || true
}

# ------------------------------------------------ controller (on/off from the app)
#
# The Services page can start and stop a service, but the app may not exec the
# daemon itself, so the buttons talk to asr-ctl: a tiny loopback server that runs
# as the shell user (see asr-ctl.c). Install it once; it survives until reboot.
CTL_PORT=${ASR_CTL_PORT:-18124}
CTL_BIN=$ROOT/asr-ctl
CTL_CONF=$ROOT/asr-services.conf
CTL_LOG=$ROOT/asr-ctl.log

ctl_pid() { adb shell pidof asr-ctl 2>/dev/null | tr -d '\r' || true; }

ctl_conf() {
  adb shell "printf '# name port model beam threads compute min_speech\nbase 18125 /data/local/tmp/ct2/model-base 5 6 int8 0.25\nsmall 18126 /data/local/tmp/ct2/model 5 6 int8 0.25\n' > $CTL_CONF"
}

ctl_install() {
  local here
  here="$(cd "$(dirname "$0")" && pwd)"
  [ -x "$here/build/asr-ctl" ] || "$here/build-asr-ctl.sh"
  adb push "$here/build/asr-ctl" "$CTL_BIN" >/dev/null
  adb shell chmod 755 "$CTL_BIN"
  ctl_conf
  echo "installed $CTL_BIN and $CTL_CONF"
}

ctl_start() {
  local pid attempt
  pid=$(ctl_pid)
  if [ -n "$pid" ]; then
    echo "controller already running (pid $pid)"
    return 0
  fi
  ctl_install
  for attempt in 1 2 3 4 5; do
    adb shell "cd $ROOT && rm -f $CTL_LOG" >/dev/null 2>&1 || true
    # Same lesson as the daemon itself: setsid inside, and nothing waits for it here,
    # otherwise the launch would be the thing that hangs.
    adb shell "cd $ROOT && (setsid ./asr-ctl $CTL_PORT > $CTL_LOG 2>&1 < /dev/null &)" >/dev/null 2>&1
    for _ in $(seq 1 20); do
      sleep 0.2
      if adb shell "grep -q '^ready:' $CTL_LOG 2>/dev/null"; then
        adb shell "grep '^ready:' $CTL_LOG" | tr -d '\r'
        return 0
      fi
    done
    # A controller killed a moment ago can leave loopback connections behind, and
    # bind fails until they drain: wait for that instead of reporting a dead start.
    if ! adb shell "grep -q 'Address already in use' $CTL_LOG 2>/dev/null"; then
      break
    fi
    echo "port $CTL_PORT still busy, retrying ($attempt)" >&2
    sleep 1
  done
  echo "controller did not start; last log lines:" >&2
  adb shell "tail -5 $CTL_LOG 2>/dev/null" >&2 || true
  return 1
}

ctl_stop() {
  local pid
  pid=$(ctl_pid)
  if [ -z "$pid" ]; then
    echo "controller not running"
    return 0
  fi
  adb shell "kill $pid" >/dev/null 2>&1 || true
  echo "stopped controller (pid $pid)"
}

ctl_restart() {
  local pid
  pid=$(ctl_pid)
  if [ -n "$pid" ]; then
    adb shell "kill $pid" >/dev/null 2>&1 || true
    echo "stopped controller (pid $pid)"
    sleep 0.5
  fi
  ctl_start
}

ctl_status() {
  local pid
  pid=$(ctl_pid)
  if [ -z "$pid" ]; then
    echo "controller stopped (port $CTL_PORT)"
    return 1
  fi
  echo "controller running: pid $pid on 127.0.0.1:$CTL_PORT"
  adb shell "cat $CTL_CONF 2>/dev/null" | tr -d '\r' || true
}

case "${1:-start}" in
  start) start ;;
  stop) stop ;;
  restart) stop; start ;;
  status) status ;;
  logs) adb shell "tail -${2:-30} $LOG" | tr -d '\r' ;;
  ctl)
    case "${2:-status}" in
      install) ctl_install ;;
      start) ctl_start ;;
      restart) ctl_restart ;;
      stop) ctl_stop ;;
      status) ctl_status ;;
      *)
        echo "usage: $0 ctl {install|start|restart|stop|status}" >&2
        exit 2
        ;;
    esac
    ;;
  *)
    echo "usage: $0 {start|stop|restart|status|logs [lines]|ctl {install|start|restart|stop|status}}" >&2
    exit 2
    ;;
esac
