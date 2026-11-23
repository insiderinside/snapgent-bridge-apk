#!/usr/bin/env python3
"""End-to-end test of the native SnapgentV3 WebSocket endpoint over ADB."""
import base64
import json
import os
import socket
import struct
import subprocess

SERIAL = '59f6c411'
LOCAL_PORT = 18766
REMOTE_PORT = 17613


def adb(*args):
    return subprocess.check_output(['adb', '-s', SERIAL, *args], text=True, timeout=12).strip()


class WS:
    def __init__(self):
        self.sock = socket.create_connection(('127.0.0.1', LOCAL_PORT), 6)
        self.sock.settimeout(8)
        key = base64.b64encode(os.urandom(16)).decode()
        headers = ['GET / HTTP/1.1', f'Host: 127.0.0.1:{REMOTE_PORT}',
                   'Origin: chrome-extension://snapgent-test', 'Upgrade: websocket',
                   'Connection: Upgrade', f'Sec-WebSocket-Key: {key}',
                   'Sec-WebSocket-Version: 13', '', '']
        self.sock.sendall('\r\n'.join(headers).encode())
        raw = b''
        while b'\r\n\r\n' not in raw:
            data = self.sock.recv(4096)
            if not data:
                raise RuntimeError('Disconnected during handshake')
            raw += data
        header, self.buffer = raw.split(b'\r\n\r\n', 1)
        if b'101 Switching Protocols' not in header:
            raise RuntimeError('Handshake failed: ' + header.decode(errors='replace'))
        print('WebSocket handshake: HTTP 101 OK')

    def read(self, n):
        while len(self.buffer) < n:
            block = self.sock.recv(max(4096, n - len(self.buffer)))
            if not block:
                raise EOFError('Socket closed')
            self.buffer += block
        out, self.buffer = self.buffer[:n], self.buffer[n:]
        return out

    def receive(self):
        head = self.read(2)
        size = head[1] & 127
        if size == 126:
            size = struct.unpack('!H', self.read(2))[0]
        elif size == 127:
            size = struct.unpack('!Q', self.read(8))[0]
        if head[1] & 128:
            raise RuntimeError('Server frames must not be masked')
        return json.loads(self.read(size))

    def send(self, payload):
        data = json.dumps(payload).encode()
        mask = os.urandom(4)
        if len(data) < 126:
            header = bytes([0x81, 0x80 | len(data)])
        elif len(data) < 65536:
            header = b'\x81\xfe' + struct.pack('!H', len(data))
        else:
            header = b'\x81\xff' + struct.pack('!Q', len(data))
        self.sock.sendall(header + mask + bytes(v ^ mask[i % 4] for i, v in enumerate(data)))

    def call(self, number, kind, **kwargs):
        self.send({'type': kind, 'id': number, **kwargs})
        result = self.receive()
        if result.get('id') != number:
            raise AssertionError(f'Unexpected reply id: {result}')
        return result

    def close(self):
        self.sock.close()


def main():
    adb('forward', f'tcp:{LOCAL_PORT}', f'tcp:{REMOTE_PORT}')
    try:
        ws = WS()
        try:
            greeting = ws.receive()
            assert greeting.get('type') == 'connected', greeting
            assert greeting.get('mcp_alive') is True, greeting
            print('SnapgentV3 greeting: OK')
            print('Server:', greeting.get('servers'))
            pong = ws.call(1, 'ping')
            assert pong.get('type') == 'pong', pong
            print('Ping: pong OK')
            catalog = ws.call(2, 'list_tools')
            assert catalog.get('type') == 'tools', catalog
            names = [x['name'] for x in catalog['tools']]
            print('Tools:', ', '.join(names))
            assert 'device_info' in names, names
            device = ws.call(3, 'call_tool', server='android', name='device_info', arguments={})
            assert device.get('type') == 'tool_result' and device.get('ok') is True, device
            info = json.loads(device['text'])
            print('Device info:', info['manufacturer'], info['model'], 'Android', info['androidVersion'])
            files = ws.call(4, 'call_tool', server='android', name='list_files', arguments={'path': ''})
            assert files.get('ok') is True, files
            print('App-private list_files: OK')
            blocked = ws.call(5, 'call_tool', server='vscode', name='device_info', arguments={})
            assert blocked.get('ok') is False, blocked
            print('Unselected server access: blocked OK')
            print('RESULT: PASS')
        finally:
            ws.close()
    finally:
        adb('forward', '--remove', f'tcp:{LOCAL_PORT}')


if __name__ == '__main__':
    main()
