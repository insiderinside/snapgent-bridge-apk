#!/usr/bin/env python3
"""End-to-end MCP JSON-RPC WebSocket tests over ADB."""
import base64
import json
import os
import socket
import struct
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_snapgent_ws import adb

LOCAL_PORT = 18765
REMOTE_PORT = 8765


def get_token():
    # SNAPGENT_TOKEN wins: a release APK is not debuggable, so `run-as` is
    # unavailable there and the token can only be supplied from outside.
    supplied = os.environ.get('SNAPGENT_TOKEN', '').strip()
    if supplied:
        return supplied
    try:
        xml = adb('shell', 'run-as', 'dev.snapgent.bridge', 'cat',
                  'shared_prefs/snapgent_bridge.xml')
    except subprocess.CalledProcessError:
        raise SystemExit(
            'Cannot read the token: this APK is a release build (run-as is debug-only).\n'
            'Copy the MCP connection URL in the app and export SNAPGENT_TOKEN=<token> '
            'for this run.')
    root = ET.fromstring(xml)
    node = root.find("string[@name='auth_token']")
    if node is None or not node.text:
        raise RuntimeError('Bridge authentication token not found')
    return node.text


class MCPWS:
    def __init__(self, sock, buffer=b''):
        self.sock = sock
        self.sock.settimeout(8)
        self.buffer = buffer

    @classmethod
    def connect(cls, token):
        sock = socket.create_connection(('127.0.0.1', LOCAL_PORT), 6)
        sock.settimeout(8)
        key = base64.b64encode(os.urandom(16)).decode()
        request = [
            f'GET /ws?token={token} HTTP/1.1',
            f'Host: 127.0.0.1:{REMOTE_PORT}',
            'Origin: http://localhost',
            'Upgrade: websocket',
            'Connection: Upgrade',
            f'Sec-WebSocket-Key: {key}',
            'Sec-WebSocket-Version: 13', '', ''
        ]
        sock.sendall('\r\n'.join(request).encode())
        raw = b''
        while b'\r\n\r\n' not in raw:
            block = sock.recv(4096)
            if not block:
                sock.close()
                raise RuntimeError('Disconnected during MCP handshake')
            raw += block
        header, buffer = raw.split(b'\r\n\r\n', 1)
        status = header.split(b'\r\n', 1)[0].decode()
        if '101 Switching Protocols' not in status:
            sock.close()
            return None, status
        return cls(sock, buffer), status

    def read(self, size):
        while len(self.buffer) < size:
            block = self.sock.recv(max(4096, size - len(self.buffer)))
            if not block:
                raise EOFError('WebSocket closed')
            self.buffer += block
        result, self.buffer = self.buffer[:size], self.buffer[size:]
        return result

    def send(self, payload):
        data = json.dumps(payload, separators=(',', ':')).encode()
        mask = os.urandom(4)
        size = len(data)
        if size < 126:
            header = bytes([0x81, 0x80 | size])
        elif size < 65536:
            header = b'\x81\xfe' + struct.pack('!H', size)
        else:
            header = b'\x81\xff' + struct.pack('!Q', size)
        masked = bytes(value ^ mask[i % 4] for i, value in enumerate(data))
        self.sock.sendall(header + mask + masked)

    def receive(self):
        while True:
            first, second = self.read(2)
            opcode = first & 15
            size = second & 127
            if size == 126:
                size = struct.unpack('!H', self.read(2))[0]
            elif size == 127:
                size = struct.unpack('!Q', self.read(8))[0]
            masked = bool(second & 128)
            mask = self.read(4) if masked else None
            data = self.read(size)
            if masked:
                data = bytes(v ^ mask[i % 4] for i, v in enumerate(data))
            if opcode == 8:
                raise EOFError('WebSocket closed by server')
            if opcode == 9:
                continue
            if opcode == 1:
                return json.loads(data)

    def call(self, number, method, params=None):
        request = {'jsonrpc': '2.0', 'id': number, 'method': method}
        if params is not None:
            request['params'] = params
        self.send(request)
        while True:
            response = self.receive()
            if response.get('id') == number:
                return response

    def close(self):
        self.sock.close()


def main():
    adb('forward', f'tcp:{LOCAL_PORT}', f'tcp:{REMOTE_PORT}')
    ws = None
    try:
        token = get_token()

        rejected, status = MCPWS.connect(token + 'invalid')
        assert rejected is None and '403' in status, status
        print('Authentication: invalid token rejected (403) OK')

        ws, status = MCPWS.connect(token)
        assert ws is not None, status
        print('WebSocket handshake: HTTP 101 OK')

        initialized = ws.call(1, 'initialize', {
            'protocolVersion': '2025-06-18',
            'capabilities': {},
            'clientInfo': {'name': 'snapgent-mcp-test', 'version': '1.0.0'}
        })
        assert 'result' in initialized, initialized
        assert initialized['result']['protocolVersion'] == '2025-06-18', initialized
        assert 'tools' in initialized['result']['capabilities'], initialized
        print('MCP initialize: protocol 2025-06-18 OK')

        ws.send({'jsonrpc': '2.0', 'method': 'notifications/initialized'})
        catalog = ws.call(2, 'tools/list')
        tools = catalog['result']['tools']
        names = [tool['name'] for tool in tools]
        assert len(names) == 65, names
        assert 'device_info' in names and 'run_command' in names and 'git_status' in names
        print(f'tools/list: {len(names)} tools OK')

        result = ws.call(3, 'tools/call', {
            'name': 'device_info', 'arguments': {}
        })
        assert 'result' in result, result
        content = result['result']['content']
        assert content and content[0]['type'] == 'text', content
        device = json.loads(content[0]['text'])
        assert device['manufacturer'] == 'Xiaomi', device
        assert device['androidVersion'] == '14', device
        print('tools/call device_info: Xiaomi Android 14 OK')
        print('RESULT: PASS (MCP WebSocket endpoint)')
    finally:
        if ws:
            ws.close()
        try:
            adb('forward', '--remove', f'tcp:{LOCAL_PORT}')
        except Exception:
            pass


if __name__ == '__main__':
    main()
