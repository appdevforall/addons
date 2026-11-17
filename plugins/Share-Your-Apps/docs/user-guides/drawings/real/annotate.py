import json, re, sys, os
from PIL import Image, ImageDraw, ImageFont
sys.path.insert(0, '/tmp/real'); from specs import SPECS
O = json.load(open('/tmp/real/ocr.json'))
MANUAL = {   # boxes read off by eye, where OCR couldn't read the text (dimmed dialogs, small red text, a scrolled-off label)
 'P08': {1: (150, 1330, 1320, 1425), 2: (150, 1450, 1320, 1615)},
 'P09': {1: (130, 1545, 740, 1590), 2: (150, 1590, 590, 1730), 3: (625, 1590, 985, 1730)},
 'P11': {1: (165, 1285, 830, 1370), 2: (165, 1405, 1170, 1490)},
 'P14': {1: (55, 2362, 1375, 2512)},
 'P13': {1: (55, 2358, 975, 2506)},
 'B01': {1: (20, 8, 580, 90)},
 'P15': {1: (60, 575, 1380, 1228), 2: (62, 1245, 716, 1405), 3: (722, 1245, 1424, 1405), 4: (60, 1490, 1380, 2165)},
}
CROP = {}   # screenshots to cut short, such as one with a keyboard showing
# Where a bubble has no room outside its outline, it goes inside, a few pixels clear of the outline:
# at the left or right end, halfway down, or at the top right.
PLACE = {'P15': {2: 'inside-left', 3: 'inside-right', 4: 'inside-top-right'},
         'B01': {1: 'right', 2: 'inside-top-right', 3: 'above'}, 'S15': {1: 'inside-top-right'}, 'B02': {1: 'inside-top-left', 2: 'right', 3: 'right'}}
def close(a, b, tol=22): return sum(abs(x - y) for x, y in zip(a, b)) <= tol
def grow(im, box):
    """Widen a text box to the button around it, by following the button's colour outwards from the text."""
    x0, y0, x1, y1 = box; cy = (y0 + y1) // 2; cx = (x0 + x1) // 2; W, H = im.size
    seed = im.getpixel((max(x0 - 8, 0), cy))
    l = x0 - 8
    while l > 0 and close(im.getpixel((l - 1, cy)), seed) and x0 - l < 700: l -= 1
    r = x1 + 8
    while r < W - 1 and close(im.getpixel((r + 1, cy)), seed) and r - x1 < 700: r += 1
    t = y0 - 4
    while t > 0 and close(im.getpixel((cx, t - 1)), seed) and y0 - t < 90: t -= 1
    b = y1 + 4
    while b < H - 1 and close(im.getpixel((cx, b + 1)), seed) and b - y1 < 90: b += 1
    return (l, t, r, b)
def boxes_for(k, im):
    out = {}
    for n, pats, *opt in SPECS[k]:
        o = opt[0] if opt else {}
        if k in MANUAL and n in MANUAL[k]: out[n] = MANUAL[k][n]; continue
        hits = []
        for p in pats:
            m = [l for l in O[k]['lines'] if re.search(p, l['text'])]
            if o.get('first'): m = m[:1]
            if o.get('last'): m = m[-1:]
            hits += m
        if not hits: print('  no anchor for', k, n); continue
        # Buttons: widen each to its own edges first, then join them, so two buttons are both covered.
        hb = [list(grow(im, h['box'])) if o.get('grow') else h['box'] for h in hits]
        b = [min(h[0] for h in hb), min(h[1] for h in hb), max(h[2] for h in hb), max(h[3] for h in hb)]
        if o.get('qr_above'): b = [b[0] - 260, b[1] - 830, b[2] + 260, b[3]]
        if o.get('qr_below'): b = [b[0], b[1], b[2], b[3] + 650]
        if o.get('widen'): b = [b[0] - o['widen'], b[1], b[2] + o['widen'], b[3]]
        if o.get('span'): b = [o['span'][0], b[1], o['span'][1], b[3]]   # a whole row, from edge to edge of its card
        if o.get('down'): b = [b[0], b[1], b[2], b[3] + o['down']]       # down to include the field under a label
        if o.get('pady'): b = [b[0], b[1] - o['pady'], b[2], b[3] + o['pady']]
        if 'xmin' in o: b[0] = min(b[0], o['xmin'])
        if 'xmax' in o: b[2] = max(b[2], o['xmax'])
        if 'bottom' in o: b[3] = o['bottom']
        if 'up_to' in o: b = [b[0], o['up_to'], b[2], b[1] - 30]   # the area above the anchor: here, a product's pictures
        if o.get('icon_right'): b = [im.width - 175, b[1] - 16, im.width - 95, b[1] + 60]   # the tooltip's faint ⓘ, at its top right
        out[n] = (b[0] - 14, b[1] - 12, b[2] + 14, b[3] + 12)
    return out
def draw(k, src, dst, width=780):
    im = Image.open(src).convert('RGB')
    if k in CROP: im = im.crop((0, 0, im.width, CROP[k]))
    bx = boxes_for(k, im); s = im.width / 390          # draw at the same visual weight as the drawings
    d = ImageDraw.Draw(im)
    font = ImageFont.truetype('/tmp/fonts/Roboto.ttf', int(15 * s)); font.set_variation_by_axes([700, 100]) if hasattr(font, 'set_variation_by_axes') else None
    r = int(13 * s); gap = int(3 * s)
    clip = {n: (max(b[0], 2), max(b[1], 2), min(b[2], im.width - 3), min(b[3], im.height - 3)) for n, b in bx.items()}
    def clear(cx, cy, own):
        # Clear of the screen's edges, and of every other outline, by at least the gap.
        if cx - r < 1 or cy - r < 1 or cx + r > im.width - 1: return False
        return all(not (cx + r + gap > b[0] and cx - r - gap < b[2] and cy + r + gap > b[1] and cy - r - gap < b[3])
                   for m, b in clip.items() if m != own)
    for n, (x0, y0, x1, y1) in sorted(clip.items()):
        d.rounded_rectangle((x0, y0, x1, y1), radius=int(8 * s), outline=(229, 57, 53), width=max(int(1.2 * s), 2))
        # The bubble sits wholly outside its outline, a few pixels from it: to its left; or, if that would touch
        # another outline or the screen's edge, above it; or else below it.
        options = [(x0 - gap - r, y0 + min((y1 - y0) // 2, int(22 * s))), (x0 + r, y0 - gap - r), (x0 + r, y1 + gap + r)]
        inside = {'inside-left': (x0 + gap + r, (y0 + y1) // 2), 'inside-right': (x1 - gap - r, (y0 + y1) // 2),
                  'inside-top-right': (x1 - gap - r, y0 + gap + r), 'inside-top-left': (x0 + gap + r, y0 + gap + r),
                  'right': (x1 + gap + r, (y0 + y1) // 2), 'above': (x0 + r, y0 - gap - r)}
        where = PLACE.get(k, {}).get(n)
        cx, cy = inside[where] if where else next((c for c in options if clear(c[0], c[1], n)), options[0])
        cx, cy = max(cx, r + 1), max(cy, r + 1)
        d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(229, 57, 53))
        d.text((cx, cy), str(n), fill='white', font=font, anchor='mm')
    im = im.resize((width, int(im.height * width / im.width)), Image.LANCZOS)
    im.save(dst, quality=88, optimize=True)
    return bx
if __name__ == '__main__':
    os.makedirs('/tmp/real/out', exist_ok=True)
    for k in SPECS:
        draw(k, f'/tmp/real/{k}.jpg', f'/tmp/real/out/{k}.jpg')
    print(len(SPECS), 'annotated')
