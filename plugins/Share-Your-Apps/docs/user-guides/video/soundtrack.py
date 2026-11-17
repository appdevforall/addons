import sys, json, subprocess; sys.path.insert(0, '/tmp/video'); import scenes
from script import ts
def build(which, mp3, video, out, stem):
    segs = json.load(open(f'/tmp/video/{which}-segs.json')); starts, total = scenes.timed(which, segs); S = getattr(scenes, which)
    parts = [f"[0:a]atrim=start={a}:end={b},asetpts=PTS-STARTPTS,afade=t=in:d=0.02,afade=t=out:st={max(b - a - 0.06, 0):.3f}:d=0.06,adelay={int(st * 1000)}:all=1[a{k}]"
             for k, ((a, b), st) in enumerate(zip(segs, starts))]
    mix = ''.join(f'[a{k}]' for k in range(len(segs))) + f"amix=inputs={len(segs)}:normalize=0,apad,atrim=end={total}[mix]"
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', mp3, '-filter_complex', ';'.join(parts + [mix]), '-map', '[mix]', '-c:a', 'pcm_s16le', f'/tmp/video/{which}.wav'], check=True)
    # Subtitles, timed to the voice: each line from its start to its end in the video.
    rows = [(st, st + (b - a), sc[4]) for (a, b), st, sc in zip(segs, starts, S)]
    with open(f'{stem}.srt', 'w') as f:
        for n, (a, b, t) in enumerate(rows, 1): f.write(f"{n}\n{ts(a)} --> {ts(b)}\n{t}\n\n")
    with open(f'{stem}.vtt', 'w') as f:
        f.write("WEBVTT\n\n")
        for a, b, t in rows: f.write(f"{ts(a, '.')} --> {ts(b, '.')}\n{t}\n\n")
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', video, '-i', f'/tmp/video/{which}.wav', '-i', f'{stem}.srt',
                    '-map', '0:v', '-map', '1:a', '-map', '2', '-c:v', 'copy', '-c:a', 'aac', '-b:a', '160k', '-c:s', 'mov_text',
                    '-metadata:s:a:0', 'language=eng', '-metadata:s:s:0', 'language=eng', '-metadata:s:s:0', 'title=Narration',
                    '-t', f'{total}', '-movflags', '+faststart', out], check=True)
    return total, rows
if __name__ == '__main__':
    which, mp3, video, out, stem = sys.argv[1:6]
    total, rows = build(which, mp3, video, out, stem); print(which, total, 's,', len(rows), 'lines placed')
