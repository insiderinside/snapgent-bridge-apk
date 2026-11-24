"""Collects the result of a running transcription job (custom bridge tool, kind=python)."""
# snapgent-service: base=18125 small=18126  (bridge "Services" page: one entry per port)

import json
import socket
import time

HOST = "127.0.0.1"
SERVICES = {"base": 18125, "small": 18126}
DEFAULT_SERVICE = "base"
HINT = ("start the daemon: addons/asr-tools/asr-daemon.sh start   "
        "(small: ASR_MODEL=model ASR_PORT=18126 addons/asr-tools/asr-daemon.sh start)")


def _port_of(service):
    service = (service or DEFAULT_SERVICE).strip().lower()
    if service not in SERVICES:
        raise ValueError("service must be one of: " + ", ".join(sorted(SERVICES)))
    return service, SERVICES[service]


def _health(port, timeout=1.5):
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
        return json.loads(raw[split + 4:].decode("utf-8", "replace"))
    except (OSError, RuntimeError, ValueError) as error:
        return {"ok": False, "error": str(error)}


def _request(target, timeout=30.0):
    sock = socket.create_connection((HOST, PORT), 5.0)
    sock.settimeout(timeout)
    try:
        payload = (
            "GET " + target + " HTTP/1.1\r\n"
            "Host: %s:%d\r\nConnection: close\r\n\r\n" % (HOST, PORT)
        ).encode("ascii")
        sock.sendall(payload)
        chunks = []
        while True:
            block = sock.recv(65536)
            if not block:
                break
            chunks.append(block)
    finally:
        sock.close()
    raw = b"".join(chunks)
    split = raw.find(b"\r\n\r\n")
    if split < 0:
        raise RuntimeError("daemon sent an incomplete HTTP response")
    return json.loads(raw[split + 4:].decode("utf-8", "replace"))


def _down(service, port, error):
    return {
        "ok": False,
        "state": "service_down",
        "service": service,
        "port": port,
        "error": str(error),
        "hint": HINT,
    }


def _summary(result, job_id, service):
    words = result.get("words") or []
    segments = result.get("segments") or []
    text = result.get("text") or " ".join(s.get("text", "") for s in segments)
    return {
        "ok": True,
        "state": "done",
        "service": service,
        "job": job_id,
        "duration": round(result.get("duration") or 0.0, 2),
        "segments_total": len(segments),
        "segments": segments,
        "words_total": len(words),
        "timing": result.get("timing") or {},
        "vad": result.get("vad"),
        "language": result.get("language"),
        "text": (text or "").strip(),
        "words": words,
    }


def _run():
    global PORT
    if ARGS.get("port") not in (None, ""):
        PORT = int(ARGS["port"])
        service = (ARGS.get("service") or "custom").strip().lower()
    else:
        service, PORT = _port_of(ARGS.get("service"))

    job_id = (ARGS.get("id") or "").strip()
    if not job_id:
        raise ValueError("id is required: the job id returned by asr_transcribe")

    probe = _health(PORT)
    if not probe.get("ok"):
        return _down(service, PORT, probe.get("error", "no answer from the health endpoint"))

    wait_s = ARGS.get("wait_s")
    wait_s = 5.0 if wait_s in (None, "") else float(wait_s)
    wait_s = max(0.0, min(wait_s, 45.0))

    started = time.time()
    while True:
        try:
            polled = _request("/job?id=" + job_id)
        except OSError as error:
            return _down(service, PORT, error)
        state = polled.get("state") or "unknown"
        if state == "done":
            return _summary(polled.get("result") or {}, job_id, service)
        if state == "error":
            raise RuntimeError(
                "transcription failed: %s" % (polled.get("error") or "unknown error"))
        if time.time() - started >= wait_s:
            return {
                "ok": True,
                "state": state,
                "service": service,
                "job": job_id,
                "elapsed": round(time.time() - started, 1),
            }
        time.sleep(1.5)


print(json.dumps(_run(), ensure_ascii=False))
