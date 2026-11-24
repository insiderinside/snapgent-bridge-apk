#!/usr/bin/env python3
"""Install the ASR custom tools on the Snapgent Bridge app over its loopback WS endpoint.

The tools are plain Python snippets stored on the phone by the bridge itself, so
nothing has to be rebuilt or reinstalled when they change. They talk to the
CTranslate2/Whisper daemon (`whisper_ct2_arm64 --serve`, see scripts/asr-daemon.sh)
over 127.0.0.1, because the Android app process is not allowed to exec binaries
from its own storage.

Usage:
    scripts/asr-daemon.sh start            # once per boot
    scripts/install-asr-bridge-tools.py    # (re)install asr_health/asr_transcribe/asr_job

The bridge's native endpoint on tcp:17613 accepts a WebSocket without the MCP
token (it only requires a browser-like Origin header), which is what the bridge UI
itself uses.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import socket
import struct
import subprocess
import sys
from pathlib import Path

TOOLS_DIR = Path(__file__).resolve().parent / "asr-bridge-tools"
LOCAL_PORT = 17613
REMOTE_PORT = 17613
ORIGIN = "chrome-extension://abcdefghijklmnop"

PORT_ARG = {
    "name": "port",
    "description": "Daemon HTTP port, default 18125",
    "required": False,
    "type": "integer",
}

TOOLS = {
    "asr_health": {
        "description": "Check that the local ASR daemon answers on 127.0.0.1:18125.",
        "params": [PORT_ARG],
        "timeout_ms": 15_000,
    },
    "asr_transcribe": {
        "description": (
            "Transcribe a WAV recording on this device with the local CTranslate2/Whisper "
            "daemon (word timestamps, int8, base model). Returns text, segments and "
            "words[] with start/end seconds. Long audio returns state=running plus a job "
            "id to collect with asr_job."
        ),
        "params": [
            {"name": "path", "description": "Absolute path of a 16 kHz mono WAV on the device",
             "required": True, "type": "string"},
            {"name": "lang", "description": "Language code, default id",
             "required": False, "type": "string"},
            {"name": "vad", "description": "1 to skip silent windows (default), 0 to transcribe everything",
             "required": False, "type": "integer"},
            {"name": "wait_s", "description": "Seconds to wait for the result before returning the job id (default 45, max 45)",
             "required": False, "type": "number"},
            PORT_ARG,
        ],
        "timeout_ms": 60_000,
    },
    "asr_job": {
        "description": "Collect the result of an asr_transcribe job that was still running (state=running).",
        "params": [
            {"name": "id", "description": "Job id returned by asr_transcribe",
             "required": True, "type": "string"},
            {"name": "wait_s", "description": "Seconds to wait for completion (default 5, max 45)",
             "required": False, "type": "number"},
            PORT_ARG,
        ],
        "timeout_ms": 60_000,
    },
}


class WS:
    """Minimal RFC 6455 client: one text frame out, JSON frames in."""

    def __init__(self, sock: socket.socket, buffer: bytes = b""):
        self.sock = sock
        self.buffer = buffer
        self.sock.settimeout(180)

    @classmethod
    def connect(cls, host: str = "127.0.0.1", port: int = LOCAL_PORT) -> "WS":
        sock = socket.create_connection((host, port), 6)
        sock.settimeout(180)
        key = base64.b64encode(os.urandom(16)).decode()
        request = "\r\n".join([
            "GET / HTTP/1.1",
            f"Host: {host}:{REMOTE_PORT}",
            f"Origin: {ORIGIN}",
            "Upgrade: websocket",
            "Connection: Upgrade",
            f"Sec-WebSocket-Key: {key}",
            "Sec-WebSocket-Version: 13",
            "", "",
        ]).encode()
        sock.sendall(request)
        raw = b""
        while b"\r\n\r\n" not in raw:
            block = sock.recv(4096)
            if not block:
                raise SystemExit("bridge closed the connection during the WS handshake")
            raw += block
        header, buffer = raw.split(b"\r\n\r\n", 1)
        if b"101" not in header.split(b"\r\n", 1)[0]:
            raise SystemExit("WS handshake refused: " + header.split(b"\r\n", 1)[0].decode())
        return cls(sock, buffer)

    def _read(self, size: int) -> bytes:
        while len(self.buffer) < size:
            block = self.sock.recv(max(4096, size - len(self.buffer)))
            if not block:
                raise EOFError("bridge closed the websocket")
            self.buffer += block
        out, self.buffer = self.buffer[:size], self.buffer[size:]
        return out

    def send(self, payload: dict) -> None:
        data = json.dumps(payload).encode()
        mask = os.urandom(4)
        size = len(data)
        if size < 126:
            header = bytes([0x81, 0x80 | size])
        elif size < 65536:
            header = b"\x81\xfe" + struct.pack("!H", size)
        else:
            header = b"\x81\xff" + struct.pack("!Q", size)
        self.sock.sendall(header + mask + bytes(v ^ mask[i % 4] for i, v in enumerate(data)))

    def receive(self) -> dict:
        while True:
            first, second = self._read(2)
            opcode = first & 15
            size = second & 127
            if size == 126:
                size = struct.unpack("!H", self._read(2))[0]
            elif size == 127:
                size = struct.unpack("!Q", self._read(8))[0]
            masked = bool(second & 128)
            mask = self._read(4) if masked else None
            data = self._read(size)
            if masked:
                data = bytes(v ^ mask[i % 4] for i, v in enumerate(data))
            if opcode == 8:
                raise EOFError("bridge closed the websocket")
            if opcode == 1:
                return json.loads(data)

    def call(self, name: str, arguments: dict, call_id: int = 1) -> dict:
        self.send({"type": "call_tool", "id": call_id, "server": "android",
                   "name": name, "arguments": arguments})
        while True:
            message = self.receive()
            if message.get("id") == call_id and message.get("type") in ("tool_result", "error"):
                return message

    def close(self) -> None:
        try:
            self.sock.close()
        except OSError:
            pass


def payload_of(message: dict) -> dict:
    text = message.get("text")
    if isinstance(text, str):
        try:
            return json.loads(text)
        except ValueError:
            return {"raw": text}
    return message


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--no-forward", action="store_true",
                        help="skip `adb forward` (when the port is already forwarded)")
    parser.add_argument("--only", action="append", choices=sorted(TOOLS),
                        help="install just these tools")
    args = parser.parse_args()

    if not args.no_forward:
        subprocess.run(["adb", "forward", f"tcp:{LOCAL_PORT}", f"tcp:{REMOTE_PORT}"],
                       check=True, capture_output=True)

    ws = WS.connect()
    selected = args.only or sorted(TOOLS)
    for name in selected:
        spec = TOOLS[name]
        ws.call("tool_create", {
            "name": name,
            "description": spec["description"],
            "kind": "python",
            "code": (TOOLS_DIR / f"{name}.py").read_text(),
            "params": spec["params"],
            "timeout_ms": spec["timeout_ms"],
        })
        print(f"installed {name}")

    listing = json.dumps(payload_of(ws.call("tool_list", {}, call_id=90)))
    missing = [name for name in selected if json.dumps(name) not in listing]
    if missing:
        print("WARNING: not visible in tool_list:", ", ".join(missing))
    else:
        print("tool_list confirms:", ", ".join(selected))
    ws.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
