#!/usr/bin/env python3
"""End-to-end test of the adb-style device control tools over ADB + WebSocket.

Covers device_status, screen_dump, screen_screenshot, screen_tap_text,
screen_swipe, screen_key, screen_text, screen_wait and app_launch against the
real device. Requires the Snapgent Bridge accessibility service to be enabled
and the bridge running; every failure prints the tool payload that caused it.
"""
import base64
import json
import os
import socket
import struct
import subprocess
import sys

SERIAL = os.environ.get('SNAPGENT_SERIAL', '59f6c411')
LOCAL_PORT = 18766
REMOTE_PORT = 17613
SCREEN_WIDTH = 1800

DEVICE_TOOLS = [
    'device_status', 'screen_dump', 'screen_screenshot', 'screen_tap', 'screen_tap_text',
    'screen_swipe', 'screen_long_press', 'screen_key', 'screen_text', 'screen_wait',
    'app_list', 'app_launch',
]


def adb(*args):
    return subprocess.check_output(['adb', '-s', SERIAL, *args], text=True, timeout=20).strip()


class WS:
    def __init__(self):
        self.sock = socket.create_connection(('127.0.0.1', LOCAL_PORT), 6)
        self.sock.settimeout(20)
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
        self.next_id = 0

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

    def tool(self, name, **arguments):
        self.next_id += 1
        number = self.next_id
        self.send({'type': 'call_tool', 'id': number, 'server': 'android', 'name': name,
                   'arguments': arguments})
        reply = self.receive()
        if reply.get('id') != number:
            raise AssertionError(f'Unexpected reply id: {reply}')
        return reply

    def ok(self, name, **arguments):
        reply = self.tool(name, **arguments)
        assert reply.get('ok') is True, f'{name} failed: {reply}'
        return reply['text']

    def close(self):
        self.sock.close()


def check(label, condition, detail=''):
    if not condition:
        raise AssertionError(f'{label}: {detail}')
    print(f'  {label}: OK')


def main():
    adb('forward', f'tcp:{LOCAL_PORT}', f'tcp:{REMOTE_PORT}')
    try:
        ws = WS()
        try:
            greeting = ws.receive()
            assert greeting.get('type') == 'connected', greeting
            ws.next_id = 10

            print('Catalog and device status')
            ws.next_id += 1
            ws.send({'type': 'list_tools', 'id': ws.next_id})
            listing = ws.receive()
            names = [tool['name'] for tool in listing['tools']]
            for name in DEVICE_TOOLS:
                check(f'{name} listed', name in names)
            check('catalog size is 65', len(names) == 65, names)

            status = json.loads(ws.ok('device_status'))
            check('device control ready', status['deviceControlReady'] is True, status)
            check('screenshots supported', status['screenshotSupported'] is True, status)
            print(f"  screen: {status['screenWidth']}x{status['screenHeight']} ({status['orientation']})")

            print('Launch an app from the background')
            launched = json.loads(ws.ok('app_launch', package='com.android.settings'))
            check('app_launch brought Settings to the foreground', launched['foreground'] is True, launched)
            check('launch reports the package it was asked for',
                  launched.get('launched') == 'com.android.settings', launched)

            print('Navigate and read the screen')
            matched = ws.ok('screen_wait', text='Setelan', timeout_ms=8000)
            print(f'  screen_wait matched: {json.loads(matched)["matched"]}')

            dump = json.loads(ws.ok('screen_dump'))
            check('dump has nodes', dump['count'] > 5, dump['count'])
            check('dump nodes carry tap coordinates',
                  all(',' in node['center'] for node in dump['nodes'][:20]))
            clickable = json.loads(ws.ok('screen_dump', clickable_only=True))
            check('clickable-only dump is smaller', clickable['count'] <= dump['count'],
                  f"{clickable['count']} vs {dump['count']}")

            filtered = json.loads(ws.ok('screen_dump', filter='setelan'))
            check('text filter narrows the dump', filtered['count'] <= dump['count'],
                  f"{filtered['count']} vs {dump['count']}")

            print('Screenshot')
            shot = json.loads(ws.ok('screen_screenshot'))
            check('screenshot saved', shot['path'].startswith('screenshots/'), shot)
            check('screenshot has pixels', shot['bytes'] > 10000, shot)
            check('screenshot matches the screen width', shot['width'] == SCREEN_WIDTH, shot)
            png = json.loads(ws.ok('read_file_base64', path=shot['path']))
            raw = base64.b64decode(png['data'][:32])
            check('screenshot is a real PNG', raw[:8] == b'\x89PNG\r\n\x1a\n', raw[:8])
            print(f"  {shot['path']} — {shot['bytes']} bytes, {shot['width']}x{shot['height']}")

            print('Scroll')
            swiped = json.loads(ws.ok('screen_swipe', x1=900, y1=2000, x2=900, y2=900, duration_ms=250))
            check('swipe delivered', swiped['swiped'] is True, swiped)

            print('Tap by label inside our own app')
            own = json.loads(ws.ok('app_launch', package='dev.snapgent.bridge'))
            check('app_launch brought our own app back', own['foreground'] is True, own)
            ws.ok('screen_wait', text='Tools', timeout_ms=8000)  # bottom nav, present on every page
            tapped = json.loads(ws.ok('screen_tap_text', text='Files'))
            check('tap_text found the Files tab', tapped['method'] in ('action', 'tap'), tapped)
            ws.ok('screen_wait', text='WORKSPACE STORAGE', timeout_ms=8000)
            print('  Files page reached through a synthetic tap')

            print('Type into a real input field')
            ws.ok('screen_tap_text', text='⌂', exact=True)  # bottom nav home
            ws.ok('screen_wait', text='YOUR LOCAL WORKSPACE', timeout_ms=8000)
            ws.ok('screen_tap_text', text='⚙', exact=True)  # header gear opens Settings
            ws.ok('screen_wait', text='PERSONALIZE YOUR BRIDGE', timeout_ms=8000)
            settings_dump = json.loads(ws.ok('screen_dump'))
            port_node = next((node for node in settings_dump['nodes']
                              if node['text'].isdigit() and len(node['text']) >= 4), None)
            check('Settings shows the port field', port_node is not None,
                  [node['text'] for node in settings_dump['nodes']][:20])
            current_port = port_node['text']
            typed_value = ('2' if current_port[0] != '2' else '3') + current_port[1:]
            ws.ok('screen_tap_text', text=current_port, exact=True)
            typed = json.loads(ws.ok('screen_text', text=typed_value))
            check('text accepted by the field', typed['method'] in ('set_text', 'paste'), typed)
            # The accessibility tree lags a set_text by a frame or two, so poll
            # for the new value instead of dumping once and racing it.
            ws.ok('screen_wait', text=typed_value, timeout_ms=5000)
            after = json.loads(ws.ok('screen_dump', filter=typed_value))
            check('typed text is on screen', after['count'] >= 1, after['count'])

            print('Keys')
            home = json.loads(ws.ok('screen_key', key='home'))
            check('home key sent', home['sent'] is True, home)
            apps = json.loads(ws.ok('app_list'))
            check('app_list returns launchable apps', len(apps) >= 1, len(apps))
            check('app_list includes this bridge', any(a['package'] == 'dev.snapgent.bridge' for a in apps), apps)
            everything = json.loads(ws.ok('app_list', include_system=True))
            check('include_system widens the list', len(everything) > len(apps),
                  f"{len(everything)} vs {len(apps)}")
            shown = ', '.join(f"{a['label']} ({a['package']})" for a in apps[:4])
            print(f'  {len(apps)} user apps ({len(everything)} with system): {shown}')

            print('RESULT: PASS')
        finally:
            ws.close()
    finally:
        adb('forward', '--remove', f'tcp:{LOCAL_PORT}')


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        print(f'RESULT: FAIL — {exc}')
        sys.exit(1)
