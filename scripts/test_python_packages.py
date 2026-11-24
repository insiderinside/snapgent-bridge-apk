#!/usr/bin/env python3
"""Verify the packages bundled into the embedded Python runtime.

Run with the phone connected over ADB and the bridge service running:

    python3 scripts/test_python_packages.py

Checks that Chaquopy packed `requests` and `Pillow` into the APK (not just the
standard library), that both import on-device, and that Pillow's native
`_imaging` extension actually works by writing and re-reading a PNG.

The native Snapgent endpoint (17613) is used so the test never needs the MCP
pairing token. No outbound network call is made here.
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_snapgent_ws import WS, adb, LOCAL_PORT, REMOTE_PORT

PROBE = r"""
import json, os, sys

os.makedirs("python", exist_ok=True)
report = {}

import requests
report["requests"] = requests.__version__

import PIL
from PIL import Image
report["pillow"] = PIL.__version__

# Native _imaging must be importable; a pure-Python stub would fail here.
from PIL import _imaging
report["imaging"] = getattr(_imaging, "__file__", "").split("/")[-1]

img = Image.new("RGB", (64, 64), (255, 176, 0))
for x in range(64):
    for y in range(64):
        if (x // 8 + y // 8) % 2 == 0:
            img.putpixel((x, y), (16, 24, 32))
img.save("python/probe.png", "PNG")

with Image.open("python/probe.png") as back:
    back = back.convert("RGB")
    report["size"] = list(back.size)
    report["mode"] = back.mode
    report["pixel"] = list(back.getpixel((0, 0)))
    report["resized"] = list(back.resize((32, 32)).size)

report["ok"] = True
print(json.dumps(report))
"""


def main():
    adb('forward', f'tcp:{LOCAL_PORT}', f'tcp:{REMOTE_PORT}')
    ws = WS()
    counter = 700

    def call(name, args=None):
        nonlocal counter
        counter += 1
        response = ws.call(counter, 'call_tool', server='android', name=name, arguments=args or {})
        if not response.get('ok'):
            raise AssertionError(f'{name} failed: {response}')
        return response.get('text', '')

    try:
        greeting = ws.receive()
        assert greeting['type'] == 'connected', greeting
        assert greeting['servers'][0]['tools'] == 53, greeting['servers']
        print('Catalog: 53 Android tools OK')

        info = json.loads(call('python_info'))
        assert info['ok'], info
        bundled = info.get('packages') or []
        print('python:', info['version'], 'on', info['machine'])
        print('bundled:', ', '.join(p for p in bundled if not p.startswith('chaquopy')))

        lowered = {name.lower() for name in bundled}
        assert 'requests' in lowered, f'requests missing from {sorted(lowered)}'
        assert 'pillow' in lowered, f'Pillow missing from {sorted(lowered)}'

        result = json.loads(call('run_python', {'code': PROBE}))
        assert result.get('exit_code') == 0, result
        probe = json.loads(result['output'].strip().splitlines()[-1])
        assert probe['ok'], probe
        print('requests:', probe['requests'], '| Pillow:', probe['pillow'])
        print('native ext:', probe['imaging'], '| image:', probe['size'], probe['mode'])
        print('resize:', probe['resized'], '| pixel(0,0):', probe['pixel'])

        listing = json.loads(call('list_files', {'path': 'python'}))
        names = {entry.get('name') for entry in listing}
        assert 'probe.png' in names, names
        print('outputs present:', sorted(names))
        print('Python packages OK')
    finally:
        try:
            ws.sock.close()
        except Exception:
            pass


if __name__ == '__main__':
    main()
