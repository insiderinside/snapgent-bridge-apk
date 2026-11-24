#!/usr/bin/env python3
"""Bandingkan word timestamps dengan pencocokan teks (difflib), bukan urutan mentah."""
import difflib
import json
import sys


def load(path):
    d = json.load(open(path))
    words = []
    for seg in d["segments"]:
        for w in seg.get("words", []):
            words.append((w["word"].strip().lower(), float(w["start"]), float(w["end"])))
    return d, words


def stats(name, xs):
    xs = sorted(xs)
    p = lambda q: xs[min(len(xs) - 1, int(q * len(xs)))]
    print(f"{name}: median {p(0.5):.3f}s  p90 {p(0.9):.3f}s  p99 {p(0.99):.3f}s  "
          f"max {xs[-1]:.3f}s  <=0.2s {100*sum(1 for x in xs if x <= 0.2)/len(xs):.1f}%")


def main(ref_path, hyp_path):
    ref_d, ref = load(ref_path)
    hyp_d, hyp = load(hyp_path)
    print(f"ground truth : {len(ref)} kata, {len(ref_d['segments'])} segmen")
    print(f"android ct2  : {len(hyp)} kata, {len(hyp_d['segments'])} segmen")

    sm = difflib.SequenceMatcher(a=[w[0] for w in ref], b=[w[0] for w in hyp], autojunk=False)
    pairs = []
    for a, b, size in sm.get_matching_blocks():
        for k in range(size):
            pairs.append((ref[a + k], hyp[b + k]))

    print(f"kata cocok: {len(pairs)}  (precision {100*len(pairs)/max(1,len(hyp)):.1f}%, "
          f"recall {100*len(pairs)/max(1,len(ref)):.1f}%)")
    if not pairs:
        return 1

    stats("selisih start", [abs(r[1] - h[1]) for r, h in pairs])
    stats("selisih end  ", [abs(r[2] - h[2]) for r, h in pairs])

    bad = sorted(pairs, key=lambda p: -abs(p[0][1] - p[1][1]))[:10]
    print("\n10 kata dengan selisih start terbesar:")
    for r, h in bad:
        print(f"  {r[0]:<16} ref {r[1]:6.2f}-{r[2]:6.2f}  android {h[1]:6.2f}-{h[2]:6.2f}  "
              f"delta {abs(r[1]-h[1]):5.2f}")
    return 0


if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:3]))
