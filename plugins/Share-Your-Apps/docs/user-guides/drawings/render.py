import os, subprocess, json, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shots_plugin, shots_server
ALL = shots_plugin.SHOTS + shots_server.SHOTS
HERE = os.path.dirname(os.path.abspath(__file__)); os.makedirs(HERE + '/html', exist_ok=True); os.makedirs(HERE + '/png', exist_ok=True)
only = sys.argv[1:]
for s in ALL:
    if only and s['id'] not in only: continue
    h = HERE + f"/html/{s['id']}.html"; open(h, 'w').write(s['html'])
    subprocess.run(['xvfb-run', '-a', 'python3', '/tmp/shot.py', 'file://' + h, HERE + f"/png/{s['id']}.png", '390', '1300'],
                   env={**os.environ, 'ZOOM': '2'}, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=90)
json.dump([{k: v for k, v in s.items() if k != 'html'} for s in ALL], open(HERE + '/shots.json', 'w'), indent=1)
print(len(ALL), 'shots')
