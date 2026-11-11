"""Builds Share Your Apps's three user guides, as PDFs, in the store's look:

  share-your-apps-plugin-guide.pdf    — for owners: the Share Your Apps plugin in Code On The Go
  local-store-server-guide.pdf    — for owners: the Local Store Server app
  serving-an-imported-store.pdf   — for a seller who imported a copy of someone's store

Usage: python3 build_user_guides.py [output folder]             PDFs (default: beside this script)
       python3 build_user_guides.py --markdown [output folder]  Markdown, for importing into Help+Manual
       python3 build_user_guides.py --shot-list [output folder] screenshot-list.pdf: every screen to capture
Needs reportlab: pip install reportlab

Versions and the date: each guide says which versions it describes, and when it was published. The
builder reads the versions from the projects — the plugin's versionName from ../../build.gradle.kts,
and the server app's versionCode from ../../../Local Store Server/app/build.gradle.kts, whose version
is 1.0.<versionCode> — and the date is the day it runs. To set any of them yourself:
       --plugin-version 1.0.0   --server-version 1.0.57   --date 2026-09-27

Appendix A: if pebble-book.pdf — the Pebble project's documentation, from
https://pebbletemplates.io/wiki/book.pdf — is beside this script, the plugin guide gets it, unchanged, as
Appendix A, after a cover page, and its templates section refers to it. Without the file, neither appears.
Appending it needs pypdf: pip install pypdf

The Markdown is made from the same text as the PDFs, so the two never differ. It's laid out as
Help+Manual imports Markdown: one folder per guide, which becomes a chapter, holding one file per
section, which becomes a topic. Files are numbered, so they sort in reading order.
"""
import os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
import re, datetime
MARKDOWN = "--markdown" in sys.argv
SHOT_LIST = "--shot-list" in sys.argv
OPTS, ARGS, _argv = {}, [], sys.argv[1:]
while _argv:
    a = _argv.pop(0)
    if a in ("--markdown", "--shot-list"): continue
    if a.startswith("--") and _argv: OPTS[a[2:]] = _argv.pop(0)
    else: ARGS.append(a)
OUTDIR = ARGS[0] if ARGS else HERE

def _read(path, pattern):
    try:
        m = re.search(pattern, open(path, encoding="utf-8").read()); return m.group(1) if m else None
    except OSError: return None
PLUGIN_VERSION = OPTS.get("plugin-version") or _read(os.path.join(HERE, "..", "..", "build.gradle.kts"), r'versionName\s*=\s*"([^"$]+)"') or "unknown"
_code = _read(os.path.join(HERE, "..", "..", "..", "Local Store Server", "app", "build.gradle.kts"), r"versionCode\s*=\s*(\d+)")
SERVER_VERSION = OPTS.get("server-version") or (f"1.0.{_code}" if _code else "unknown")
PUBLISHED = datetime.date.fromisoformat(OPTS["date"]) if "date" in OPTS else datetime.date.today()
PUBLISHED_TEXT = PUBLISHED.strftime("%B ") + str(PUBLISHED.day) + PUBLISHED.strftime(", %Y")
STAMP = f"For Share Your Apps {PLUGIN_VERSION} and Local Store Server {SERVER_VERSION} · Published {PUBLISHED_TEXT}"
from reportlab.lib.pagesizes import letter
from reportlab.platypus import (SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, Image, ListFlowable,
                                ListItem, KeepTogether)
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib import colors
from reportlab.lib.units import inch

INK = colors.HexColor('#2B1B3D'); TEAL = colors.HexColor('#0B6E6E'); RED = colors.HexColor('#FF4F3A')
CREAM = colors.HexColor('#FFF3D6'); MUTED = colors.HexColor('#5B5068')
body = ParagraphStyle('b', fontName='Helvetica', fontSize=10, leading=13.4, textColor=INK, spaceAfter=4)
h1 = ParagraphStyle('h1', fontName='Helvetica-Bold', fontSize=14, leading=17, textColor=TEAL, spaceBefore=10, spaceAfter=4, keepWithNext=1)
h2 = ParagraphStyle('h2', fontName='Helvetica-Bold', fontSize=11, leading=14, textColor=INK, spaceBefore=6, spaceAfter=2, keepWithNext=1)
small = ParagraphStyle('s', parent=body, fontSize=9, leading=12, textColor=MUTED)
title = ParagraphStyle('t', fontName='Helvetica-Bold', fontSize=20, leading=24, textColor=colors.white)
sub = ParagraphStyle('st', fontName='Helvetica', fontSize=10.5, leading=14, textColor=CREAM)
W = 7.5 * inch

def P(t, s=body): return Paragraph(t, s)
def H1(t): return P(t, h1)
def H2(t): return P(t, h2)
def bullets(items, numbered=False):
    return ListFlowable([ListItem(P(i), leftIndent=12, value=(n + 1 if numbered else None)) for n, i in enumerate(items)],
                        bulletType='1' if numbered else 'bullet', start=1 if numbered else None, leftIndent=14,
                        bulletFontSize=8 if not numbered else 10, bulletColor=RED if not numbered else INK,
                        bulletFontName='Helvetica-Bold' if numbered else 'Helvetica', spaceAfter=4)
def tip(t):
    """A note that matters, in a cream box."""
    box = Table([[P(t)]], colWidths=[W])
    box.setStyle(TableStyle([('BACKGROUND', (0, 0), (-1, -1), CREAM), ('BOX', (0, 0), (-1, -1), 0.8, RED),
                             ('LEFTPADDING', (0, 0), (-1, -1), 8), ('RIGHTPADDING', (0, 0), (-1, -1), 8),
                             ('TOPPADDING', (0, 0), (-1, -1), 5), ('BOTTOMPADDING', (0, 0), (-1, -1), 3)]))
    return KeepTogether([Spacer(1, 3), box, Spacer(1, 5)])
# ------------------------------------------------------------------ figures: screens with numbered callouts
import json
SCREENS = os.path.join(HERE, "screens")
try: SHOTS = json.load(open(os.path.join(SCREENS, "shots.json"), encoding="utf-8"))
except OSError: SHOTS = []
keynum = ParagraphStyle('kn', parent=body, fontSize=9.5, leading=12.5)
caption = ParagraphStyle('cap', parent=body, fontName='Helvetica-Bold', fontSize=9.5, leading=12.5, spaceAfter=5)

class Fig(KeepTogether):
    """A screen with its callouts, and its key beside it: numbers in red, matching the picture's badges."""
    def __init__(self, shot, frame=True):
        self.shot = shot
        from PIL import Image as PILImage
        path = screen_file(shot)
        w, h = PILImage.open(path).size
        iw = 2.3 * inch; ih = iw * h / w
        if ih > 4.6 * inch: ih = 4.6 * inch; iw = ih * w / h
        key = [P(f"Figure {shot['id']} · {shot['title']}", caption)]
        key += [P(f'<font color="#E53935"><b>{n}</b></font>&nbsp;&nbsp;{t}', keynum) for n, t in shot["key"]]
        t = Table([[Image(path, iw, ih), key]], colWidths=[iw + 0.25 * inch, W - iw - 0.25 * inch])
        t.setStyle(TableStyle([('VALIGN', (0, 0), (-1, -1), 'TOP'), ('LEFTPADDING', (0, 0), (-1, -1), 0)] +
                              ([('BOX', (0, 0), (0, 0), 0.5, FRAME)] if FRAMES and frame and light_edges(path) else [])))
        KeepTogether.__init__(self, [Spacer(1, 4), t, Spacer(1, 8)])

def screen_file(shot):
    """A real screenshot, as .jpg, if there is one; otherwise the drawing, as .png."""
    for ext in (".jpg", ".png"):
        f = os.path.join(SCREENS, shot["id"] + ext)
        if os.path.exists(f): return f
    raise FileNotFoundError(shot["id"])

def light_edges(path):
    """Whether a picture's edges are light, so it needs a frame to stand out from the white page."""
    from PIL import Image as PILImage, ImageStat
    im = PILImage.open(path).convert('L'); w, h = im.size; e = max(w // 40, 4)
    # The sides only, between the phone's status bar and navigation bar, which are dark on every screen.
    strips = [im.crop((0, h // 10, e, h * 9 // 10)), im.crop((w - e, h // 10, w, h * 9 // 10))]
    return sum(ImageStat.Stat(x).mean[0] for x in strips) / len(strips) > 150

FRAME = colors.HexColor('#C8C3CF')
FRAMES = False   # no picture has a frame, in any guide or the screenshot list

def with_figures(story, guide):
    """Each guide's figures, at the end of the part they illustrate: before the next heading after theirs."""
    shots = [x for x in SHOTS if x["guide"] == guide]
    out, current = [], None
    def flush():
        for x in shots:
            if x["section"] == current: out.append(Fig(x))
    for f in story:
        if isinstance(f, Paragraph) and f.style in (h1, h2):
            flush(); current = md_text(f.text)
        out.append(f)
    flush()
    return out

def header(name, subtitle):
    aw = Table([[''] * 20], colWidths=[W / 20] * 20, rowHeights=[7])
    aw.setStyle(TableStyle([('BACKGROUND', (i, 0), (i, 0), RED if i % 2 == 0 else CREAM) for i in range(20)] +
                           [('LEFTPADDING', (0, 0), (-1, -1), 0), ('RIGHTPADDING', (0, 0), (-1, -1), 0)]))
    stamp = ParagraphStyle('stamp', parent=sub, fontSize=8.5, leading=11, spaceBefore=3)
    head = Table([[Image(os.path.join(HERE, 'octopus.png'), 0.72 * inch, 0.72 * inch), [P(name, title), P(subtitle, sub), P(STAMP, stamp)]]],
                 colWidths=[0.95 * inch, W - 0.95 * inch])
    head.setStyle(TableStyle([('BACKGROUND', (0, 0), (-1, -1), TEAL), ('VALIGN', (0, 0), (-1, -1), 'MIDDLE'),
                              ('LEFTPADDING', (0, 0), (-1, -1), 10), ('RIGHTPADDING', (0, 0), (-1, -1), 10),
                              ('TOPPADDING', (0, 0), (-1, -1), 8), ('BOTTOMPADDING', (0, 0), (-1, -1), 8)]))
    return [aw, head, Spacer(1, 8)]

def build(filename, name, subtitle, story):
    def footer(canvas, doc):
        canvas.saveState(); canvas.setFont('Helvetica', 8.5); canvas.setFillColor(MUTED)
        canvas.drawString(0.5 * inch, 0.35 * inch, f"{name} · Share Your Apps {PLUGIN_VERSION} · {PUBLISHED_TEXT}")
        canvas.drawRightString(8.0 * inch, 0.35 * inch, f"Page {doc.page}")
        canvas.restoreState()
    out = os.path.join(OUTDIR, filename)
    appendix = os.path.join(HERE, 'pebble-book.pdf') if filename == 'share-your-apps-plugin-guide.pdf' else None
    doc = SimpleDocTemplate(out, pagesize=letter, leftMargin=0.5 * inch, rightMargin=0.5 * inch, topMargin=0.45 * inch,
                            bottomMargin=0.6 * inch, title=f"{name}, {PLUGIN_VERSION}", author='App Dev for All', subject=STAMP)
    doc.build(header(name, subtitle) + story, onFirstPage=footer, onLaterPages=footer)
    if appendix and os.path.exists(appendix): add_appendix(out, name, appendix)
    return out

def add_appendix(out, name, book):
    """Appends Pebble's documentation, unchanged, after a cover page saying what it is and whose it is."""
    from pypdf import PdfReader, PdfWriter
    cover = out + '.cover.pdf'
    first = len(PdfReader(out).pages) + 1      # the cover's page number, continuing the guide's
    def footer(canvas, doc):
        canvas.saveState(); canvas.setFont('Helvetica', 8.5); canvas.setFillColor(MUTED)
        canvas.drawString(0.5 * inch, 0.35 * inch, f"{name} · Share Your Apps {PLUGIN_VERSION} · {PUBLISHED_TEXT}")
        canvas.drawRightString(8.0 * inch, 0.35 * inch, f"Page {first}")
        canvas.restoreState()
    doc = SimpleDocTemplate(cover, pagesize=letter, leftMargin=0.5 * inch, rightMargin=0.5 * inch, topMargin=0.45 * inch, bottomMargin=0.6 * inch)
    pages = len(PdfReader(book).pages)
    doc.build(header('Appendix A — Pebble documentation', 'The template language the store page is made from.') + [
        P('The %d pages that follow are the Pebble project\'s own documentation of its template language — every tag, filter, function, test and operator — '
          'as published at <i>https://pebbletemplates.io/wiki/book.pdf</i>. They\'re included unchanged, so they can be read without internet.' % pages),
        P('Share Your Apps uses Pebble to make the store page. Its templates can use everything described here, apart from the parts about setting up Pebble '
          'in a Java program, or with Spring, which Share Your Apps does for you. Share Your Apps adds a few functions and filters of its own, and the blocks of '
          'its default template, which are described in <i>Changing the store\'s look</i>.'),
        P('This appendix is the Pebble project\'s work, not App Dev for All\'s. See <i>https://pebbletemplates.io</i> for its copyright and licence.', small)],
        onFirstPage=footer)
    w = PdfWriter()
    for f in (out, cover, book):
        for pg in PdfReader(f).pages: w.add_page(pg)
    with open(out, 'wb') as fh: w.write(fh)
    os.remove(cover)

# ------------------------------------------------------------------ 1. The plugin
plugin = [
 P('Share Your Apps shares the apps you build in Code On The Go with people nearby — no internet, and no accounts. It has two parts:'),
 bullets(['The <b>Share Your Apps plugin</b>, in Code On The Go, where you describe your apps and publish them. This guide is about the plugin.',
          'The <b>Local Store Server</b> app, which the plugin installs for you. It serves your store over Wi-Fi or a hotspot, and people open it in their phone\'s browser. It has its own guide.']),
 P('<b>You\'ll need:</b> Android 9 or later, Code On The Go, and an app you\'ve built at least once, so it has an APK.'),

 H1('Installing the plugin'),
 bullets(['Get the plugin\'s <b>.cgp</b> file.',
          'In Code On The Go, open the <b>Addons Manager</b>, install that file, and activate the plugin.',
          'A <b>Share Your Apps</b> tab appears. Everything below happens there.'], True),

 H1('Your store'),
 P('The tab is in three parts: <b>Store</b>, <b>Products</b> and <b>Advanced</b>. Changes are saved as you make them; there\'s nothing to save by hand.'),
 H2('Store name'), P('The name at the top of the store page, and in its browser tab.'),
 H2('Store description'), P('Optional text under the name, written in HTML. If a tag is opened but never closed, or similar, a red warning appears under the field. It won\'t stop you publishing, but it may spoil the page\'s layout.'),
 H2('Header image'), P('An optional picture or short video across the top of the page. <b>Change</b> picks one; <b>Default</b> puts back the built-in header. A video shows a still frame, and plays when a buyer taps it, so buyers don\'t each download it on every visit.'),
 H2('PIN'), P('Up to 6 digits that buyers enter before reaching the store, or leave it empty for none. It keeps casual visitors out, but it isn\'t real security: the store doesn\'t use encryption, so someone on the same network could see what\'s sent.'),

 H1('Products'),
 P('Each product is one of your apps. <b>+ Add product</b> adds one at the end of the list. Tap a product\'s card to open it.'),
 H2('Name'), P('The product\'s name, as buyers see it. It also names its downloads, such as <i>todo-app.apk</i>; a second product of the same name gets <i>todo-app-2</i>.'),
 H2('Project and APK'), P('<b>Project</b> is the Code On The Go project the product comes from. <b>APK</b> is the installable app buyers download, chosen from the project\'s builds, newest first. The list marks builds that aren\'t signed: buyers can\'t install those, so choose a signed one.'),
 H2('What to publish'), P('<b>Publish the APK</b> and <b>Publish the source</b> choose what buyers can download: the app, the project\'s source code as a zip, or both. A product with nothing to download is left out of the store, and its card says <i>draft: not published</i>.'),
 H2('Pictures and video'), P('<b>+ Add media</b> adds pictures, videos or sounds, which buyers swipe through on the product\'s card. Beside each one:'),
 bullets(['<b>Describe</b> says what it shows, for visually-impaired people, whose screen readers read it aloud in the picture\'s place. Say what matters for choosing the app — <i>The chat screen, with two messages</i> — not what kind of item it is.',
          'For a video, <b>Describe</b> can also add captions, for people who can\'t hear it: <b>Choose captions file</b> picks a WebVTT file, ending <i>.vtt</i>.',
          '<b>Remove</b> takes it out of the product, with its description and captions. The file itself stays on your phone.']),
 H2('Description and Extra JSON'), P('<b>Description</b> is what buyers read about the product, in HTML. <b>Extra JSON</b> holds optional details in JSON: <i>whatsNew</i>, <i>license</i>, <i>author</i> and <i>website</i> appear on the product\'s card.'),
 H2('Order, and removing a product'), P('<b>Move up</b> and <b>Move down</b> set the order buyers see. <b>Remove</b> takes a product out of the store at your next publish, after asking.'),

 H1('Publishing'),
 P('Tap <b>Publish</b>. A dialog asks you to confirm, saying how many products the store will have, and which are left out. Tap <b>Publish</b> in it to go ahead, or <b>Cancel</b> to change nothing.'),
 P('<b>The first time,</b> Android asks you to allow three things. Allow each one:'),
 bullets(['<b>Code On The Go installing apps</b> — needed once, to install Local Store Server.',
          '<b>All files access</b> for Local Store Server — so it can read your store\'s files.',
          '<b>Nearby devices</b> — asked only when the server needs to start its own hotspot: no Wi-Fi, and no Mobile hotspot.'], True),
 P('Publishing checks every file it needs, builds a new copy of the store beside the old one, installs or updates Local Store Server if needed, then starts it on the new copy and checks every file it serves. Until the new store is ready, buyers keep the old one — unless Local Store Server is being updated, which stops it briefly.'),
 H2('What the messages mean'),
 bullets(['<i>Published … files verified</i>: buyers can use the store now.',
          '<i>Left out</i>: products with nothing to download, and why.',
          '<i>Notes</i>: things to fix that don\'t stop a publish, such as an unsigned APK.',
          '<i>Couldn\'t publish</i>: nothing changed. The store still serves its previous version. The message says why.']),

 H1('Letting buyers in'),
 P('Open the <b>Local Store Server</b> app, and tap <b>Show QR codes for buyers</b>. Buyers scan the codes with their phone\'s camera: on a hotspot, the first joins its network, and the second opens the store. The <i>Local Store Server</i> guide covers networks, hotspots and PINs in detail.'),
 tip('<b>When buyers download an APK,</b> their phone asks them to allow their browser to install apps. That\'s normal for apps from outside the Play Store: they allow it, then can turn it off again afterwards. Some Samsung phones also need <b>Auto Blocker</b> turned off first.'),

 H1('Changing the store\'s look'),
 P('The store page is made from a template. The built-in one suits most stores. Under <b>Advanced</b>:'),
 bullets(['<b>Choose a template file</b> picks another template, such as the alternatives that come with Share Your Apps: <i>Neon Arcade</i>, <i>Morning Edition</i>, <i>Aurora Deck</i> and <i>Clear Access</i>.',
          '<b>Load the default template</b> puts the built-in one in the <b>Template override</b> field, to start editing from, or to go back to it.',
          '<b>Extra JSON</b>, for the whole store, holds settings templates use: <i>announcement</i>, a line above the products; <i>accent</i>, a colour; <i>contact</i>, your name, email, phone and web address, shown at the foot of the page so buyers can reach you; and <i>lang</i>, the page\'s language.']),
 P('Every publish also makes an <b>accessible version</b> of the store page, plain and in large text, which every other template links to from its top left.'),
 P('If you know a little Pebble, the template language, a template of your own can extend the built-in one and replace just one part — the header, each product\'s card, its download buttons, or the footer. Pieces shared between templates go in the folder <i>CodeOnTheGoProjects/ShareYourAppsTemplates</i>. The <i>README</i> beside the alternative templates explains how, with examples.'
   + (' Pebble\'s own documentation, explaining every tag, filter and function, is <b>Appendix A</b> of this guide.' if os.path.exists(os.path.join(HERE, 'pebble-book.pdf')) else ''), small),

 H1('Sharing your store with another seller'),
 P('Someone nearby can serve a copy of your store from their own phone, so it reaches people where you aren\'t. You do this in the Local Store Server app, with <b>Share this store…</b>: its guide explains how. To give them a newer version later, publish, then share again.'),

 H1('Getting help'),
 P('Long-press any heading, button or checkbox for a short explanation. <b>+ See more</b> gives more, with a button that opens a help page. Code On The Go adds a note to each that it comes from a plugin. Long-pressing a text field selects its text, as usual; its heading has the help.'),
 H2('If something goes wrong'),
 bullets(['<b>Couldn\'t publish:</b> read the message; it says what\'s missing, such as a file that\'s been moved. Nothing changed, so buyers still have the previous store.',
          '<b>A buyer can\'t install an app:</b> check its APK isn\'t marked unsigned, and see the tip under <i>Letting buyers in</i>.',
          '<b>A buyer can\'t open the store:</b> see the <i>Local Store Server</i> guide.']),
]

# ------------------------------------------------------------------ 2. The server app
server = [
 P('Local Store Server serves the store you publish with the Share Your Apps plugin, over Wi-Fi or a hotspot. People nearby open it in their phone\'s browser. The plugin installs and starts it for you, so you only open it to let buyers in, check on it, share your store, or change how it runs.'),
 P('Open it from your phone\'s app list. Everything below is on its <b>Manage server</b> screen, unless it says otherwise.'),

 H1('Status'),
 P('The <b>Status</b> panel says whether the server is serving, and which store. While it serves, it shows the store\'s address, with a button that copies it. The line below says what <b>Start</b> will serve.'),
 P('If <b>Grant storage access</b> shows, tap it and allow access, once: the server needs it to read your store\'s files.'),

 H1('Starting and stopping'),
 P('Publishing from the plugin starts the server for you. In the <b>Server</b> panel:'),
 bullets(['<b>Start</b> serves the last store you published.',
          '<b>Stop</b> stops serving. Downloads under way end.',
          '<b>Port</b> is the number in the store\'s address, 6175 unless you change it. Change it only if another app already uses that port.',
          '<b>Help</b> opens the help pages.']),

 H1('Letting buyers in'),
 P('Tap <b>Show QR codes for buyers</b>. Buyers scan the codes with their phone\'s camera:'),
 bullets(['<b>On a hotspot,</b> the first code joins its network, and the second opens the store.',
          '<b>On ordinary Wi-Fi,</b> buyers join the same network themselves. If you type the network\'s password, the screen can show a code that joins it for them. The password is never saved.']),
 P('<b>Done</b> closes the screen; the store keeps serving.'),
 tip('<b>A buyer\'s phone can\'t open the store?</b> A hotspot has no internet, so their phone may try mobile data instead. Have them turn on <b>airplane mode</b>, then turn <b>Wi-Fi</b> back on — and turn airplane mode off afterwards.'),

 H1('Which network buyers use'),
 bullets(['<b>Wi-Fi:</b> if your phone is on Wi-Fi, buyers join the same network.',
          '<b>Your phone\'s Mobile hotspot:</b> if it\'s on, and the phone isn\'t on Wi-Fi, the server uses it. Buyers join it with the name and password from your phone\'s Settings.',
          '<b>A hotspot of the server\'s own:</b> otherwise. With Wi-Fi switched on but not connected to a network, it\'s a <i>Wi-Fi Direct</i> hotspot, whose name and password stay the same every time. Without, it\'s Android\'s own hotspot, whose name and password change every time the server starts.']),
 P('On Android 9 to 12, the server\'s own hotspot won\'t start while <b>Location</b> is off. If so, the server says to turn Location on, then tap <b>Start</b>.'),
 H2('The Wi-Fi Direct hotspot\'s name and password'),
 P('In the <b>Wi-Fi Direct hotspot</b> panel. Its name always starts <i>DIRECT-LS-</i>. <b>Suggest a password</b> fills in a strong one, and <b>Save name &amp; password</b> keeps them for the next time the hotspot starts. Buyers who joined before must join again after a change.'),

 H1('The PIN'),
 P('A store can have a PIN, up to 6 digits, which buyers enter before reaching it. You set it in the plugin, under <b>Store</b>. It keeps casual visitors out, but isn\'t real security: the store doesn\'t use encryption.'),

 H1('When to run'),
 bullets(['<b>Run always</b> keeps serving whatever the screen does.',
          '<b>Pause when the screen turns off</b> saves battery. The server resumes when you unlock the phone.']),

 H1('Sharing your store with another seller'),
 P('Someone nearby can serve a copy of your store from their own phone. Their copy is served exactly as you published it: they can\'t change it, or pass it on. They set their own PIN, and it\'s deleted from their phone on the date you choose.'),
 bullets(['While your store is served, tap <b>Share this store…</b>, in the <b>Copies of this store</b> panel. Just after a publish, it may first say <i>Getting the store ready to share…</i>',
          'If their phone doesn\'t have Local Store Server, they scan the <b>small</b> QR code with its camera, and install the app it downloads from your phone.',
          'In Local Store Server, they tap <b>Import a store…</b>, then scan the <b>large</b> QR code, or type the code shown.',
          'Both phones show four digits. Check they\'re <b>the same</b>. Choose when their copy expires — in a week, in a month, on a date, or never — and tap <b>Allow</b>. If the digits differ, tap <b>Deny</b>.',
          'Keep the screen open until the copy has been sent.'], True),
 P('Both phones must be on the same Wi-Fi network, or theirs on your hotspot. Each code works for one phone, for 10 minutes. <b>Cancel</b>, or leaving the screen, ends the share. To give them a newer version later, publish, then share again: their new copy replaces the old one.'),
 tip('<b>A copy isn\'t copy protection.</b> Anything a store serves can be saved by anyone who opens it. What Share Your Apps prevents is a copy being passed on through Share Your Apps itself.'),
 P('Their side is described in <i>Serving an imported store</i>, a short guide you can give them.', small),

 H1('Importing someone else\'s store'),
 P('Your phone can serve someone else\'s store too, with their permission, in the <b>Import a store</b> panel. A phone that\'s serving a store can\'t import one: stop serving first. <i>Serving an imported store</i> explains the steps.'),

 H1('Logs'),
 P('The <b>Logs</b> panel records buyers\' requests and what the server does, newest first. <b>View logs</b> shows them; <b>Export logs</b> sends every log file off the phone, such as to a chat or by email; <b>Log size limit</b> sets how large each file grows, and 0 turns logging off.'),
 P('The logs include buyers\' network addresses and browser names, so share them only with people who need them. They\'re kept in the app\'s private storage, and aren\'t backed up.', small),

 H1('Getting help'),
 P('Long-press any heading or button for a short explanation. <b>+ See more</b> gives more, with a button that opens a help page. Long-pressing a text field selects its text, as usual; its label has the help. The faint <b>i</b> in a circle, in each explanation\'s corner, is for developers: it shows where the help text came from.'),
 H2('If something goes wrong'),
 bullets(['<b>A buyer can\'t open the store:</b> check they\'re on the same network, or joined your hotspot; then try the airplane mode tip above.',
          '<b>The hotspot won\'t start:</b> on Android 9 to 12, turn Location on. Otherwise, the Status panel says why.',
          '<b>A buyer can\'t install an app:</b> their phone must allow their browser to install apps; on some Samsung phones, <b>Auto Blocker</b> must be off too.',
          '<b>Reporting a problem:</b> say what you did and what happened, with a screenshot if you can, and send the logs with <b>Export logs</b>.']),
]

# ------------------------------------------------------------------ 3. Serving an imported store
seller = [
 P('Someone has given you a copy of their app store, so you can share their apps with people near you. This guide shows you how. You don\'t need internet, or an account.'),
 tip('<b>Your copy:</b> you can serve it to people near you, with your own PIN if you like. You can\'t change it, or pass it on to another phone. It may <b>expire</b> on a date the store\'s owner chose; then it\'s deleted from your phone.'),

 H1('1. Get the app'),
 P('If your phone doesn\'t have <b>Local Store Server</b> yet, the owner shows you a <b>small</b> QR code on their phone.'),
 bullets(['Scan it with your phone\'s camera, and open the link.',
          'Your phone downloads the app. Open the download and install it. If your phone asks, allow your browser to install apps; you can turn that off again afterwards.',
          'On some Samsung phones, turn off <b>Auto Blocker</b> in Settings first.'], True),
 P('Both phones must be on the same Wi-Fi network, or yours joined to the owner\'s hotspot.'),

 H1('2. Get the store'),
 bullets(['Open <b>Local Store Server</b>, and tap <b>Import a store…</b>.',
          'Scan the owner\'s <b>large</b> QR code with your camera, and open the link. Or type the code their phone shows, such as <i>LS-FM50-ZV7G</i>.',
          'Tap <b>Continue</b>.',
          'Your phone shows <b>four digits</b>. The owner checks their phone shows the same, and taps <b>Allow</b>.',
          'Wait while the store arrives. Keep both phones close, with their screens on.',
          'When it says <i>Imported</i>, tap <b>Serve it now</b>.'], True),
 P('If it stops partway, tap <b>Try again</b>, while the owner\'s phone still shows the code.'),
 tip('<b>Did you join the owner\'s hotspot to get the store?</b> Before serving, leave it: join your own Wi-Fi, or turn Wi-Fi off, so your phone makes its own hotspot. The last screen reminds you.'),

 H1('3. Let people in'),
 bullets(['In <b>Local Store Server</b>, tap <b>Show QR codes for buyers</b>.',
          'People scan the codes with their phone\'s camera. On a hotspot, the first code joins its network, and the second opens the store.',
          'They see the store in their browser, and can download its apps.'], True),
 tip('<b>Someone\'s phone can\'t open the store?</b> Have them turn on <b>airplane mode</b>, then turn <b>Wi-Fi</b> back on — and turn airplane mode off afterwards.'),

 H1('A PIN, if you want one'),
 P('In <b>Local Store Server</b>, the store has a panel with its name. Under <i>Set a PIN or leave blank for no PIN</i>, type up to 6 digits, then tap <b>Serve this store</b> — or, if it\'s already serving, <b>Serve it again, with this PIN</b>. People then enter your PIN before they reach the store.'),

 H1('Starting and stopping'),
 P('<b>Stop</b> stops serving; <b>Start</b> serves the store again. To save battery, choose <b>Pause when the screen turns off</b>: it starts again when you unlock the phone.'),

 H1('When the store expires'),
 P('When its date comes, the store is deleted from your phone, and Local Store Server says <i>A store has expired</i>. Tap <b>OK</b>. To serve it again, ask its owner for a new copy.'),
 P('<b>A newer version:</b> tap <b>Stop</b>, then get the store again from its owner, as in step 2. The new copy replaces the old one.'),
 P('<b>No longer need it?</b> Tap <b>Remove this store</b>, in its panel.'),

 H1('Help'),
 P('Long-press any heading or button in Local Store Server for a short explanation, then <b>+ See more</b> for more.'),
]

# ------------------------------------------------------------------ Markdown, for Help+Manual
def md_text(html):
    t = html.replace("\\'", "'")
    t = re.sub(r"<b>(.*?)</b>", r"**\1**", t, flags=re.S)
    t = re.sub(r"<i>(.*?)</i>", r"*\1*", t, flags=re.S)
    t = re.sub(r"<[^>]+>", "", t)
    return t.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").strip()

def md_blocks(flowable):
    """One flowable as Markdown: a heading, a paragraph, a list, or a note."""
    if isinstance(flowable, Paragraph):
        t = md_text(flowable.text)
        if flowable.style is h2: return f"## {t}"
        return t
    if isinstance(flowable, ListFlowable):
        numbered = flowable._bulletType == '1'
        items = [md_text(li._flowables[0].text) for li in flowable._flowables]
        return "\n".join((f"{k + 1}. " if numbered else "- ") + it for k, it in enumerate(items))
    if isinstance(flowable, Fig):
        sh = flowable.shot
        return (f"![Figure {sh['id']} · {md_text(sh['title'])}]({os.path.basename(screen_file(sh))})\n\n*Figure {sh['id']} · {md_text(sh['title'])}*\n\n" +
                "\n".join(f"{n}. {md_text(t)}" for n, t in sh["key"]))
    if isinstance(flowable, KeepTogether):   # a tip: its box holds one paragraph
        box = [f for f in flowable._content if isinstance(f, Table)][0]
        return "> " + md_text(box._cellvalues[0][0].text)
    return None

def safe(name):
    return re.sub(r'[\\/:*?"<>|]', "", re.sub(r"^\d+\.\s*", "", name)).strip()

def markdown(folder, name, subtitle, story, steps=False):
    """A guide as a folder of topics: the introduction first, then one per section. A tutorial (steps=True)
    has a topic per step instead; a section heading such as "On your phone" opens the steps that follow it."""
    d = os.path.join(OUTDIR, safe(name)); os.makedirs(d, exist_ok=True)
    topics = [["Introduction", [f"*{subtitle}*", f"*{STAMP}*"]]]; group = None
    for i, f in enumerate(story):
        if steps and isinstance(f, Paragraph) and f.style is h1:
            nxt = story[i + 1] if i + 1 < len(story) else None
            if isinstance(nxt, Paragraph) and nxt.style is h2: group = md_text(f.text); continue   # a group of steps
            topics.append([md_text(f.text), []]); group = None; continue
        if steps and isinstance(f, Paragraph) and f.style is h2:
            topics.append([md_text(f.text), [f"**{group}**"] if group else []]); continue
        if isinstance(f, Paragraph) and f.style is h1: topics.append([md_text(f.text), []]); continue
        b = md_blocks(f)
        if b: topics[-1][1].append(b)
    import shutil
    for f in story:
        if isinstance(f, Fig): shutil.copy(screen_file(f.shot), d)
    for k, (t, blocks) in enumerate(topics):
        with open(os.path.join(d, f"{k:02d} {safe(t)}.md"), "w", encoding="utf-8") as out:
            out.write(f"# {name if k == 0 else t}\n\n" + "\n\n".join(blocks) + "\n")
    return d, len(topics)

GUIDES = (
    ('share-your-apps-plugin-guide.pdf', 'Share Your Apps plugin — user guide', 'Describe your apps in Code On The Go, and publish them as a store for people nearby.', plugin),
    ('local-store-server-guide.pdf', 'Local Store Server — user guide', 'Serve your store over Wi-Fi or a hotspot, let buyers in, and share it with other sellers.', server),
    ('serving-an-imported-store.pdf', 'Serving an imported store', 'Share someone else\'s apps with people near you, with their permission.', seller))
# ------------------------------------------------------------------ tutorials: one task each, step by step
def fig(i):
    """A figure by its ID, placed by hand in a tutorial."""
    return Fig(next(x for x in SHOTS if x["id"] == i))
def step(n, title, *body):
    return [H2(f"Step {n}. {title}")] + [P(b) if isinstance(b, str) else b for b in body]

tut_create = [
 P('In about ten minutes, you\'ll turn an app you\'ve built in Code On The Go into a store that people nearby can open in their phone\'s browser, and download from.'),
 tip('<b>You\'ll need:</b> Android 9 or later, Code On The Go, the Share Your Apps plugin\'s <b>.cgp</b> file, and an app you\'ve built at least once, so it has an APK.'),
 *step(1, 'Install the plugin', 'In Code On The Go, open the <b>Addons Manager</b>, choose the <b>.cgp</b> file, and tap <b>Install</b>. A <b>Share Your Apps</b> tab appears in the bottom panel.', fig('P01')),
 *step(2, 'Name your store', 'In the <b>Share Your Apps</b> tab, under <b>Store</b>, type a <b>Store name</b>. Buyers see it at the top of the page. A description, a header picture and a PIN are optional; you can add them later.', fig('P02')),
 *step(3, 'Add your app', 'Under <b>Products</b>, tap <b>+ Add product</b>. Give it a <b>Name</b>, then tap <b>Choose</b> beside <b>Project</b> and pick your app\'s project, and <b>Choose</b> beside <b>APK</b> and pick its build. Leave <b>Publish the APK</b> ticked.', fig('P05')),
 *step(4, 'Add a picture, and describe it', 'Tap <b>+ Add media</b> and pick a screenshot of your app. Then tap <b>Describe</b> beside it, and say what it shows, for people who use a screen reader.', fig('P08')),
 *step(5, 'Publish', 'Tap <b>Publish</b>. A dialog says how many products the store will have. Tap <b>Publish</b> in it.', fig('P11'),
        'The first time, Android asks to let Code On The Go install apps: tap <b>Settings</b>, and allow it. It also asks for <b>All files access</b> for Local Store Server. Allow both; they\'re needed only once.', fig('P12')),
 *step(6, 'Check the result', 'When it\'s done, the bottom of the tab says <i>Published … files verified</i>. Your store is ready, and Local Store Server is already serving it.', fig('P13')),
 H1('Next'),
 P('To let people in, follow <i>Serving your first store</i>. To change anything, edit it in the tab and publish again: buyers keep the old version until the new one is ready.'),
]
tut_serve = [
 P('Your store is published. Now people nearby can open it: on the same Wi-Fi, or on your phone\'s hotspot when there\'s no Wi-Fi.'),
 *step(1, 'Open Local Store Server', 'Open <b>Local Store Server</b> from your phone\'s app list. <b>Status</b> says it\'s serving your store, with its address. If it says <i>Stopped</i>, tap <b>Start</b>.', fig('S01')),
 *step(2, 'Show the codes', 'Tap <b>Show QR codes for buyers</b>. On a hotspot, the first code joins its network, and the second opens the store. On ordinary Wi-Fi, buyers join the network themselves, and scan the store\'s code.', fig('S04')),
 *step(3, 'Let a buyer in', 'The buyer scans the codes with their phone\'s camera. The store opens in their browser. If you set a PIN, they type it first.', fig('B01'),
        'Each product has its pictures, its description, and its downloads. They tap <b>Download APK</b>, and their phone asks them to allow their browser to install apps.', fig('B02')),
 tip('<b>Their phone can\'t open the store?</b> A hotspot has no internet, so their phone may try mobile data instead. Have them turn on airplane mode, then Wi-Fi back on — and airplane mode off afterwards.'),
 *step(4, 'Choose when it runs', 'Under <b>When to run</b>, <b>Run always</b> keeps serving whatever the screen does. <b>Pause when the screen turns off</b> saves battery, and resumes when you unlock the phone.', fig('S07')),
 *step(5, 'Stop, and start again', 'In the <b>Server</b> panel, <b>Stop</b> stops serving, and <b>Start</b> serves the last store you published.', fig('S03')),
 H1('Next'),
 P('To let someone else serve your store from their own phone, follow <i>Sharing your first store with another phone</i>.'),
]
tut_share = [
 P('Someone nearby can serve a copy of your store from their own phone, so it reaches people where you aren\'t. They can\'t change it or pass it on, and it\'s deleted on the date you choose. You\'ll need both phones, on the same Wi-Fi network, or theirs on your hotspot.'),
 H1('On your phone'),
 *step(1, 'Start the share', 'While your store is served, open <b>Local Store Server</b>, and tap <b>Share this store…</b>, in <b>Copies of this store</b>.', fig('S08'),
        'The screen shows a large QR code, a code to type, and a smaller QR code, for a phone that doesn\'t have the app yet.', fig('S09')),
 H1('On their phone'),
 *step(2, 'Get the app, if needed', 'If their phone doesn\'t have Local Store Server, they scan your <b>small</b> QR code with the camera, and download the app from your phone. Their browser warns about apps from outside the Play Store: they tap <b>Download anyway</b>, then install it.', fig('I01')),
 *step(3, 'Ask for the copy', 'In Local Store Server, they tap <b>Import a store…</b>. They scan your <b>large</b> QR code, or type the code, and tap <b>Continue</b>.', fig('I03')),
 H1('On both phones'),
 *step(4, 'Check the four digits', 'Their phone shows four digits. Yours shows <i>Allow a copy?</i> with the same four. Check they match: that proves you\'re sharing with the phone in front of you.', fig('I04')),
 H1('On your phone'),
 *step(5, 'Allow it', 'Choose when their copy expires, and tap <b>Allow</b>. If the digits don\'t match, tap <b>Deny</b>.', fig('S10')),
 H1('On their phone'),
 *step(6, 'Serve it', 'When it says <i>Imported</i>, they tap <b>Serve it now</b>. If they joined your hotspot to get it, they leave it first, as the screen says.', fig('I05'),
        'Their copy has a panel of its own in Manage server, where they can set their own PIN, and remove it.', fig('I07')),
 H1('Next'),
 P('They let people in just as you do, with <b>Show QR codes for buyers</b>. To give them a newer version later, publish, then share again.'),
]
TUTORIALS = (
    ('tutorial-creating-your-first-store.pdf', 'Tutorial: creating your first store', 'From an app you\'ve built to a store people nearby can open.', tut_create),
    ('tutorial-serving-your-first-store.pdf', 'Tutorial: serving your first store', 'Let people nearby open your store, on Wi-Fi or your phone\'s hotspot.', tut_serve),
    ('tutorial-sharing-your-first-store.pdf', 'Tutorial: sharing your first store with another phone', 'Let someone nearby serve a copy of your store from their own phone.', tut_share))

GUIDE_NAMES = {"plugin": "Share Your Apps plugin — user guide", "server": "Local Store Server — user guide", "seller": "Serving an imported store"}

from html import escape as _esc

def shot_list():
    """Every screen the guides show, with how to capture the real one, so drawings can be replaced."""
    story = [
     P('The user guides show <b>%d screens</b>, with numbered callouts: <b>%d real screenshots</b>, and <b>%d drawings</b>, made from the apps\' code, for screens not yet captured. '
       'The list below says how to reach each screen, and what its callouts mark, so any can be captured again.' % (len(SHOTS), sum(1 for x in SHOTS if x.get("captured")), sum(1 for x in SHOTS if not x.get("captured")))),
     H2('Taking the screenshots'),
     bullets(['Use a phone with Code On The Go and Share Your Apps installed, and a store published, preferably the demo store.',
              'Set the phone to the theme the drawings use: <b>dark</b> for the Share Your Apps tab, as Code On The Go is shown here; either for Local Store Server.',
              'Capture the whole screen, in portrait, without cropping. Scroll so the items the callouts mark are on screen; a long screen can be two screenshots.',
              'Name each file by its ID, such as <b>P05.png</b>, and send them together. I\'ll add the callouts, and swap them into the guides.',
              '<b>Drawn: a real screenshot is still needed</b> marks the screens still shown as drawings.']),
    ]
    for guide in ("plugin", "server", "seller"):
        story.append(H1(GUIDE_NAMES[guide]))
        for x in [y for y in SHOTS if y["guide"] == guide]:
            from PIL import Image as PILImage
            path = screen_file(x); w, h = PILImage.open(path).size
            iw = 1.45 * inch; ih = min(iw * h / w, 3.0 * inch); iw = ih * w / h
            text = [P(f"<b>{x['id']}</b> · {x['title']}", caption),
                    P(f"<i>In the guide under:</i> {x['section']}", small),
                    P(("<font color='#0B6E6E'><b>Real screenshot.</b></font> " if x.get("captured") else "<font color='#E53935'><b>Drawn: a real screenshot is still needed.</b></font> ") + f"<b>How to get it:</b> {_esc(x['how'])}"),
                    P("<b>Callouts:</b> " + "; ".join(f'<font color="#E53935"><b>{n}</b></font> {t.rstrip(".")}' for n, t in x["key"]) + ".", keynum)]
            t = Table([[Image(path, iw, ih), text]], colWidths=[iw + 0.2 * inch, W - iw - 0.2 * inch])
            t.setStyle(TableStyle([('VALIGN', (0, 0), (-1, -1), 'TOP'), ('LEFTPADDING', (0, 0), (-1, -1), 0)] +
                                  ([('BOX', (0, 0), (0, 0), 0.5, FRAME)] if FRAMES and light_edges(path) else []) + [('LINEBELOW', (0, 0), (-1, -1), 0.4, colors.HexColor('#E3DEE8')),
                                   ('BOTTOMPADDING', (0, 0), (-1, -1), 8), ('TOPPADDING', (0, 0), (-1, -1), 6)]))
            story.append(KeepTogether([t]))
    return build("screenshot-list.pdf", "Screenshots for the user guides", "Each screen the guides show, how to reach it, and what its callouts mark.", story)

if SHOT_LIST:
    print(shot_list()); sys.exit()

for (f, n, st, story), guide in zip(GUIDES, ("plugin", "server", "seller")):
    story = with_figures(story, guide)
    if MARKDOWN: print(*markdown(f, n, st, story))
    else: print(build(f, n, st, story))
for f, n, st, story in TUTORIALS:
    if MARKDOWN: print(*markdown(f, n, st, story, steps=True))
    else: print(build(f, n, st, story))
