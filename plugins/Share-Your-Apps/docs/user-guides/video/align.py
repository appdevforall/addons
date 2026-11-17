import subprocess, re, sys, json
sys.path.insert(0, '/tmp/video'); import scenes
def silences(path, d=0.3):
    out = subprocess.run(['ffmpeg', '-v', 'info', '-i', path, '-af', f'silencedetect=noise=-35dB:d={d}', '-f', 'null', '-'], capture_output=True, text=True).stderr
    s = [float(x) for x in re.findall(r'silence_start: ([0-9.]+)', out)]; e = [float(x) for x in re.findall(r'silence_end: ([0-9.]+)', out)]
    return list(zip(s, e))
def duration(path):
    return float(subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', path], capture_output=True, text=True).stdout)
def align(path, which):
    S = getattr(scenes, which); words = [len(s[4].split()) for s in S]; total = duration(path); sil = silences(path)
    start = sil[0][1] if sil and sil[0][0] < 0.05 else 0.0; end = sil[-1][0] if sil and sil[-1][1] > total - 0.05 else total
    cum = 0; bounds = []; used = set()
    for k in range(len(S) - 1):
        cum += words[k]; expect = start + (end - start) * cum / sum(words)
        cands = [i for i, (a, b) in enumerate(sil) if i not in used and a > (sil[bounds[-1]][1] if bounds else start)]
        i = min(cands, key=lambda i: abs((sil[i][0] + sil[i][1]) / 2 - expect)); bounds.append(i); used.add(i)
    segs = []; a = start
    for k in range(len(S)):
        b = sil[bounds[k]][0] if k < len(S) - 1 else end
        segs.append((round(max(a - 0.04, 0), 3), round(min(b + 0.08, total), 3)))
        if k < len(S) - 1: a = sil[bounds[k]][1]
    return segs
if __name__ == '__main__' and 'dp' not in sys.argv:
    for which, f in (('SHORT', 'share_Your_Apps_promo'), ('LONG', 'Share_Your_Apps_demo')):
        segs = align(f'/mnt/user-data/uploads/{f}.mp3', which); S = getattr(scenes, which)
        json.dump(segs, open(f'/tmp/video/{which}-segs.json', 'w'))
        print(f"== {which}: {len(segs)} lines")
        for (a, b), s in zip(segs, S):
            w = len(s[4].split()); print(f"  {a:7.2f}-{b:7.2f}  {b-a:5.2f}s  {w:2d} words  {w/(b-a)*60:4.0f} wpm  {s[4][:60]}")

def align_dp(path, which):
    """Choose all line boundaries at once, from the recording's pauses, so every line's length best fits one speaking rate."""
    S = getattr(scenes, which); words = [len(s[4].split()) for s in S]; L = len(S)
    total = duration(path); sil = silences(path, 0.25)
    start = sil[0][1] if sil and sil[0][0] < 0.05 else 0.0; end = sil[-1][0] if sil and sil[-1][1] > total - 0.05 else total
    inner = [(a, b) for a, b in sil if a > start + 0.05 and b < end - 0.05]
    pts = [(None, start)] + inner + [(end, None)]           # boundary k: speech ends at pts[k][0], next starts at pts[k][1]
    spw = (end - start) / sum(words)
    for _ in range(4):
        INF = float('inf'); M = len(pts)
        dp = [[INF] * M for _ in range(L + 1)]; back = [[-1] * M for _ in range(L + 1)]; dp[0][0] = 0
        for k in range(1, L + 1):
            for j in range(1, M):
                if k == L and j != M - 1: continue
                if k < L and j == M - 1: continue
                for i in range(j):
                    if dp[k - 1][i] == INF: continue
                    d = pts[j][0] - pts[i][1]
                    if d <= 0: continue
                    c = dp[k - 1][i] + ((d - words[k - 1] * spw) ** 2) / (words[k - 1] * spw)
                    if c < dp[k][j]: dp[k][j] = c; back[k][j] = i
        js = [M - 1]
        for k in range(L, 0, -1): js.append(back[k][js[-1]])
        js = js[::-1]
        segs = [(pts[js[k]][1], pts[js[k + 1]][0]) for k in range(L)]
        spw = sum(b - a for a, b in segs) / sum(words)
    return [(round(max(a - 0.04, 0), 3), round(min(b + 0.08, total), 3)) for a, b in segs], spw

if __name__ == '__main__' and 'dp' in sys.argv:
    for which, f in (('SHORT', 'share_Your_Apps_promo'), ('LONG', 'Share_Your_Apps_demo')):
        segs, spw = align_dp(f'/mnt/user-data/uploads/{f}.mp3', which); S = getattr(scenes, which)
        json.dump(segs, open(f'/tmp/video/{which}-segs.json', 'w'))
        rates = [len(s[4].split()) / (b - a) * 60 for (a, b), s in zip(segs, S)]
        print(f"== {which}: {len(segs)} lines, overall {60/spw:.0f} wpm, lines from {min(rates):.0f} to {max(rates):.0f} wpm")
        for (a, b), s, r in zip(segs, S, rates): print(f"  {a:7.2f}-{b:7.2f}  {b-a:5.2f}s  {r:4.0f} wpm  {s[4][:58]}")
