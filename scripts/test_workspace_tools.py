#!/usr/bin/env python3
"""End-to-end checks for Android workspace MCP tools over ADB WebSocket."""
import json
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_snapgent_ws import WS, adb, LOCAL_PORT, REMOTE_PORT


def main():
    adb('forward', f'tcp:{LOCAL_PORT}', f'tcp:{REMOTE_PORT}')
    ws = WS()
    counter = 100
    prefix = '__snapgent_e2e__'
    try:
        greeting = ws.receive()
        assert greeting['type'] == 'connected'
        assert greeting['servers'][0]['tools'] == 29, greeting['servers']
        print('Catalog: 29 Android tools OK')

        def call(name, args=None, expect_ok=True):
            nonlocal counter
            counter += 1
            response = ws.call(counter, 'call_tool', server='android', name=name, arguments=args or {})
            if response.get('ok') is not expect_ok:
                raise AssertionError(f'{name} expected ok={expect_ok}: {response}')
            return response.get('text', response.get('error', ''))

        for name in ('get_workspace_info', 'get_project_info'):
            assert json.loads(call(name))
            print(name, 'OK')

        try:
            print('create_directory:', call('create_directory', {'path': prefix}))
            base = prefix + '/sample.txt'
            text = 'alpha\nbeta\nclass Widget {\nfunction run() {\n}\n}\n'
            assert 'Created' in call('create_file', {'path': base, 'content': text})
            call('create_file', {'path': base, 'content': 'should fail'}, expect_ok=False)
            assert any(f['name'] == 'sample.txt' for f in json.loads(call('list_dir', {'path': prefix})))
            assert any(f['path'] == base for f in json.loads(call('list_tree', {'path': prefix, 'max_depth': 2})))
            assert base in json.loads(call('find_files', {'path': prefix, 'pattern': '*.txt'}))
            assert any(x['line'] == 1 for x in json.loads(call('search_files', {'path': prefix, 'query': 'ALPHA'})))
            assert '1: alpha' in call('read_file_lines', {'path': base, 'start_line': 1, 'end_line': 2})
            assert json.loads(call('read_files', {'paths': [base]}))[0]['content'] == text
            assert json.loads(call('get_file_info', {'path': base}))['type'] == 'file'
            assert any(x['name'] == 'Widget' for x in json.loads(call('get_file_outline', {'path': base})))
            print('list/search/read/info/outline OK')

            call('edit_file', {'path': base, 'old_text': 'alpha', 'new_text': 'gamma'})
            call('apply_patch', {'path': base, 'edits': [{'old_text': 'beta', 'new_text': 'delta'}]})
            result = call('read_file', {'path': base})
            assert 'gamma\ndelta' in result, result
            print('edit_file/apply_patch OK')

            call('copy_file', {'source': base, 'destination': prefix + '/copy.txt'})
            call('move_file', {'source': prefix + '/copy.txt', 'destination': prefix + '/moved.txt'})
            assert 'gamma' in call('read_file', {'path': prefix + '/moved.txt'})
            call('delete_file', {'path': prefix + '/moved.txt'})
            call('delete_file', {'path': base})
            print('copy/move/delete OK')

            call('read_file', {'path': '../shared_prefs'}, expect_ok=False)
            call('create_file', {'path': '../outside.txt', 'content': 'blocked'}, expect_ok=False)
            call('delete_file', {'path': prefix}, expect_ok=False)
            print('Path traversal and directory deletion blocked OK')
        finally:
            # Delete only the test directory created by this test, not user files.
            adb('shell', 'run-as', 'dev.snapgent.bridge', 'rmdir', 'files/' + prefix)
        print('RESULT: PASS (all tested tools)')
    finally:
        ws.close()
        adb('forward', '--remove', f'tcp:{LOCAL_PORT}')


if __name__ == '__main__':
    main()
