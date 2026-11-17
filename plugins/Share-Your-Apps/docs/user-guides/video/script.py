import sys, json; sys.path.insert(0, '/tmp/video'); import scenes
LEAD = 0.3; TAIL = 0.4
def ts(t, sep=','):
    h, r = divmod(t, 3600); m, s = divmod(r, 60); return f"{int(h):02d}:{int(m):02d}:{int(s):02d}{sep}{int(round((s % 1) * 1000)):03d}"
def build(which, stem, title):
    S = getattr(scenes, which); t = 0; rows = []
    for n, (sid, co, part, head, text) in enumerate(S, 1):
        d = scenes.duration(text); a = t + LEAD; b = t + d - TAIL
        rows.append(dict(scene=n, start=round(a, 2), end=round(b, 2), max_seconds=round(b - a, 2), words=len(text.split()),
                         on_screen=('Title card' if sid == 'card' else f"Screen {sid}") + f": {head}", part=part, text=text))
        t += d
    total = round(t, 2)
    with open(f'{stem}.srt', 'w') as f:
        for r in rows: f.write(f"{r['scene']}\n{ts(r['start'])} --> {ts(r['end'])}\n{r['text']}\n\n")
    with open(f'{stem}.vtt', 'w') as f:
        f.write("WEBVTT\n\n")
        for r in rows: f.write(f"{ts(r['start'], '.')} --> {ts(r['end'], '.')}\n{r['text']}\n\n")
    json.dump(dict(title=title, duration_seconds=total, words_per_minute=int(scenes.WPS * 60), lines=rows), open(f'{stem}.json', 'w'), indent=1)
    open(f'{stem}-lines.txt', 'w').write('\n'.join(r['text'] for r in rows) + '\n')
    md = [f"# {title}: narration script", "",
          f"Length **{int(total // 60)}:{total % 60:04.1f}**, at about **{int(scenes.WPS * 60)} words a minute**. Each line starts {LEAD} s into its scene, after the fade-in, and must end by its *End* time; the rest of the scene is a pause.", "",
          "**For a voice tool:** import the `.srt` or `.vtt` file, whose timings match the video, or read the lines from the `-lines.txt` file, one per scene. If a generated line runs longer than its *Max*, speed it up slightly, or shorten the pause after it. The `.json` file has the same timings, for tools that take structured input.", "",
          "**Voice:** warm, clear and unhurried, like a friendly guide showing someone an app. Neutral accent. Pause briefly at each full stop.", "",
          "**Pronunciations:** *APK* is spelled out, A-P-K. *PIN* rhymes with *tin*. *Wi-Fi* is *why-fye*. *Code On The Go* is one name, said evenly.", "",
          "| # | Start | End | Max | On screen | Narration |", "|---|---|---|---|---|---|"]
    for r in rows: md.append(f"| {r['scene']} | {ts(r['start'], '.')[3:]} | {ts(r['end'], '.')[3:]} | {r['max_seconds']:.1f} s | {r['on_screen']} | {r['text']} |")
    open(f'{stem}-script.md', 'w').write('\n'.join(md) + '\n')
    return total, len(rows)
if __name__ == '__main__':
    print(build('LONG', '/tmp/video/share-your-apps-demo-narration', 'Share Your Apps demo'))
    print(build('SHORT', '/tmp/video/share-your-apps-demo-short-narration', 'Share Your Apps demo, short'))
