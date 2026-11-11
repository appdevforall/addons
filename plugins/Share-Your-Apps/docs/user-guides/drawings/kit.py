import os
"""Pieces for drawing Share Your Apps's screens, in each app's own look, with numbered callouts."""
import sys, html as _h
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import server_look as S          # the server app's pieces, drawn from its real screenshot
E = _h.escape

def co(n, inner, grow=False, inline=False):
    """Marks an item for callout n: the page's script outlines it, and badges it with n."""
    disp = 'inline-block' if inline else 'block'
    return f'<div data-co="{n}" style="display:{disp};{"flex:1;" if grow else ""}">{inner}</div>'

CALLOUTS_JS = '''<script>
window.addEventListener('load', () => setTimeout(() => {
  const W = document.documentElement.clientWidth;
  document.querySelectorAll('[data-co]').forEach(el => {
    const r = el.getBoundingClientRect(), n = el.dataset.co, sx = scrollX, sy = scrollY;
    const box = document.createElement('div');
    box.style.cssText = `position:absolute;left:${r.left+sx-3}px;top:${r.top+sy-3}px;width:${r.width+6}px;height:${r.height+6}px;border:1.2px solid #E53935;border-radius:8px;pointer-events:none;box-sizing:border-box`;
    const b = document.createElement('div'); b.textContent = n;
    // Wholly outside the outline, 3 px from it: to its left, or, with no room there, above it.
    const bl = r.left + sx - 3, bt = r.top + sy - 3;
    let x = bl - 3 - 26, y = bt + Math.min(r.height / 2, 22) - 13;
    if (x < 1) { x = bl; y = bt - 3 - 26; }
    b.style.cssText = `position:absolute;left:${x}px;top:${y}px;width:26px;height:26px;border-radius:13px;background:#E53935;color:#fff;font:700 15px/26px Roboto,sans-serif;text-align:center;box-shadow:0 1px 3px rgba(0,0,0,.45)`;
    document.body.appendChild(box); document.body.appendChild(b);
  });
}, 150));
</script>'''

FONTS = '''<style>
@font-face{font-family:Roboto;src:url("file:///tmp/fonts/Roboto.ttf");font-weight:100 900}
@font-face{font-family:"Roboto Mono";src:url("file:///tmp/fonts/RobotoMono.ttf");font-weight:100 700}
body{margin:0} *{-webkit-font-smoothing:antialiased}
</style>'''

def page(body, bg):
    return f'<!doctype html><html><head><meta charset=utf-8>{FONTS}</head><body style="background:{bg}"><div style="width:390px;background:{bg};position:relative">{body}</div>{CALLOUTS_JS}</body></html>'

# ---------------------------------------------------------------- the Share Your Apps tab, in Code On The Go's dark theme
BG = '#17181C'; CARD = '#22252C'; EDGE = '#5A616B'; BEDGE = '#3A3E47'; WHITE = '#E9EAEE'; GREY = '#9EA2AB'
SECTION = '#9DA1AD'; BLUE = '#A1B3D9'; RED = '#D75D5A'; PUB = '#9EB2E5'; PUBINK = '#111723'; GREEN = '#7FC89A'

def cg_bar(title='Share Your Apps'):
    """Code On The Go's editor, cut down: its status bar, and the tab strip, with the Share Your Apps tab chosen."""
    return (f'<div style="background:#000;color:#fff;height:24px;padding:0 16px;display:flex;align-items:center;justify-content:space-between;font:12px Roboto">'
            f'<span>4:05</span><span>Wi‑Fi ▾ 86%</span></div>'
            f'<div style="background:{CARD};height:44px;display:flex;align-items:flex-end;padding:0 12px;gap:18px;font:500 14px Roboto;color:{GREY};border-bottom:1px solid {BEDGE}">'
            f'<span style="padding:0 2px 11px">Build output</span><span style="padding:0 2px 11px">App logs</span>'
            f'<span style="padding:0 2px 9px;color:{WHITE};border-bottom:2px solid {PUB}">{E(title)}</span></div>')
def section(t): return f'<p style="margin:22px 0 10px;font:500 14px Roboto;letter-spacing:.6px;text-transform:uppercase;color:{SECTION}">{E(t)}</p>'
def label(t): return f'<p style="margin:14px 0 8px;font:600 15px Roboto;color:{WHITE}">{E(t)}</p>'
def hint(t, color=GREY): return f'<p style="margin:6px 0 0;font:12.5px/1.4 Roboto;color:{color}">{t}</p>'
def field(v, mono=False, lines=1, placeholder=False, width=None):
    fam = "'Roboto Mono',monospace" if mono else 'Roboto'
    col = GREY if placeholder else WHITE
    w = f'width:{width}px;' if width else ''
    return (f'<div style="{w}box-sizing:border-box;background:{CARD};border:1.5px solid {EDGE};border-radius:12px;padding:12px 14px;min-height:{24+lines*20}px;'
            f'font:14px/1.45 {fam};color:{col};white-space:pre-wrap">{E(v)}</div>')
def btn(t, grow=True, disabled=False):
    return (f'<div style="{"flex:1;" if grow else ""}box-sizing:border-box;border:1.5px solid {BEDGE};background:{CARD};border-radius:12px;min-height:44px;'
            f'display:flex;align-items:center;justify-content:center;padding:0 12px;font:500 14px Roboto;color:{GREY if disabled else BLUE};text-align:center">{E(t)}</div>')
def row(*items, gap=10): return f'<div style="display:flex;gap:{gap}px;align-items:center;margin:8px 0">{"".join(items)}</div>'
def check(t, on):
    box = (f'<span style="width:18px;height:18px;border-radius:3px;border:2px solid {PUB if on else GREY};background:{PUB if on else "transparent"};'
           f'display:inline-flex;align-items:center;justify-content:center;color:{PUBINK};font:900 13px Roboto">{"✓" if on else ""}</span>')
    return f'<div style="display:flex;align-items:center;gap:12px;min-height:40px;font:15px Roboto;color:{WHITE}">{box}{E(t)}</div>'
def card(*items, open_=True): return f'<div style="background:{CARD};border:1.5px solid {BEDGE};border-radius:14px;padding:12px 14px;margin:0 0 12px">{"".join(items)}</div>'
def product_head(name, status, in_store=True, open_=False):
    return (f'<div style="display:flex;justify-content:space-between;align-items:center;gap:8px"><span style="font:600 16px Roboto;color:{WHITE}">{E(name)}</span>'
            f'<span style="font:13px Roboto;color:{GREEN if in_store else GREY}">{E(status)}</span></div>')
def bottom(status='', error=False, busy=False, status_co=None):
    s = hint(E(status).replace('\n', '<br>'), RED if error else GREY) if status else ''
    if s and status_co: s = co(status_co, s)
    return (f'<div style="background:{CARD};padding:12px 16px 16px;margin-top:16px">{s}'
            f'<div style="margin-top:10px;background:{PUB};border-radius:14px;height:52px;display:flex;align-items:center;justify-content:center;font:500 16px Roboto;color:{PUBINK}">{"Publishing…" if busy else "Publish"}</div></div>')
def plugin_screen(*content, status='', error=False, show_bottom=True, status_co=None):
    return page(cg_bar() + f'<div style="padding:4px 18px 8px">{"".join(content)}</div>' + (bottom(status, error, status_co=status_co) if show_bottom else ''), BG)

def dark_dialog(title, *content, buttons=()):
    """A dialog in Code On The Go's dark theme, over a dimmed tab."""
    bs = ''.join(f'<span style="font:500 14px Roboto;color:{BLUE};padding:10px 8px">{E(b)}</span>' for b in buttons)
    return page(cg_bar() + f'<div style="background:rgba(0,0,0,.55);padding:70px 22px 200px">'
                f'<div style="background:#2B2E36;border-radius:24px;padding:22px 22px 12px">'
                f'<p style="margin:0 0 12px;font:500 20px/1.3 Roboto;color:{WHITE}">{title}</p>{"".join(content)}'
                f'<div style="display:flex;justify-content:flex-end;gap:10px;margin-top:10px">{bs}</div></div></div>', BG)

# ---------------------------------------------------------------- plain Android screens, for what Android or Code On The Go draws
def android_dialog(title, text, buttons, note='Drawn from memory: replace with a real screenshot.'):
    bs = ''.join(co(n, f'<span style="font:500 14px Roboto;color:#0B57D0;padding:10px 12px">{E(b)}</span>', inline=True) if n else
                 f'<span style="font:500 14px Roboto;color:#0B57D0;padding:10px 12px">{E(b)}</span>' for b, n in buttons)
    return page(S.status_bar() + f'<div style="background:#5c5c5c;padding:120px 24px 260px"><div style="background:#fff;border-radius:26px;padding:24px">'
                f'<p style="margin:0 0 14px;font:400 22px/1.3 Roboto;color:#1F1F1F">{title}</p><p style="margin:0;font:14px/1.5 Roboto;color:#444">{text}</p>'
                f'<div style="display:flex;justify-content:flex-end;gap:8px;margin-top:18px">{bs}</div></div>'
                f'<p style="margin:16px 4px 0;font:italic 12px Roboto;color:#eee">{E(note)}</p></div>', '#5c5c5c')
