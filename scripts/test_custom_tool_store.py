#!/usr/bin/env python3
"""Proves custom tool definitions live as files and survive an app restart.

Tool definitions used to be one JSON string inside the preferences file, so a
single bad write (or a settings reset) could take every tool with it. They now
live in `custom-tools/`, one file per tool, inside the workspace root.

This test checks the whole contract on a real device:

  1. `tool_create` writes `custom-tools/<name>.json`.
  2. The file is plain JSON the Files page can read.
  3. The tool still exists and still runs after the app process is restarted.
  4. A definition dropped into the folder by hand becomes a working tool.
  5. `tool_delete` removes the file.

Run with the phone connected over ADB and the bridge service up:

    python3 scripts/test_custom_tool_store.py
"""
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_snapgent_ws import WS, adb, LOCAL_PORT, REMOTE_PORT

SERIAL = '59f6c411'
CREATED = 'store_probe'
HANDWRITTEN = 'manual_probe'
DIR = 'custom-tools'


def dir_names(payload):
    """`list_dir` answers with an array of entries; tolerate a wrapped object too."""
    data = json.loads(payload)
    if isinstance(data, dict):
        data = data.get('entries', [])
    return [entry['name'] for entry in data]


def connect():
    ws = WS()
    ws.sock.settimeout(120)
    greeting = ws.receive()
    assert greeting['type'] == 'connected', greeting
    return ws, greeting


def main():
    adb('forward', f'tcp:{LOCAL_PORT}', f'tcp:{REMOTE_PORT}')
    ws, greeting = connect()
    counter = 700

    def call(name, args=None):
        nonlocal counter
        counter += 1
        return ws.call(counter, 'call_tool', server='android', name=name, arguments=args or {})

    def ok(name, args=None):
        response = call(name, args)
        assert response.get('ok') is True, f'{name}: {response}'
        return response.get('text', '')

    try:
        print('bridge:', greeting.get('bridge_version'))

        listing = json.loads(ok('tool_list'))
        assert listing['custom_tools_dir'] == DIR, listing.get('custom_tools_dir')
        for stale in (CREATED, HANDWRITTEN):
            if stale in listing['custom_tools_files']:
                ok('tool_delete', {'name': stale})
        print('custom tools directory:', listing['custom_tools_dir'])

        ok('tool_create', {
            'name': CREATED,
            'description': 'Proves a definition is a file on disk',
            'kind': 'python',
            'code': "print('stored ' + ARGS.get('word', 'nothing'))",
            'params': [{'name': 'word', 'description': 'Echoed back', 'required': True}],
        })
        names = dir_names(ok('list_dir', {'path': DIR}))
        assert f'{CREATED}.json' in names, names
        print('tool_create wrote:', f'{DIR}/{CREATED}.json')

        text = ok('read_file_lines', {'path': f'{DIR}/{CREATED}.json'})
        assert CREATED in text and 'python' in text, text[:200]
        assert 'stored' in text, text[:200]
        print('definition file is readable JSON with the python source')

        # Restart the process: a tool kept only in memory or in a stale cache would die here.
        adb('shell', 'am', 'force-stop', 'dev.snapgent.bridge')
        time.sleep(1)
        adb('shell', 'am', 'start', '-n', 'dev.snapgent.bridge/.MainActivity')
        for _ in range(20):
            time.sleep(1)
            try:
                ws.close()
            except Exception:
                pass
            try:
                ws, greeting = connect()
                break
            except Exception:
                continue
        else:
            raise AssertionError('bridge did not come back after restart')

        listing = json.loads(ok('tool_list'))
        assert CREATED in listing['custom_tools_files'], listing['custom_tools_files']
        assert any(tool['name'] == CREATED for tool in listing['tools']), listing['tools']
        assert 'stored restart' in ok(CREATED, {'word': 'restart'})
        print('definition survived an app restart and still runs')

        # A file dropped in by hand is a tool: this is how a definition comes back
        # after a phone reset, or how one created elsewhere gets imported.
        hand = json.dumps({
            'name': HANDWRITTEN,
            'description': 'Written into the folder without the tool API',
            'kind': 'python',
            'code': "print('hand ' + ARGS.get('word', 'none'))",
            'params': [{'name': 'word', 'description': 'Echoed back', 'required': True}],
        }, indent=2)
        import base64
        ok('write_file_base64', {
            'path': f'{DIR}/{HANDWRITTEN}.json',
            'data': base64.b64encode(hand.encode()).decode(),
        })
        listing = json.loads(ok('tool_list'))
        assert HANDWRITTEN in listing['custom_tools_files'], listing['custom_tools_files']
        assert 'hand file' in ok(HANDWRITTEN, {'word': 'file'})
        print('a definition written by hand is picked up and callable')

        ok('tool_delete', {'name': CREATED})
        ok('tool_delete', {'name': HANDWRITTEN})
        remaining = dir_names(ok('list_dir', {'path': DIR}))
        assert f'{CREATED}.json' not in remaining, remaining
        assert f'{HANDWRITTEN}.json' not in remaining, remaining
        listing = json.loads(ok('tool_list'))
        assert listing['custom_tools_files'] == [], listing['custom_tools_files']
        print('tool_delete removed both files')

        print('RESULT: PASS (custom tools are files and survive a restart)')
    finally:
        try:
            ws.close()
        finally:
            adb('forward', '--remove', f'tcp:{LOCAL_PORT}')


if __name__ == '__main__':
    main()
