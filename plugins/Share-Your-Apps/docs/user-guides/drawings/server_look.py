"""The server app's look, drawn from its real screenshot: pieces for the guides' drawings (see kit.py)."""
import json, os, re
INK='#20242B'; BODY='#3C4148'; MUTED='#6B7078'; LINK='#485D92'; DANGER='#A1261B'
def esc(s): return s.replace('&','&amp;').replace('<','&lt;').replace('>','&gt;')
def text(t, size=15, bold=False, color=BODY, center=False, mono=False, extra=''):
    fam = "'Roboto Mono', monospace" if mono else "Roboto, sans-serif"
    return f'<p style="margin: 0 0 8px; font-family: {fam}; font-size: {size}px; line-height: 1.4; color: {color}; font-weight: {700 if bold else 400};{" text-align: center;" if center else ""}{extra}">{esc(t)}</p>'
def title(t): return f'<p style="margin: 0 0 6px; font-family: Roboto, sans-serif; font-size: 17px; font-weight: 700; color: {INK}">{esc(t)}</p>'
def heading(t): return f'<h2 style="margin: 0 0 10px; font-family: Roboto, sans-serif; font-size: 20px; font-weight: 700; color: {INK}">{esc(t)}</h2>'
def btn(t, disabled=False, color=None, full=True, grow=False):
    fg = '#A2A5A9' if disabled else (color or '#212121')
    bg = '#E6E7E8' if disabled else '#D6D7D7'
    shadow = 'none' if disabled else '0 1px 2px rgba(0,0,0,0.35)'
    w = 'width: 100%;' if full else ''
    g = 'flex: 1;' if grow else ''
    return (f'<button type="button"{" disabled" if disabled else ""} style="{w}{g} min-height: 48px; margin: 4px 0; border: 0; border-radius: 2px; background: {bg}; '
            f'box-shadow: {shadow}; font-family: Roboto, sans-serif; font-size: 14px; font-weight: 500; letter-spacing: 0.5px; text-transform: uppercase; color: {fg}; padding: 0 12px">{esc(t)}</button>')
def row(*items, gap=8, center=False): return f'<div style="display: flex; gap: {gap}px; align-items: center;{" justify-content: center;" if center else ""}">{"".join(items)}</div>'
def edit(value, mono=False, width=None, placeholder=False, size=17):
    fam = "'Roboto Mono', monospace" if mono else "Roboto, sans-serif"
    w = f'width: {width}px;' if width else 'flex: 1; width: 100%;'
    col = MUTED if placeholder else INK
    return f'<div style="{w} box-sizing: border-box; border-bottom: 2px solid #7C8089; padding: 8px 2px 6px; margin: 0 0 10px; font-family: {fam}; font-size: {size}px; color: {col}">{esc(value)}</div>'
def radio(label, checked=False):
    dot = f'<span style="position: absolute; left: 5px; top: 5px; width: 10px; height: 10px; border-radius: 5px; background: #009688"></span>' if checked else ''
    ring = '#009688' if checked else '#6B7078'
    return (f'<div style="display: flex; align-items: center; gap: 14px; min-height: 44px"><span style="position: relative; width: 20px; height: 20px; box-sizing: border-box; border: 2px solid {ring}; border-radius: 10px; flex-shrink: 0">{dot}</span>'
            f'<span style="font-family: Roboto, sans-serif; font-size: 16px; color: {INK}">{esc(label)}</span></div>')
def qr(size):
    cells=[]
    import random; r=random.Random(size)
    n=25; c=size/n
    for y in range(n):
        for x in range(n):
            finder = any(fx<=x<fx+7 and fy<=y<fy+7 for fx,fy in ((0,0),(n-7,0),(0,n-7)))
            if finder:
                fx = 0 if x<7 else n-7; fy = 0 if y<7 else n-7; dx, dy = x-fx, y-fy
                on = dx in (0,6) or dy in (0,6) or (2<=dx<=4 and 2<=dy<=4)
            else: on = r.random() < 0.45
            if on: cells.append(f'<rect x="{x*c:.1f}" y="{y*c:.1f}" width="{c+0.3:.1f}" height="{c+0.3:.1f}"/>')
    return f'<div style="display: flex; justify-content: center; margin: 6px 0 10px"><svg width="{size}" height="{size}" viewBox="0 0 {size} {size}" role="img" aria-label="QR code" style="background: #fff; padding: 10px">{"".join(cells)}</svg></div>'
def copyicon(): return '<svg width="26" height="26" viewBox="0 0 24 24" aria-label="Copy the code" role="img" fill="none" stroke="#20242B" stroke-width="2.2" stroke-linejoin="round"><rect x="8" y="3" width="12" height="13" rx="2"/><rect x="4" y="8" width="12" height="13" rx="2" fill="#fff"/></svg>'
def checkbox(label, code): return (f'<div style="border: 2px solid {INK}; border-radius: 10px; background: #FFF3D6; padding: 8px 12px 10px; margin: 0 0 12px; text-align: center">'
    f'<p style="margin: 0; font-family: Roboto, sans-serif; font-size: 13px; color: {INK}">{esc(label)}</p><p style="margin: 2px 0 0; font-family: Roboto, sans-serif; font-size: 34px; font-weight: 700; letter-spacing: 4px; color: {INK}">{code}</p></div>')
def progress(pct): return f'<div style="height: 6px; background: #CFD8DC; margin: 4px 0 8px"><div style="width: {pct}%; height: 100%; background: #009688"></div></div>'
def step(label, state):
    mark = '✓' if state=='done' else label[0]
    lab = label[2:] if state!='done' else label[2:]
    return f'<p style="margin: 0 0 10px; font-family: Roboto, sans-serif; font-size: 16px; color: {INK}; font-weight: {700 if state=="now" else 400}; opacity: {0.6 if state=="todo" else 1}">{mark}&nbsp;&nbsp;{esc(lab)}</p>'
def panel(t, *items, highlight=False):
    return (f'<section style="position: relative; margin: 0 5px 19px 0; border-radius: 12px; border: 1px solid #B9C0CA; background: linear-gradient(#FFFFFF, #DCE0E6); '
            f'box-shadow: 3px 3px 0 #9AA3AE, 5px 5px 0 rgba(0,0,0,0.33); padding: 12px 14px 14px">{title(t) if t else ""}{"".join(items)}</section>')
def card(*items):
    return f'<section style="margin: 0 0 16px; border-radius: 12px; border: 1px solid #B9C0CA; background: linear-gradient(#FFFFFF, #F0F2F5); padding: 16px">{"".join(items)}</section>'
def bar(t): return f'<div style="background: #212121; color: #fff; padding: 0 16px; height: 56px; display: flex; align-items: center; font-family: Roboto, sans-serif; font-size: 20px; font-weight: 500">{esc(t)}</div>'
def status_bar(): return '<div style="background: #000; color: #fff; height: 24px; padding: 0 16px; display: flex; align-items: center; justify-content: space-between; font-family: Roboto, sans-serif; font-size: 12px"><span>4:05</span><span>Wi‑Fi ▾ 86%</span></div>'
def screen(barTitle, *content):
    return status_bar() + bar(barTitle) + f'<div style="padding: 16px">{"".join(content)}</div>'
def footer(): return text("Share Your Apps Server 1.0.26 · built Sep 27, 2026 4:12 PM", 12, color=MUTED, center=True, extra=" margin-top: 4px;")

HINT = text("Long-press a heading or button for help.", 12, color=MUTED)
SHARE_CAPTION = "Give someone nearby a copy to serve from their own phone. They can serve it with their own PIN. They can't change it or pass it on."
IMPORT_CAPTION = "Serve a copy of someone else's store, with their permission. You'll need to be near their phone, on the same network."
def server_panel(): return panel("Server", row(text("Port", 15, color=MUTED, extra=" margin: 0;"), edit("6175", width=110)), row(btn("Start", full=False, grow=True), btn("Stop", full=False, grow=True), btn("Help", full=False, grow=True)))
def copies_panel(caption, enabled): return panel("Copies of this store", text(caption), btn("Share this store…", disabled=not enabled), text("You can set an expiration date.", 13, color=MUTED))
def import_panel(serving): return panel("Import a store",
    text("This phone is serving a store. To import one, stop serving it first, with Stop above, or import on another phone." if serving else IMPORT_CAPTION),
    btn("Import a store…", disabled=serving))
def run_panel(): return panel("When to run", radio("Run always", True), radio("Pause when the screen turns off"))
def status_panel(line, url, last):
    items=[text(line, 16, bold=True, color=INK)]
    if url: items.append(text(url, 16, color=LINK))
    items.append(btn("Show QR codes for buyers")); items.append(text(last, 15))
    return panel("Status", *items)

