"""Transcribes a WAV on the phone with the local ASR daemon (custom bridge tool, kind=python).

The daemon is a separate process: pre-flight the health endpoint first so a dead
service answers service_down immediately instead of holding the call for 70 s.
"""
# snapgent-service: base=18125 small=18126  (bridge "Services" page: one entry per port)

import json
import os
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


def _down(service, port, error):
    return {
        "ok": False,
        "state": "service_down",
        "service": service,
        "port": port,
        "error": str(error),
        "hint": HINT,
    }


def _request(method, target, body=None, timeout=70.0):
    sock = socket.create_connection((HOST, PORT), 5.0)
    sock.settimeout(timeout)
    try:
        head = [
            method + " " + target + " HTTP/1.1",
            "Host: %s:%d" % (HOST, PORT),
            "Connection: close",
        ]
        if body is not None:
            head.append("Content-Type: application/octet-stream")
            head.append("Content-Length: %d" % len(body))
        payload = ("\r\n".join(head) + "\r\n\r\n").encode("ascii")
        sock.sendall(payload if body is None else payload + body)
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


def _summary(result, job_id, service):
    words = result.get("words") or []
    segments = result.get("segments") or []
    text = (result.get("text") or "").strip() or " ".join(
        s.get("text", "") for s in segments).strip()
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

    # A daemon can die between calls; answer that before reading a large file.
    probe = _health(PORT)
    if not probe.get("ok"):
        return _down(service, PORT, probe.get("error", "no answer from the health endpoint"))

    path = (ARGS.get("path") or "").strip()
    if not path:
        raise ValueError("path is required: absolute path of a 16 kHz mono WAV on this device")
    if not os.path.isfile(path):
        raise ValueError("file not readable by the bridge app: " + path)

    try:
        handle = open(path, "rb")
        audio = handle.read()
        handle.close()
    except OSError as error:
        raise ValueError("cannot read %s: %s" % (path, error))

    if len(audio) < 2000:
        raise ValueError("file too small to be a WAV recording: %d bytes" % len(audio))

    lang = (ARGS.get("lang") or "id").strip() or "id"
    vad = 1 if str(ARGS.get("vad", "1")) not in ("0", "false", "False") else 0
    wait_s = ARGS.get("wait_s")
    wait_s = 45.0 if wait_s in (None, "") else float(wait_s)
    wait_s = max(2.0, min(wait_s, 45.0))

    started = time.time()
    try:
        submitted = _request("POST", "/asr?vad=%d&async=1&lang=%s" % (vad, lang), audio)
    except OSError as error:
        return _down(service, PORT, error)

    if not submitted.get("ok"):
        raise RuntimeError("daemon rejected the request: %s" % (submitted.get("error") or submitted))

    job_id = submitted.get("job") or ""
    deadline = started + wait_s
    state = "running"
    while time.time() < deadline:
        time.sleep(1.5)
        try:
            polled = _request("GET", "/job?id=" + job_id, None, 20.0)
        except OSError as error:
            return _down(service, PORT, error)
        state = polled.get("state") or "running"
        if state == "done":
            return _summary(polled.get("result") or {}, job_id, service)
        if state == "error":
            raise RuntimeError("transcription failed: %s" % (polled.get("error") or "unknown error"))

    return {
        "ok": True,
        "state": "running",
        "service": service,
        "job": job_id,
        "elapsed": round(time.time() - started, 1),
        "hint": "still transcribing; call asr_job with this job id (or bump wait_s) to collect the result",
    }


print(json.dumps(_run(), ensure_ascii=False))
