#!/usr/bin/env python3
"""Keep the ASR bridge tools enabled only while at least one ASR daemon is alive.

The rule this enforces: the ASR tools are a thin front for a separate daemon
process, so they are only worth offering while that process answers. When every
known service is down, the three tools are marked disabled in the bridge, which
removes them from tool_list and makes a call fail fast instead of timing out.
When any service answers again, they are enabled again.

The state is not cached anywhere: every run probes the daemons and writes the
verdict, so the registry follows the services on its own. `asr-daemon.sh start`
and `asr-daemon.sh stop` call this, which is what makes the rule automatic.

Usage:
    addons/asr-tools/asr-service-sync.py            # apply the rule
    addons/asr-tools/asr-service-sync.py --dry-run  # only report what it would do
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import socket
import subprocess
import sys
from pathlib import Path

HOST = "127.0.0.1"
SERVICES = {"base": 18125, "small": 18126}
TOOLS = ("asr_health", "asr_transcribe", "asr_job")
HERE = Path(__file__).resolve().parent
INSTALLER = HERE / "install-asr-bridge-tools.py"
LOCAL_PORT = 17613
REMOTE_PORT = 17613


def probe(name: str, port: int, timeout: float = 1.5) -> dict:
    try:
        sock = socket.create_connection((HOST, port), timeout)
        sock.settimeout(timeout)
        try:
            sock.sendall((
                "GET /health HTTP/1.1\r\n"
                "Host: %s:%d\r\nConnection: close\r\n\r\n" % (HOST, port)
            ).encode("ascii"))
            chunks = []
            while True:
                block = sock.recv(4096)
                if not block:
                    break
                chunks.append(block)
        finally:
            sock.close()
        raw = b"".join(chunks)
        split = raw.find(b"\r\n\r\n")
        if split < 0:
            raise RuntimeError("incomplete HTTP response")
        body = json.loads(raw[split + 4:].decode("utf-8", "replace"))
        return {"service": name, "port": port, "state": "ready",
                "model": body.get("model"), "beam": body.get("beam"),
                "threads": body.get("threads")}
    except (OSError, RuntimeError, ValueError) as error:
        return {"service": name, "port": port, "state": "service_down", "error": str(error)}


def load_ws_class():
    """Reuse the WebSocket client the installer already ships, without copying it."""
    spec = importlib.util.spec_from_file_location("asr_installer", INSTALLER)
    if spec is None or spec.loader is None:
        raise RuntimeError("cannot load " + str(INSTALLER))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--no-forward", action="store_true",
                        help="skip `adb forward` (when the port is already forwarded)")
    parser.add_argument("--dry-run", action="store_true",
                        help="report the verdict without touching the registry")
    parser.add_argument("--tools", default=",".join(TOOLS),
                        help="comma-separated tools to switch (default: %(default)s)")
    parser.add_argument("--quiet", action="store_true", help="only print on failure")
    args = parser.parse_args()

    # The probe runs on this machine, so each daemon port must be forwarded first;
    # without it a live daemon looks dead and the rule would switch the tools off.
    if not args.no_forward:
        for port in sorted(SERVICES.values()):
            subprocess.run(["adb", "forward", f"tcp:{port}", f"tcp:{port}"],
                           check=False, capture_output=True)

    services = [probe(name, port) for name, port in sorted(SERVICES.items())]
    live = [s["service"] for s in services if s["state"] == "ready"]
    enable = bool(live)
    tools = [t.strip() for t in args.tools.split(",") if t.strip()]

    verdict = {
        "ok": True,
        "rule": "enabled while any ASR service answers",
        "live": live,
        "services": services,
        "enabled": enable,
        "tools": {t: enable for t in tools},
        "applied": False,
    }

    if args.dry_run:
        print(json.dumps(verdict, ensure_ascii=False))
        return 0

    if not args.no_forward:
        subprocess.run(["adb", "forward", f"tcp:{LOCAL_PORT}", f"tcp:{REMOTE_PORT}"],
                       check=False, capture_output=True)

    module = load_ws_class()
    try:
        ws = module.WS.connect()
    except OSError as error:
        verdict.update({"ok": False, "applied": False, "error": str(error),
                        "hint": "bridge is not reachable on 127.0.0.1:%d; "
                                "nothing changed, run this again once it is up" % LOCAL_PORT})
        print(json.dumps(verdict, ensure_ascii=False))
        return 2

    try:
        for name in tools:
            ws.call("tool_set_enabled", {"name": name, "enabled": enable}, call_id=1)
        verdict["applied"] = True
    finally:
        ws.close()

    if not args.quiet:
        print(json.dumps(verdict, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
