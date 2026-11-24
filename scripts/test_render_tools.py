#!/usr/bin/env python3
"""End-to-end checks for the on-device render tools (WebView stills + MediaCodec MP4).

Run with the phone connected over ADB and the bridge service running:

    python3 scripts/test_render_tools.py

The native Snapgent endpoint (17613) is used so the test never needs the MCP
pairing token. Outputs land in app-private storage; only their reported
metadata is asserted here.
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_snapgent_ws import WS, adb, LOCAL_PORT, REMOTE_PORT

HTML = """<!doctype html><html><body style="margin:0;background:#101820">
<canvas id="c" width="320" height="180"></canvas>
<script>
const ctx = document.getElementById('c').getContext('2d');
window.__snapgentSeek = (ms) => {
  ctx.fillStyle = '#101820'; ctx.fillRect(0,0,320,180);
  ctx.fillStyle = '#ffb000';
  ctx.beginPath(); ctx.arc(40 + (ms/5) % 240, 90, 28, 0, Math.PI*2); ctx.fill();
};
window.__snapgentSeek(0);
</script></body></html>"""


def main():
    adb('forward', f'tcp:{LOCAL_PORT}', f'tcp:{REMOTE_PORT}')
    ws = WS()
    counter = 500

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
        assert greeting['servers'][0]['tools'] == 65, greeting['servers']
        print('Catalog: 65 Android tools OK')

        # MediaCodec start-up plus per-frame encode easily exceeds the 8 s
        # default socket timeout used for the cheap catalog calls.
        ws.sock.settimeout(240)

        info = json.loads(call('render_info'))
        assert 'webview' in info and 'encoders' in info
        print('render_info:', info['webview'])

        still = json.loads(call('render_still', {
            'html': HTML, 'width': 320, 'height': 180,
            'output': 'render/test-still.png',
        }))
        assert still['bytes'] > 0, still
        assert still['format'] == 'png'
        print('render_still:', still['output'], still['bytes'], 'bytes')

        video = json.loads(call('render_video', {
            'html': HTML, 'width': 320, 'height': 180,
            'fps': 10, 'duration_ms': 500, 'wait_ms': 20,
            'output': 'render/test-video.mp4',
        }))
        assert video['bytes'] > 0, video
        assert video['format'] == 'mp4'
        print('render_video:', video['output'], video['bytes'], 'bytes')

        listing = json.loads(call('list_files', {'path': 'render'}))
        assert isinstance(listing, list), listing
        names = {entry.get('name') for entry in listing}
        assert 'test-still.png' in names, names
        assert 'test-video.mp4' in names, names
        print('outputs present:', sorted(names))
        print('Render tools OK')
    finally:
        try:
            ws.sock.close()
        except Exception:
            pass


if __name__ == '__main__':
    main()
