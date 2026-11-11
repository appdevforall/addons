"""Builds share-your-apps-tester-guide.pdf: one page, in the store's look.

Usage: python3 build_guide.py [output.pdf]   (default: share-your-apps-tester-guide.pdf beside this script)
Needs reportlab: pip install reportlab
"""
import os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "share-your-apps-tester-guide.pdf")
from reportlab.lib.pagesizes import letter
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, Image, ListFlowable, ListItem, KeepTogether, PageBreak
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib import colors
from reportlab.lib.units import inch
INK=colors.HexColor('#2B1B3D'); TEAL=colors.HexColor('#0B6E6E'); RED=colors.HexColor('#FF4F3A'); CREAM=colors.HexColor('#FFF3D6'); MUTED=colors.HexColor('#5B5068')
body=ParagraphStyle('b',fontName='Helvetica',fontSize=9.1,leading=11.6,textColor=INK,spaceAfter=2.5)
h2=ParagraphStyle('h2',fontName='Helvetica-Bold',fontSize=11.5,leading=13.5,textColor=TEAL,spaceBefore=6,spaceAfter=2.5)
small=ParagraphStyle('s',parent=body,fontSize=8.2,leading=10.2,textColor=MUTED)
def P(t,s=body): return Paragraph(t,s)
def bullets(items, numbered=False):
    return ListFlowable([ListItem(P(i),leftIndent=11,value=(n+1 if numbered else None)) for n,i in enumerate(items)],
                        bulletType='1' if numbered else 'bullet', start=1 if numbered else None, leftIndent=13, bulletFontSize=7.5 if not numbered else 9.1,
                        bulletColor=RED if not numbered else INK, bulletFontName='Helvetica-Bold' if numbered else 'Helvetica')
title=ParagraphStyle('t',fontName='Helvetica-Bold',fontSize=19,leading=22,textColor=colors.white)
sub=ParagraphStyle('st',fontName='Helvetica',fontSize=10,leading=13,textColor=CREAM)
W=7.5*inch
head=Table([[Image(os.path.join(HERE,'octopus.png'),0.72*inch,0.72*inch),[P('Share Your Apps — tester guide',title),P('Share the apps you build in Code On The Go with people nearby. No internet or accounts needed.',sub)]]],colWidths=[0.95*inch,W-0.95*inch])
head.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,-1),TEAL),('VALIGN',(0,0),(-1,-1),'MIDDLE'),('LEFTPADDING',(0,0),(-1,-1),10),('RIGHTPADDING',(0,0),(-1,-1),10),('TOPPADDING',(0,0),(-1,-1),8),('BOTTOMPADDING',(0,0),(-1,-1),8)]))
aw=Table([['']*20],colWidths=[W/20]*20,rowHeights=[7])
aw.setStyle(TableStyle([('BACKGROUND',(i,0),(i,0),RED if i%2==0 else CREAM) for i in range(20)]+[('LEFTPADDING',(0,0),(-1,-1),0),('RIGHTPADDING',(0,0),(-1,-1),0)]))
s=[aw,head,Spacer(1,6),
 P('Share Your Apps has two parts: the <b>Share Your Apps plugin</b>, which you install in Code On The Go, and the <b>Local Store Server</b> app, which the plugin installs for you. People nearby open your store in their phone\'s browser, and download your APK, your source, or both.'),
 P('<b>You\'ll need:</b> Android 9 or later, Code On The Go, and an app you\'ve built at least once, so it has an APK.'),
 P('1. Install the plugin',h2),
 bullets(['Download the <b>.cgp</b> file attached to ticket ADFA-6207.','In Code On The Go, open the <b>Addons Manager</b>. Uninstall any earlier Share Your Apps plugin, then install that file, and activate the plugin.','A <b>Share Your Apps</b> tab appears.'],True),
 P('2. Make your store',h2),
 bullets(['Enter a <b>store name</b>. The description and header image are optional.',
  'Tap <b>+ Add product</b>, then <b>Choose</b> its project and its APK. Tick <b>Publish the source</b> to share the source code too; build output and signing files are left out automatically.',
  'Descriptions are written in HTML. If a tag is opened but never closed, or similar, a red warning appears under the field. It won\'t stop you publishing.',
  'A <b>PIN</b> of up to 6 digits is optional. Buyers enter it before they can see the store.',
  'Long-press any heading or button for help, then <b>+ See more</b> for more, and a page.']),
 P('Everything saves as you type, but buyers only see changes after you publish.'),
 P('3. Publish',h2),
 P('Tap <b>Publish</b>. The first time, Android asks you to allow three things; allow each one:'),
 bullets(['<b>Code On The Go installing apps</b> — needed once, to install Local Store Server.','<b>All files access</b> for Local Store Server — so it can read your store.','<b>Nearby devices</b> — asked only when the server needs to start its own hotspot: no Wi-Fi, and no Mobile hotspot.'],True),
 P('It ends with <b>Published … files verified.</b> If a product has nothing to download, the message names it as <b>Left out</b>, and says why.'),
 P('4. Let buyers in',h2),
 P('Open the <b>Local Store Server</b> app and tap <b>Show QR codes for buyers</b>. Buyers scan the first code to join the network, then the second to open the store.'),
 bullets(['<b>On Wi-Fi:</b> buyers must be on the same network. To show a join code, type your Wi-Fi password on that screen. It\'s never saved.',
  '<b>Your phone\'s Mobile hotspot:</b> if it\'s on and you\'re not on Wi-Fi, the server uses it. To show a join code, type its name and password, from Settings, on that screen. They\'re never saved.',
  '<b>No Wi-Fi and no Mobile hotspot:</b> the server starts a hotspot of its own, and the codes carry its name and password. With Wi-Fi switched <b>on</b> but not connected, it keeps the same name and password every time; with Wi-Fi <b>off</b>, Android picks new ones each time.']),
 P('When buyers download an APK, their phone asks to allow their browser to install apps. That\'s normal for apps from outside the Play Store: allow it, then turn it off again afterwards. Some Samsung phones also need <b>Auto Blocker</b> turned off first; it turns itself back on.'),
 P('On a hotspot, which has no internet, a buyer\'s phone may try mobile data instead. If a buyer can\'t open the store, have them turn on airplane mode, then turn Wi-Fi back on — and to turn airplane mode off afterwards.'),
 PageBreak(),
 P('5. Share your store with another seller',h2),
 P('Someone nearby can serve a copy of your store from their own phone, so it reaches buyers where you aren\'t. They don\'t need Code On The Go.'),
 bullets(['On your phone: <b>Local Store Server</b> → <b>Manage server</b> → <b>Share this store…</b>',
  'If their phone doesn\'t have Local Store Server, they scan the <b>small</b> QR code with its camera, and install the app it downloads.',
  'They scan the <b>large</b> QR code with the camera and open the link — or, in Local Store Server, tap <b>Import a store…</b> and type the code.',
  'Check that both phones show the <b>same four digits</b>. Choose when their copy expires, and tap <b>Allow</b>.',
  'They tap <b>Serve it now</b>, with a PIN of their own if they like.'], numbered=True),
 P('Their copy is served exactly as you published it: they can\'t change it, or pass it on. It\'s deleted from their phone on the date you chose. To give them a newer version, share again.'),
 P('Both phones must be on the same Wi-Fi network, or theirs on your hotspot. A phone that\'s serving a store can\'t import one: stop serving it first.',small),
 P('Good to know',h2),
 bullets(['<b>The PIN keeps casual visitors out, but isn\'t real security.</b> The store doesn\'t use encryption, so anyone on the same network could see what\'s sent.',
  '<b>Battery:</b> under <b>When to run</b> in Local Store Server, <b>Pause when the screen turns off</b> saves battery. The server and its hotspot pause while the screen is off, and resume when you unlock the phone.',
  '<b>Hotspot name and password:</b> if you change them, buyers who joined before must join again.']),
 KeepTogether([P('Reporting a problem',h2),
 P('Add a comment to ADFA-6207 with what you did, what you expected, and what happened instead; a screenshot if possible; and the logs — in Local Store Server, tap <b>Export logs</b>.'),
 P('The logs include buyers\' network addresses and browser names, so only share them on the ticket.',small)]),
]
doc=SimpleDocTemplate(OUT,pagesize=letter,leftMargin=0.5*inch,rightMargin=0.5*inch,topMargin=0.4*inch,bottomMargin=0.4*inch,
                      title='Share Your Apps — tester guide',author='App Dev for All',subject='ADFA-6207')
doc.build(s)
