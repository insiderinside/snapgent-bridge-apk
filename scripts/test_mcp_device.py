#!/usr/bin/env python3
"""MCP WebSocket end-to-end test that runs *inside* the phone.

`scripts/test_mcp_ws.py` reads the bearer token with `run-as`, which only works on a
debug APK. A release APK is not debuggable, so this variant pushes a small client
into the app-private workspace, lets `run_python` execute it with the app's own
UID, and reads the token from `shared_prefs` there. The token never crosses ADB
and never appears in the output: only counts and booleans are printed.

Same coverage as test_mcp_ws.py: invalid-token rejection, initialize, tools/list,
and one tools/call.
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_snapgent_ws import WS, adb, LOCAL_PORT, REMOTE_PORT

DEVICE_SCRIPT = r'''
import base64, json, os, re, socket, struct, sys

def main():
    prefs = os.path.join(os.getcwd(), '..', '..', 'shared_prefs', 'snapgent_bridge.xml')
    with open(prefs, encoding='utf-8') as handle:
        match = re.search(r'<string name="auth_token">([^<]+)</string>', handle.read())
    if not match:
        return {'error': 'auth_token not found in shared_prefs'}
    token = match.group(1)

    def connect(value):
        sock = socket.create_connection(('127.0.0.1', 8765), 6)
        sock.settimeout(10)
        key = base64.b64encode(os.urandom(16)).decode()
        request = [
            'GET /ws?token=' + value + ' HTTP/1.1',
            'Host: 127.0.0.1:8765',
            'Origin: http://localhost',
            'Upgrade: websocket',
            'Connection: Upgrade',
            'Sec-WebSocket-Key: ' + key,
            'Sec-WebSocket-Version: 13', '', '']
        sock.sendall('\r\n'.join(request).encode())
        raw = b''
        while b'\r\n\r\n' not in raw:
            block = sock.recv(4096)
            if not block:
                sock.close()
                return None, b'', 'disconnected'
            raw += block
        header, rest = raw.split(b'\r\n\r\n', 1)
        status = header.split(b'\r\n', 1)[0].decode()
        if '101 Switching Protocols' not in status:
            sock.close()
            return None, b'', status
        return sock, rest, status

    bad, _, bad_status = connect(token + 'invalid')
    rejected = bad is None and '403' in bad_status

    sock, buffer, status = connect(token)
    if sock is None:
        return {'error': 'valid token rejected: ' + status, 'rejected': rejected}

    def send(payload):
        data = json.dumps(payload, separators=(',', ':')).encode()
        mask = os.urandom(4)
        size = len(data)
        if size < 126:
            header = bytes([0x81, 0x80 | size])
        elif size < 65536:
            header = b'\x81\xfe' + struct.pack('!H', size)
        else:
            header = b'\x81\xff' + struct.pack('!Q', size)
        sock.sendall(header + mask + bytes(v ^ mask[i % 4] for i, v in enumerate(data)))

    def receive(buffer):
        while True:
            while len(buffer) < 2:
                buffer += sock.recv(4096)
            first, second = buffer[0], buffer[1]
            size = second & 127
            offset = 2
            if size == 126:
                while len(buffer) < 4:
                    buffer += sock.recv(4096)
                size = struct.unpack('!H', buffer[2:4])[0]
                offset = 4
            elif size == 127:
                while len(buffer) < 10:
                    buffer += sock.recv(4096)
                size = struct.unpack('!Q', buffer[2:10])[0]
                offset = 10
            masked = bool(second & 128)
            mask = None
            if masked:
                while len(buffer) < offset + 4:
                    buffer += sock.recv(4096)
                mask = buffer[offset:offset + 4]
                offset += 4
            while len(buffer) < offset + size:
                buffer += sock.recv(4096)
            data = buffer[offset:offset + size]
            buffer = buffer[offset + size:]
            if masked:
                data = bytes(v ^ mask[i % 4] for i, v in enumerate(data))
            opcode = first & 15
            if opcode == 8:
                raise EOFError('closed by server')
            if opcode == 1:
                return json.loads(data), buffer

    def call(number, method, params, buffer):
        request = {'jsonrpc': '2.0', 'id': number, 'method': method}
        if params is not None:
            request['params'] = params
        send(request)
        while True:
            response, buffer = receive(buffer)
            if response.get('id') == number:
                return response, buffer

    result = {'rejected': rejected, 'handshake': status}
    initialized, buffer = call(1, 'initialize', {
        'protocolVersion': '2025-06-18',
        'capabilities': {},
        'clientInfo': {'name': 'snapgent-device-test', 'version': '1.0.0'}}, buffer)
    result['protocol'] = initialized.get('result', {}).get('protocolVersion')

    send({'jsonrpc': '2.0', 'method': 'notifications/initialized'})
    catalog, buffer = call(2, 'tools/list', None, buffer)
    result['tools'] = len(catalog.get('result', {}).get('tools', []))

    device, buffer = call(3, 'tools/call', {'name': 'device_info', 'arguments': {}}, buffer)
    content = device.get('result', {}).get('content', [])
    result['device_info'] = bool(content and content[0].get('type') == 'text')
    sock.close()
    return result

print(json.dumps(main()))
'''

DEVICE_PATH = '__snapgent_e2e__/mcp_device_check.py'


def main():
    adb('forward', f'tcp:{LOCAL_PORT}', f'tcp:{REMOTE_PORT}')
    ws = WS()
    counter = 300

    def call(name, args=None):
        nonlocal counter
        counter += 1
        response = ws.call(counter, 'call_tool', server='android', name=name,
                           arguments=args or {})
        if not response.get('ok'):
            raise AssertionError(f'{name} failed: {response}')
        return response.get('text') or response.get('output') or ''

    try:
        greeting = ws.receive()
        assert greeting['type'] == 'connected', greeting

        call('create_directory', {'path': '__snapgent_e2e__'})
        call('write_file', {'path': DEVICE_PATH, 'content': DEVICE_SCRIPT})
        print('Device script written')

        counter += 1
        response = ws.call(counter, 'call_tool', server='android', name='run_python',
                           arguments={'path': DEVICE_PATH, 'cwd': '__snapgent_e2e__',
                                      'timeout_ms': 30000})
        assert response.get('ok'), response
        # The native reply nests the interpreter output one level deeper.
        output = response.get('output') or response.get('text') or ''
        if isinstance(output, str):
            try:
                nested = json.loads(output)
                if isinstance(nested, dict) and 'output' in nested:
                    output = nested['output']
            except ValueError:
                pass
        try:
            result = json.loads(output.strip().splitlines()[-1])
        except (ValueError, IndexError):
            raise AssertionError('device script did not return JSON: ' + output[:400])

        assert 'error' not in result, result
        assert result.get('rejected') is True, result
        print('Authentication: invalid token rejected (403) OK')
        assert '101' in str(result.get('handshake', '')), result
        print('WebSocket handshake: HTTP 101 OK')
        assert result.get('protocol') == '2025-06-18', result
        print('MCP initialize: protocol 2025-06-18 OK')
        assert result.get('tools') == 53, result
        print(f"tools/list: {result['tools']} tools OK")
        assert result.get('device_info') is True, result
        print('tools/call device_info: text content OK')
        print('RESULT: PASS (MCP WebSocket endpoint, release APK)')
    finally:
        try:
            call('delete_file', {'path': DEVICE_PATH})
        except Exception:
            pass
        adb('forward', '--remove', f'tcp:{LOCAL_PORT}')


if __name__ == '__main__':
    main()
