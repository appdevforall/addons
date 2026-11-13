"""Renders a demo video from the guides' annotated screens: scene by scene, zooming to each scene's callout."""
import sys, os, json, math, subprocess, textwrap
sys.path.insert(0, '/tmp/video'); sys.path.insert(0, '/tmp/real')
from PIL import Image, ImageDraw, ImageFont, ImageFilter
import scenes, annotate
W, H, FPS = 1920, 1080, 30
INK = (23, 19, 31); PANEL = (34, 29, 45); RED = (255, 79, 58); CREAM = (255, 243, 214); WHITE = (245, 242, 248); MUTED = (170, 162, 184); TEAL = (11, 110, 110)
def font(size, weight=400):
    f = ImageFont.truetype('/tmp/fonts/Roboto.ttf', size)
    try: f.set_variation_by_axes([weight, 100])   # weight first, then width
    except Exception: pass
    return f
F_PART, F_TITLE, F_CAP, F_SMALL, F_CARD, F_CARDCAP = font(30, 700), font(76, 700), font(40, 400), font(26, 500), font(120, 800), font(44, 400)
OCTO = Image.open('/home/claude/repo-docs/docs/user-guides/octopus.png').convert('RGBA')
SCREENS = '/tmp/real/out'
VH = 900; VW = int(VH * 1440 / 3088)            # the phone's screen, on the video
PX, PY = 300, (H - VH) // 2 - 10                # its top-left corner

def background():
    bg = Image.new('RGB', (W, H), INK); d = ImageDraw.Draw(bg)
    for y in range(H):                            # a gentle vertical glow
        k = 1 - abs(y - H * 0.45) / H; d.line((0, y, W, y), fill=tuple(int(c + 10 * k) for c in INK))
    return bg
BG = background()

def wrap(d, text, f, width):
    words, lines, cur = text.split(), [], ''
    for w in words:
        t = (cur + ' ' + w).strip()
        if d.textlength(t, font=f) <= width: cur = t
        else: lines.append(cur); cur = w
    if cur: lines.append(cur)
    return lines

def chrome(img, t_total, t_now):
    """The wordmark, and a thin progress bar along the bottom."""
    d = ImageDraw.Draw(img)
    o = OCTO.resize((44, 44)); img.paste(o, (60, H - 70), o)
    d.text((116, H - 60), 'Share Your Apps', font=F_SMALL, fill=MUTED)
    d.rectangle((0, H - 6, W, H), fill=(45, 39, 58)); d.rectangle((0, H - 6, int(W * t_now / t_total), H), fill=RED)

def text_panel(img, part, title, caption):
    d = ImageDraw.Draw(img); x = PX + VW + 150; wmax = W - x - 110; y = 300
    if part: d.text((x, y), part.upper(), font=F_PART, fill=RED); y += 60
    for line in wrap(d, title, F_TITLE, wmax): d.text((x, y), line, font=F_TITLE, fill=WHITE); y += 90
    y += 30
    for line in wrap(d, caption, F_CAP, wmax): d.text((x, y), line, font=F_CAP, fill=CREAM); y += 56

def card(part, title, caption):
    img = BG.copy(); d = ImageDraw.Draw(img)
    o = OCTO.resize((170, 170)); img.paste(o, ((W - 170) // 2, 170), o)
    y = 380
    if part: tw = d.textlength(part.upper(), font=F_PART); d.text(((W - tw) / 2, y), part.upper(), font=F_PART, fill=RED); y += 60
    tw = d.textlength(title, font=F_CARD); d.text(((W - tw) / 2, y), title, font=F_CARD, fill=WHITE); y += 170
    for line in wrap(d, caption, F_CARDCAP, 1300):
        tw = d.textlength(line, font=F_CARDCAP); d.text(((W - tw) / 2, y), line, font=F_CARDCAP, fill=CREAM); y += 62
    return img

def phone_base(part, title, caption):
    """Everything but the moving screen: the background, the phone's body, and the text."""
    img = BG.copy(); d = ImageDraw.Draw(img)
    sh = Image.new('RGBA', (VW + 80, VH + 80), (0, 0, 0, 0)); ImageDraw.Draw(sh).rounded_rectangle((20, 30, VW + 60, VH + 70), 56, fill=(0, 0, 0, 150))
    sh = sh.filter(ImageFilter.GaussianBlur(18)); img.paste(sh, (PX - 40, PY - 30), sh)
    d.rounded_rectangle((PX - 16, PY - 16, PX + VW + 16, PY + VH + 16), 50, fill=(8, 6, 12), outline=(70, 62, 86), width=3)
    text_panel(img, part, title, caption)
    return img

def target_box(sid, im):
    """The zoom's target: the scene's callout box, in the annotated screen's pixels."""
    src = f'/tmp/real/{sid}.jpg'; orig = Image.open(src).convert('RGB')
    if sid in annotate.CROP: orig = orig.crop((0, 0, orig.width, annotate.CROP[sid]))
    return {n: tuple(v * im.width / orig.width for v in b) for n, b in annotate.boxes_for(sid, orig).items()}

def ease(u): return 0.5 - 0.5 * math.cos(math.pi * min(max(u, 0), 1))

def frames(scene, t0, total):
    sid, co, part, title, cap = scene
    dur = scenes.duration(cap); n = int(round(dur * FPS))
    if sid == 'card':
        base = card(part, title, cap); get = lambda f: base
    else:
        base = phone_base(part, title, cap)
        im = Image.open(f'{SCREENS}/{sid}.jpg').convert('RGB'); iw, ih = im.size
        ar = VW / VH; w0 = iw; h0 = w0 / ar
        if co:
            tb = target_box(sid, im); cs = co if isinstance(co, tuple) else (co,)   # several callouts: zoom to all of them
            bs = [tb[c] for c in cs]; bx = (min(b[0] for b in bs), min(b[1] for b in bs), max(b[2] for b in bs), max(b[3] for b in bs))
            cx, cy = (bx[0] + bx[2]) / 2, (bx[1] + bx[3]) / 2
            # Zoom in only as far as the whole outline still fits, with room for its bubble on either side.
            need = (bx[2] - bx[0]) + 0.2 * iw
            z = max(min(0.82 * iw / max(bx[2] - bx[0], 1), 1.9, iw / need), 1.0)
        else: cx, cy, z = iw / 2, h0 / 2, 1.06
        w1 = iw / z; h1 = w1 / ar
        def win(w, h, cx, cy):
            x = min(max(cx - w / 2, 0), iw - w); y = min(max(cy - h / 2, 0), max(ih - h, 0)); return x, y, w, h
        start = win(w0, h0, cx, cy); end = win(w1, h1, cx, cy)
        pad = h0 > ih
        def get(f):
            u = ease((f / n - 0.12) / 0.6)
            x, y, w, h = [a + (b - a) * u for a, b in zip(start, end)]
            x = min(max(x, 0), iw - w)
            if y + h > ih:                       # a screen shorter than the phone: letterbox it below
                canvas = Image.new('RGB', (iw, int(math.ceil(y + h)) + 2), (20, 20, 24)); canvas.paste(im, (0, 0)); src = canvas
            else: src = im
            view = src.resize((VW, VH), Image.BILINEAR, box=(x, max(y, 0), x + w, max(y, 0) + h))
            out = base.copy(); mask = Image.new('L', (VW, VH), 0); ImageDraw.Draw(mask).rounded_rectangle((0, 0, VW - 1, VH - 1), 36, fill=255)
            out.paste(view, (PX, PY), mask); return out
    fade = int(0.35 * FPS)
    for f in range(n):
        img = get(f).copy(); chrome(img, total, t0 + f / FPS)
        a = min(1, f / fade, (n - 1 - f) / fade) if fade else 1
        if a < 1: img = Image.blend(BG, img, max(a, 0))
        yield img
    return dur

def render(which, out):
    S = getattr(scenes, which); total = sum(scenes.duration(s[4]) for s in S)
    p = subprocess.Popen(['ffmpeg', '-y', '-loglevel', 'error', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', f'{W}x{H}', '-r', str(FPS), '-i', '-',
                          '-c:v', 'libx264', '-preset', 'medium', '-crf', '19', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', out], stdin=subprocess.PIPE)
    t = 0
    for sc in S:
        for img in frames(sc, t, total): p.stdin.write(img.tobytes())
        t += scenes.duration(sc[4])
    p.stdin.close(); p.wait(); return total

if __name__ == '__main__':
    if sys.argv[1] == 'frame':
        S = getattr(scenes, sys.argv[2]); sc = S[int(sys.argv[3])]; fs = list(frames(sc, 0, 100)); fs[int(len(fs) * 0.85)].save(sys.argv[4])
    else:
        if len(sys.argv) > 3: scenes.timed(sys.argv[1], json.load(open(sys.argv[3])))   # times from a recorded narration
        print(render(sys.argv[1], sys.argv[2]))
