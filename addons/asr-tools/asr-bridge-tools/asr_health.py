"""Reports whether the local ASR daemons answer (custom bridge tool, kind=python).

A daemon is a separate process, so a tool call can outlive it: this probe is what
lets every ASR tool answer "service_down" in under two seconds instead of waiting
for a timeout. Pass all=1 to check every known service at once.
"""
# snapgent-service: base=18125 small=18126  (bridge "Services" page: one entry per port)

import json
import socket

HOST = "127.0.0.1"
SERVICES = {"base": 18125, "small": 18126}
DEFAULT_SERVICE = "base"
HINT = ("start the daemon: addons/asr-tools/asr-daemon.sh start   "
        "(small: ASR_MODEL=model ASR_PORT=18126 addons/asr-tools/asr-daemon.sh start)")


def _port_of(name):
    name = (name or DEFAULT_SERVICE).strip().lower()
    if name not in SERVICES:
        raise ValueError("service must be one of: " + ", ".join(sorted(SERVICES)))
    return SERVICES[name]


def _probe(name, port, timeout=1.5):
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
            raise RuntimeError("daemon sent an incomplete HTTP response")
        body = json.loads(raw[split + 4:].decode("utf-8", "replace"))
        return {
            "ok": True,
            "state": "ready",
            "service": name,
            "port": port,
            "backend": body.get("backend"),
            "model": body.get("model"),
            "beam": body.get("beam"),
            "threads": body.get("threads"),
            "load_seconds": body.get("load_seconds"),
        }
    except (OSError, ValueError, RuntimeError) as error:
        return {
            "ok": False,
            "state": "service_down",
            "service": name,
            "port": port,
            "error": str(error),
            "hint": HINT,
        }


def _run():
    # An explicit port pins one process; otherwise the service name picks it.
    if ARGS.get("port") not in (None, ""):
        port = int(ARGS["port"])
        name = (ARGS.get("service") or "custom").strip().lower()
        if name in SERVICES and SERVICES[name] != port:
            name = "custom"
        return _probe(name, port)

    if str(ARGS.get("all", "0")) not in ("0", "false", "False"):
        probes = [_probe(name, port) for name, port in sorted(SERVICES.items())]
        live = [p["service"] for p in probes if p["ok"]]
        return {
            "ok": bool(live),
            "state": "ready" if live else "service_down",
            "live": live,
            "services": probes,
            "hint": None if live else HINT,
        }

    name = (ARGS.get("service") or DEFAULT_SERVICE).strip().lower()
    return _probe(name, _port_of(name))


print(json.dumps(_run(), ensure_ascii=False))
