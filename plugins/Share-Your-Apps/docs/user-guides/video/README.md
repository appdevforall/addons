# The demo videos

- `scenes.py` — both videos' scenes: the screen, the callouts to zoom to, the titles, and the narration. Each scene lasts as long as its narration takes at 144 words a minute, plus a pause.
- `render.py` — draws every frame from the annotated screens in `../screens/`, and encodes them with ffmpeg: `python3 render.py LONG share-your-apps-demo.mp4`, or `SHORT`.
- `script.py` — writes the narration files, timed to the videos: a readable script, SRT and VTT subtitles, JSON, and plain lines.

- `slow.sh` — slows a recording, keeping its pitch, with ffmpeg's atempo: `sh slow.sh recording.mp3 slowed.wav 0.9`. Run it before `align.py`, and use the slowed file from then on.
- `align.py` — matches a recorded narration's lines to the scenes, from the recording's pauses, choosing all the boundaries together so every line fits one speaking rate: `python3 align.py dp`.
- `soundtrack.py` — re-times each scene to its recorded line, places each line at its scene's start, re-times the subtitles, and combines picture, voice and subtitles.

With a recording: `python3 align.py dp`, then `python3 render.py LONG picture.mp4 LONG-segs.json`, then `python3 soundtrack.py LONG recording.mp3 picture.mp4 share-your-apps-demo.mp4 share-your-apps-demo-narration`. The same with `SHORT` for the short video.

The scripts were written for the machine that rendered the videos; their paths to the screens, fonts and callout tool are at their tops.
