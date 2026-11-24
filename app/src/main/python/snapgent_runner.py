"""Entry point for the Snapgent Bridge Python tools.

Chaquopy owns the interpreter; this module only exposes a small, controlled
exec() surface so the Java side never has to build code strings itself.
Everything is returned as a JSON string because that crosses the
Java <-> Python bridge without extra marshalling.
"""

import io
import json
import os
import platform
import sys
import traceback

MAX_SOURCE_BYTES = 512 * 1024


class _Capture(io.StringIO):
    """StringIO with a byte budget so a runaway script cannot fill the heap."""

    def __init__(self, limit):
        super().__init__()
        self._limit = max(int(limit), 1024)
        self.truncated = False

    def write(self, text):
        if self.tell() >= self._limit:
            self.truncated = True
            return len(text)
        room = self._limit - self.tell()
        if len(text) > room:
            self.truncated = True
            text = text[:room]
        return super().write(text)


def _json(payload):
    return json.dumps(payload, ensure_ascii=False)


def _error(message):
    return _json({
        "ok": False,
        "exit_code": 1,
        "output": message,
        "truncated": False,
    })


def execute(source, filename="<snapgent>", cwd="", max_output=65536, argv=None):
    """Run a Python source string and return a JSON result object."""
    if not isinstance(source, str) or not source.strip():
        return _error("run_python needs a non-empty 'code' or 'path'")
    if len(source.encode("utf-8")) > MAX_SOURCE_BYTES:
        return _error("Python source exceeds %d bytes" % MAX_SOURCE_BYTES)

    stdout = _Capture(max_output)
    stderr = _Capture(max_output)
    saved_stdout, saved_stderr = sys.stdout, sys.stderr
    saved_argv = sys.argv
    saved_cwd = os.getcwd()
    namespace = {
        "__name__": "__main__",
        "__file__": filename or "<snapgent>",
        "__builtins__": __builtins__,
    }
    exit_code = 0
    changed_dir = False
    try:
        if cwd:
            os.chdir(cwd)
            changed_dir = True
        sys.stdout, sys.stderr = stdout, stderr
        sys.argv = [filename or "<snapgent>"] + [str(a) for a in (argv or [])]
        compiled = compile(source, filename or "<snapgent>", "exec")
        exec(compiled, namespace)  # noqa: S102 - deliberate, sandboxed to app storage
    except SystemExit as exc:
        code = exc.code
        exit_code = code if isinstance(code, int) else (0 if code is None else 1)
    except BaseException:  # noqa: BLE001 - must report every failure back to MCP
        exit_code = 1
        stderr.write(traceback.format_exc())
    finally:
        sys.stdout, sys.stderr = saved_stdout, saved_stderr
        sys.argv = saved_argv
        if changed_dir:
            try:
                os.chdir(saved_cwd)
            except OSError:
                pass

    output = stdout.getvalue()
    if stderr.getvalue():
        output = output + ("\n" if output and not output.endswith("\n") else "") + stderr.getvalue()

    return _json({
        "ok": exit_code == 0,
        "exit_code": exit_code,
        "output": output,
        "truncated": bool(stdout.truncated or stderr.truncated),
    })


def info():
    """Describe the embedded interpreter for the python_info tool."""
    packages = []
    try:
        from importlib import metadata

        for dist in metadata.distributions():
            name = dist.metadata.get("Name")
            if name:
                packages.append(name)
    except Exception:  # noqa: BLE001 - metadata is best effort only
        packages = []
    return _json({
        "ok": True,
        "version": sys.version.split()[0],
        "implementation": platform.python_implementation(),
        "platform": sys.platform,
        "machine": platform.machine(),
        "executable": sys.executable,
        "prefix": sys.prefix,
        "packages": sorted(set(packages)),
    })
