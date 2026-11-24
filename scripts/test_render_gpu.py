#!/usr/bin/env python3
"""Compare the GPU and software capture paths on a real device.

`render_still` has two capture paths:

  * render_mode=gpu      — hardware acceleration stays on, so WebGL really runs on
                           the GPU; the frame is read back from a RenderNode or a
                           virtual display.
  * render_mode=software — the legacy path that forces LAYER_TYPE_SOFTWARE and
                           draws the WebView into a software bitmap, which is what
                           used to flatten WebGL scenes.

The scene below is a raw WebGL fragment shader, so a capture that skipped the GPU
comes back with far fewer colours. The test pulls both PNGs and compares them.

Run with the phone connected over ADB and the bridge service up:

    python3 scripts/test_render_gpu.py
"""
import json
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_snapgent_ws import WS, adb, LOCAL_PORT, REMOTE_PORT

SERIAL = '59f6c411'
OUT_DIR = Path(__file__).resolve().parent.parent / 'build' / 'render-gpu-test'

# A screen-filling triangle shaded by an animated fragment shader plus a spinning
# quad: colourful, geometry-heavy and cheap to seek, with no CDN dependency.
HTML = """<!doctype html><html><head><meta charset="utf-8"><style>
html,body{margin:0;background:#05060a;overflow:hidden}canvas{display:block}
</style></head><body><canvas id="c" width="640" height="360"></canvas><script>
var cvs = document.getElementById('c');
var gl = cvs.getContext('webgl', {antialias: true});
var program = null, buffer = null, tUniform = null;
function compile(type, src) {
  var s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s);
  if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s));
  return s;
}
if (gl) {
  program = gl.createProgram();
  gl.attachShader(program, compile(gl.VERTEX_SHADER,
    'attribute vec2 p;void main(){gl_Position=vec4(p,0.0,1.0);}'));
  gl.attachShader(program, compile(gl.FRAGMENT_SHADER,
    'precision highp float;uniform float t;uniform vec2 res;'
    + 'void main(){vec2 u=gl_FragCoord.xy/res;'
    + 'float a=sin(u.x*22.0+t*2.0)*cos(u.y*17.0-t*1.4);'
    + 'float b=sin((u.x+u.y)*13.0+t*0.9)+cos(length(u-0.5)*26.0-t*1.7);'
    + 'vec3 col=vec3(0.5+0.5*sin(a*3.0+t),0.5+0.5*cos(b*2.0-t*1.3),0.5+0.5*sin(a*b+t*0.6));'
    + 'gl_FragColor=vec4(col,1.0);}'));
  gl.linkProgram(program); gl.useProgram(program);
  buffer = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1,-1, 3,-1, -1,3]), gl.STATIC_DRAW);
  var loc = gl.getAttribLocation(program, 'p');
  gl.enableVertexAttribArray(loc); gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);
  tUniform = gl.getUniformLocation(program, 't');
  gl.uniform2f(gl.getUniformLocation(program, 'res'), cvs.width, cvs.height);
}
window.__snapgentSeek = function (ms) {
  if (!gl) { document.body.style.background = '#ff00ff'; return; }
  gl.viewport(0, 0, cvs.width, cvs.height);
  gl.uniform1f(tUniform, ms / 1000.0);
  gl.clearColor(0.05, 0.06, 0.10, 1.0);
  gl.clear(gl.COLOR_BUFFER_BIT);
  gl.drawArrays(gl.TRIANGLES, 0, 3);
};
window.__snapgentSeek(400);
</script></body></html>"""


def stats(path):
    from PIL import Image
    image = Image.open(path).convert('RGB')
    colors = image.getcolors(maxcolors=1 << 24) or []
    total = image.width * image.height
    background = (5, 6, 10)
    flat = image.resize((160, 90))
    pixels = list(flat.getdata())
    non_background = sum(1 for p in pixels if p != background)
    histogram = image.histogram()
    entropy = 0.0
    for count in histogram:
        if count:
            p = count / total
            entropy -= p * __import__('math').log2(p)
    dominant = max(colors, key=lambda c: c[0])[:2] if colors else (0, None)
    return {
        'unique_colors': len(colors),
        'non_background_ratio': round(non_background / len(pixels), 3),
        'entropy_bits': round(entropy, 2),
        'dominant_share': round(dominant[0] / total, 3) if colors else 1.0,
        'dominant_color': dominant[1],
    }


def main():
    adb('forward', f'tcp:{LOCAL_PORT}', f'tcp:{REMOTE_PORT}')
    ws = WS()
    counter = 900
    ws.sock.settimeout(180)

    def call(name, args=None):
        nonlocal counter
        counter += 1
        response = ws.call(counter, 'call_tool', server='android', name=name, arguments=args or {})
        if not response.get('ok'):
            raise AssertionError(f'{name} failed: {response}')
        return response.get('text', '')

    greeting = ws.receive()
    assert greeting['type'] == 'connected', greeting
    print('bridge:', greeting.get('bridge_version', '?'))

    info = json.loads(call('render_info'))
    print('webview:', info.get('webview'))
    print('webgl (gpu probe):', info.get('webgl'))
    print('overlay allowed:', info.get('overlayAllowed'))

    def pull(remote_path, local_path):
        """Read a PNG back through read_file_base64: the release APK is not debuggable,
        so `run-as` is unavailable and app-private storage cannot be read directly."""
        payload = json.loads(call('read_file_base64', {'path': remote_path}))
        data = __import__('base64').b64decode(payload['data'])
        Path(local_path).write_bytes(data)
        assert len(data) == payload['bytes'], (len(data), payload['bytes'])

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    results = {}
    for mode in ('gpu', 'software'):
        payload = json.loads(call('render_still', {
            'html': HTML,
            'width': 640,
            'height': 360,
            'seek_ms': 900,
            'wait_ms': 400,
            'render_mode': mode,
            'output': f'render/gpu-test-{mode}.png',
        }))
        local = OUT_DIR / f'{mode}.png'
        pull(payload['output'], local)
        results[mode] = {'payload': payload, 'stats': stats(local), 'png': str(local)}
        print(f"\nrender_still mode={mode}")
        print('  reported render_mode:', payload.get('render_mode'),
              '| requested:', payload.get('requested_mode'),
              '| webgl:', payload.get('webgl'))
        print('  bytes:', payload.get('bytes'), '|', results[mode]['stats'])

    gpu, software = results['gpu']['stats'], results['software']['stats']
    print('\ncomparison')
    print(f"  unique colours   gpu={gpu['unique_colors']:>7}  software={software['unique_colors']:>7}")
    print(f"  entropy (bits)   gpu={gpu['entropy_bits']:>7}  software={software['entropy_bits']:>7}")
    print(f"  non-background   gpu={gpu['non_background_ratio']:>7}  software={software['non_background_ratio']:>7}")
    print(f"  dominant share   gpu={gpu['dominant_share']:>7}  software={software['dominant_share']:>7}")

    richer = gpu['unique_colors'] > software['unique_colors'] * 2
    print('\nGPU is at least twice as colourful (the point of the change):', richer)
    if not richer:
        print('GPU capture did not beat the software path — do not ship this.', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
