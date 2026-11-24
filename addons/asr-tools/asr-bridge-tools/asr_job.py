import json
import socket
import time

DEFAULT_PORT = 18125
HOST = "127.0.0.1"


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


def _summary(result, job_id):
    words = result.get("words") or []
    segments = result.get("segments") or []
    text = result.get("text") or " ".join(s.get("text", "") for s in segments)
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
    job_id = (ARGS.get("id") or "").strip()
    if not job_id:
        raise ValueError("id is required: the job id returned by asr_transcribe")

    wait_s = ARGS.get("wait_s")
    wait_s = 5.0 if wait_s in (None, "") else float(wait_s)
    wait_s = max(0.0, min(wait_s, 45.0))

    started = time.time()
    while True:
        try:
            polled = _request("/job?id=" + job_id)
        except OSError as error:
            raise RuntimeError("ASR daemon is not reachable on 127.0.0.1:%d (%s)" % (PORT, error))
        state = polled.get("state") or "unknown"
        if state == "done":
            return _summary(polled.get("result") or {}, job_id)
        if state == "error":
            raise RuntimeError(
                "transcription failed: %s" % (polled.get("error") or "unknown error"))
        if time.time() - started >= wait_s:
            return {
                "ok": True,
                "state": state,
                "job": job_id,
                "elapsed": round(time.time() - started, 1),
            }
        time.sleep(1.5)


print(json.dumps(_run(), ensure_ascii=False))
