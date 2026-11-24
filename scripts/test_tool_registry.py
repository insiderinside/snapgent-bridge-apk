#!/usr/bin/env python3
"""End-to-end checks for the on-device tool registry and the binary file tools.

Covers: tool_list, python custom tools (argument injection + error paths),
on/off switches that hide a tool and refuse calls, deletion, and the
write_file_base64/read_file_base64 pair used to push voice-overs to the phone.
"""
import base64
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_snapgent_ws import WS, adb, LOCAL_PORT, REMOTE_PORT

CUSTOM = 'e2e_custom_tool'
BAD_TOOL = 'e2e_bad_http'
BINARY = '__snapgent_registry__.wav'


def main():
    adb('forward', f'tcp:{LOCAL_PORT}', f'tcp:{REMOTE_PORT}')
    ws = WS()
    counter = 200
    try:
        greeting = ws.receive()
        assert greeting['type'] == 'connected', greeting
        assert greeting['servers'][0]['tools'] == 53, greeting['servers']
        print('Catalog: 53 Android tools OK')

        def call(name, args=None):
            nonlocal counter
            counter += 1
            return ws.call(counter, 'call_tool', server='android', name=name, arguments=args or {})

        def ok(name, args=None):
            response = call(name, args)
            assert response.get('ok') is True, f'{name}: {response}'
            return response.get('text', '')

        def fail(name, args=None, contains=None):
            response = call(name, args)
            assert response.get('ok') is False, f'{name} should fail: {response}'
            error = response.get('error', '')
            if contains:
                assert contains in error, f'{name}: unexpected error {error!r}'
            return error

        listing = json.loads(ok('tool_list'))
        assert listing['count'] == 53, listing['count']
        assert any(tool['name'] == 'tool_create' for tool in listing['tools'])
        print('tool_list:', listing['count'], 'tools OK')

        created = json.loads(ok('tool_create', {
            'name': CUSTOM,
            'description': 'Echo the topic argument from the embedded interpreter',
            'kind': 'python',
            'code': "print('hello ' + ARGS.get('topic', 'nobody'))",
            'params': [{'name': 'topic', 'description': 'Word to greet', 'required': True}],
        }))
        assert created['created'] is True, created
        assert 'hello snapgent' in ok(CUSTOM, {'topic': 'snapgent'})
        print('tool_create python + ARGS injection OK')

        fail('tool_create', {'name': 'Bad Name', 'description': 'x', 'kind': 'python', 'code': 'pass'},
             contains='Invalid tool name')
        fail('tool_create', {'name': 'read_file', 'description': 'x', 'kind': 'python', 'code': 'pass'},
             contains='reserved')
        fail('tool_create', {'name': BAD_TOOL, 'description': 'x', 'kind': 'http', 'url': 'ftp://example.com'},
             contains='http')
        print('tool_create validation (name, reserved, url scheme) OK')

        json.loads(ok('tool_set_enabled', {'name': CUSTOM, 'enabled': False}))
        listing = json.loads(ok('tool_list'))
        entry = next(tool for tool in listing['tools'] if tool['name'] == CUSTOM)
        assert entry['enabled'] is False, entry
        fail(CUSTOM, {'topic': 'nobody'}, contains='switched off')
        json.loads(ok('tool_set_enabled', {'name': CUSTOM, 'enabled': True}))
        assert 'hello again' in ok(CUSTOM, {'topic': 'again'})
        print('tool switch hides and refuses calls OK')

        json.loads(ok('tool_delete', {'name': CUSTOM}))
        fail(CUSTOM, {'topic': 'gone'}, contains='Unknown tool')
        listing = json.loads(ok('tool_list'))
        assert all(tool['name'] != CUSTOM for tool in listing['tools'])
        print('tool_delete OK')

        payload = bytes(range(256)) * 3
        saved = json.loads(ok('write_file_base64', {
            'path': BINARY, 'data': base64.b64encode(payload).decode()}))
        assert saved['bytes'] == len(payload), saved
        read = json.loads(ok('read_file_base64', {'path': BINARY}))
        assert base64.b64decode(read['data']) == payload
        ok('write_file_base64', {'path': BINARY, 'data': base64.b64encode(b'TAIL').decode(), 'append': True})
        tail = base64.b64decode(json.loads(ok('read_file_base64', {'path': BINARY}))['data'])
        assert tail == payload + b'TAIL', (len(tail), len(payload))
        fail('write_file_base64', {'path': BINARY, 'data': 'not-base64!!'}, contains='base64')
        ok('delete_file', {'path': BINARY})
        print('write_file_base64 / read_file_base64 (round trip + append) OK')

        print('RESULT: PASS (tool registry and binary files)')
    finally:
        ws.close()
        adb('forward', '--remove', f'tcp:{LOCAL_PORT}')


if __name__ == '__main__':
    main()
