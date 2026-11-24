import json
import os
import socket
import time

DEFAULT_PORT = 18125
HOST = "127.0.0.1"


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


def _summary(result, job_id):
    words = result.get("words") or []
    segments = result.get("segments") or []
    text = (result.get("text") or "").strip() or " ".join(
        s.get("text", "") for s in segments).strip()
    return {
        "ok": True,
        "state": "done",
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
    PORT = int(ARGS.get("port") or DEFAULT_PORT)
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
        raise RuntimeError(
            "ASR daemon is not reachable on 127.0.0.1:%d (%s). Start it with: "
            "adb shell 'cd /data/local/tmp/ct2 && LD_LIBRARY_PATH=. nohup ./whisper_ct2_arm64 --serve %d "
            "./model-base ./mel80.bin 5 6 int8 0.25 > serve.log 2>&1 &'" % (PORT, error, PORT)
        )

    if not submitted.get("ok"):
        raise RuntimeError("daemon rejected the request: %s" % (submitted.get("error") or submitted))

    job_id = submitted.get("job") or ""
    deadline = started + wait_s
    state = "running"
    while time.time() < deadline:
        time.sleep(1.5)
        polled = _request("GET", "/job?id=" + job_id, None, 20.0)
        state = polled.get("state") or "running"
        if state == "done":
            return _summary(polled.get("result") or {}, job_id)
        if state == "error":
            raise RuntimeError("transcription failed: %s" % (polled.get("error") or "unknown error"))

    return {
        "ok": True,
        "state": "running",
        "job": job_id,
        "elapsed": round(time.time() - started, 1),
        "hint": "still transcribing; call asr_job with this job id (or bump wait_s) to collect the result",
    }


print(json.dumps(_run(), ensure_ascii=False))
