"""Copies this demo's files into index.html, so the page shows exactly the files.

Usage, from anywhere:  python3 sync-page.py [--check]

Each <pre class="script" data-file="PATH"><code>...</code></pre> in index.html gets the
contents of PATH, relative to this folder, HTML-escaped. The files are UTF-8: the scripts have
names such as José Núñez and São Paulo. With --check, nothing is written: the exit status is 1
if the page differs from the files. (Adapted from the Shell Hooks demo's sync-page.py.)
"""

import html
import re
import sys
from pathlib import Path

here = Path(__file__).resolve().parent
page = here / "index.html"
block = re.compile(r'(<pre class="script" data-file="([^"]+)"><code>)(.*?)(</code></pre>)', re.S)


def fill(match: re.Match) -> str:
    text = (here / match.group(2)).read_text(encoding="utf-8")
    return match.group(1) + html.escape(text, quote=False) + match.group(4)


old = page.read_text(encoding="utf-8")
new, count = block.subn(fill, old)
if "--check" in sys.argv[1:]:
    state = "page matches the files" if new == old else "page is out of date"
    print(f"{count} files: {state}")
    sys.exit(0 if new == old else 1)
page.write_text(new, encoding="utf-8")
print(f"{count} files copied into {page.name}")
