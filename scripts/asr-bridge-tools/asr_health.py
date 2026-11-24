"""Reports whether the local ASR daemon answers (custom bridge tool, kind=python)."""
import json
import socket

DEFAULT_PORT = 18125
HOST = "127.0.0.1"


def _run():
    port = int(ARGS.get("port") or DEFAULT_PORT)
    sock = socket.create_connection((HOST, port), 5.0)
    sock.settimeout(10.0)
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


print(json.dumps(_run(), ensure_ascii=False))
