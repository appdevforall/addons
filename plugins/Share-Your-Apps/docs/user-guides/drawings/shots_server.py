from kit import *
SHOTS = []
SV = '#E2E5EA'
def shot(id, guide, section, title, html, key, how, real=False):
    SHOTS.append(dict(id=id, guide=guide, section=section, title=title, html=html, key=key, how=how, real=real))
def sp(title, *c): return page(S.screen(title, *c), SV)
T = S.text; INK = S.INK
URL = "http://192.168.1.5:6175/"
def status(line, url, last, qr_co=None, url_co=None, line_co=None, last_co=None):
    items = [co(line_co, T(line, 16, bold=True, color=INK)) if line_co else T(line, 16, bold=True, color=INK)]
    if url: items.append(co(url_co, T(url, 16, color=S.LINK)) if url_co else T(url, 16, color=S.LINK))
    b = S.btn('Show QR codes for buyers'); items.append(co(qr_co, b) if qr_co else b)
    items.append(co(last_co, T(last, 15)) if last_co else T(last, 15))
    return S.panel('Status', *items)

# ------------------------------------------------ the server app guide
shot('S01', 'server', 'Status', 'Manage server, serving',
    sp('Local Store Server', S.HINT,
       status('Serving Store_48213576', URL, 'Start serves the last store published: Store_48213576', qr_co=3, url_co=2, line_co=1, last_co=4),
       S.server_panel()),
    [(1, 'What the server is doing, and which store.'), (2, "The store's address. The button beside it copies it."), (3, 'The codes buyers scan.'), (4, 'What <b>Start</b> will serve.')],
    'Open Local Store Server while it serves your store, and capture the top of Manage server.')
shot('S02', 'server', 'Status', 'Storage access still needed',
    sp('Local Store Server', S.HINT, S.panel('Status', T('Grant storage access first.', 16, bold=True, color=INK),
       co(1, S.btn('Grant storage access')), T('Start serves the last store published: Store_48213576', 15))),
    [(1, "Tap it and allow access, once: the server needs it to read your store's files.")],
    "Before allowing All files access to Local Store Server, open it. (Or turn the access off in Android's Settings, capture, then turn it on again.)")
shot('S03', 'server', 'Starting and stopping', 'The Server panel',
    sp('Local Store Server', S.panel('Server', S.row(co(1, T('Port', 15, color=S.MUTED, extra=' margin: 0;'), inline=True), S.edit('6175', width=110)),
       S.row(co(2, S.btn('Start'), grow=True), co(3, S.btn('Stop'), grow=True), co(4, S.btn('Help'), grow=True)))),
    [(1, "The port, the number in the store's address. Its label has help; the field keeps its long-press for text."), (2, '<b>Start</b> serving.'), (3, '<b>Stop</b> serving.'), (4, 'The help pages.')],
    'In Manage server, capture the Server panel.')
shot('S04', 'server', 'Letting buyers in', 'The codes buyers scan, on a hotspot',
    sp('Serve', S.card(co(1, S.text('1. Join this Wi-Fi network', 20, bold=True, color=INK) + S.qr(170) + T('DIRECT-LS-VDHX · password s5Fyp6EhcpjWANj3', 13, center=True))),
       S.card(co(2, S.text('2. Open the store', 20, bold=True, color=INK) + S.qr(170) + T(URL, 14, center=True, color=S.LINK))),
       co(3, T("This network has no internet. If a buyer's phone can't open the store, have them turn on airplane mode, then turn Wi-Fi back on. Remind them to turn airplane mode off afterwards.", 14)),
       S.btn('Done')),
    [(1, "The first code joins buyers to the hotspot."), (2, 'The second opens the store.'), (3, "The tip for a buyer whose phone can't open it.")],
    'Turn Wi-Fi off, tap Start, then Show QR codes for buyers. Capture the whole screen, scrolling if needed.')
shot('S05', 'server', 'Letting buyers in', 'Making a join code for ordinary Wi-Fi',
    sp('Serve', S.card(S.text('1. Join this Wi-Fi network', 20, bold=True, color=INK),
       co(1, T('For you, not buyers: to show a code for joining this Wi-Fi network, enter its password. It isn\'t saved.', 13, color=S.MUTED)),
       S.edit('Home Wi-Fi'), co(2, S.edit('••••••••••')), co(3, S.btn('Show the join code')))),
    [(1, 'What this is for: making a code that joins buyers to your Wi-Fi.'), (2, "The network's password, never saved."), (3, 'Show the code.')],
    "On ordinary Wi-Fi, open Show QR codes for buyers, and capture the part asking for the network's password.")
shot('S06', 'server', 'The Wi-Fi Direct hotspot\'s name and password', 'The Wi-Fi Direct hotspot panel',
    sp('Local Store Server', S.panel('Wi-Fi Direct hotspot', T('When this phone isn\'t on Wi-Fi, the server starts a hotspot that buyers join. It uses this name and password.', 14),
       co(1, T('Network name', 15, color=S.MUTED) + S.row(T('DIRECT-LS-', 16, color=S.MUTED, extra=' margin:0;'), S.edit('VDHX'))),
       co(2, T('Password', 15, color=S.MUTED) + S.edit('s5Fyp6EhcpjWANj3')),
       S.row(co(3, S.btn('Save name & password'), grow=True), co(4, S.btn('Suggest a password'), grow=True)))),
    [(1, 'Its name, after DIRECT-LS-.'), (2, 'Its password, 8 to 63 characters.'), (3, 'Keep them, for the next time the hotspot starts.'), (4, 'Fill in a strong password.')],
    'In Manage server, capture the Wi-Fi Direct hotspot panel.')
shot('S07', 'server', 'When to run', 'When to run',
    sp('Local Store Server', S.panel('When to run', co(1, S.radio('Run always', True)), co(2, S.radio('Pause when the screen turns off')))),
    [(1, 'Keep serving whatever the screen does.'), (2, 'Save battery: pause when the screen turns off, and resume on unlocking.')],
    'In Manage server, capture the When to run panel.')
shot('S08', 'server', 'Sharing your store with another seller', 'Starting a share',
    sp('Local Store Server', S.panel('Copies of this store', T(S.SHARE_CAPTION), co(1, S.btn('Share this store…')), T('You can set an expiration date.', 13, color=S.MUTED))),
    [(1, 'Share a copy of your store, while it serves.')],
    'While your store serves, capture the Copies of this store panel.')
shot('S09', 'server', 'Sharing your store with another seller', 'The share code',
    sp('Share this store', S.card(S.heading('Share this store'),
        T("On the other phone, open Local Store Server and tap Import a store. Then scan this code with the phone's camera, or type it."),
        co(1, S.qr(210)),
        co(2, S.row(f'<span style="font-family: \'Roboto Mono\', monospace; font-size: 28px; font-weight: 700; color: {INK}">LS-FM50-ZV7G</span>', f'<span style="padding:10px">{S.copyicon()}</span>', center=True, gap=4)),
        co(3, T('This code works for one phone, for 9 more minutes.', 13))),
       S.card(T('No Local Store Server on the other phone?', 15, bold=True, color=INK), T('Scan this first with its camera, to download the app from this phone and install it.', 14), co(4, S.qr(130))),
       S.btn('Cancel')),
    [(1, 'The large code: the other phone scans it to import.'), (2, 'Or the other phone types this code; the icon copies it.'), (3, 'One phone, for 10 minutes.'), (4, "For a phone without the app: a small code, or a link to open in its browser, with a button that copies it, to download Local Store Server from this phone.")],
    'Tap Share this store…, and capture the whole screen, scrolling if needed.')
shot('S10', 'server', 'Sharing your store with another seller', 'Allowing a copy',
    sp('Share this store', S.card(S.heading('Allow a copy?'), T("Daniel's S23 Ultra is asking for a copy of Top Android apps.", 16),
        co(1, S.checkbox('Check that their screen shows', '42 17')),
        T('The copy expires', 14, bold=True, color=INK),
        co(2, S.radio('In a week') + S.radio('In a month · Oct 27, 2026', True) + S.radio('On a date') + S.radio('Never'))),
       S.row(co(3, S.btn('Deny'), grow=True), co(4, S.btn('Allow'), grow=True))),
    [(1, 'These four digits must match the other phone\'s.'), (2, 'When their copy expires.'), (3, "<b>Deny</b>, if the digits don't match."), (4, '<b>Allow</b>, if they do.')],
    'With a second phone importing, capture the Allow a copy? screen, before choosing.')
shot('S11', 'server', 'Importing someone else\'s store', 'The Import a store panel',
    sp('Local Store Server', S.panel('Import a store', co(1, T(S.IMPORT_CAPTION)), co(2, S.btn('Import a store…')))),
    [(1, 'Serve a copy of someone else\'s store, with their permission.'), (2, "Start importing. It's disabled, saying why, while the phone serves a store.")],
    'With the server stopped, capture the Import a store panel.')
shot('S12', 'server', 'Logs', 'The Logs panel',
    sp('Local Store Server', S.panel('Logs', co(1, T('Log size limit (MB)', 15, color=S.MUTED) + S.edit('10')),
       S.row(co(2, S.btn('View logs'), grow=True), co(3, S.btn('Export logs'), grow=True)),
       T('Log folder: /data/user/0/org.appdevforall.localstore.server/files/logs', 12, color=S.MUTED))),
    [(1, 'How large each log file grows; 0 turns logging off.'), (2, 'Read the logs, newest first.'), (3, 'Send the log files off the phone.')],
    'In Manage server, capture the Logs panel.')
def tip(expanded):
    body = (co(1, T('The number in the store\'s address, 6175 unless you change it.', 15, color='#222'), grow=True) +
            co(2, '<span style="font:16px Roboto;color:#222;opacity:.35;padding:0 4px">ⓘ</span>', inline=True))
    more = (T('Change it only if another app already uses that port; buyers\' codes change with it.', 14, color='#222') + co(3, S.btn('Serving the store'))) if expanded \
        else co(3, '<span style="font:15px Roboto;color:#485D92;text-decoration:underline">+ See more</span>', inline=True)
    return sp('Local Store Server', S.panel('Server', S.row(T('Port', 15, color=S.MUTED, extra=' margin: 0;'), S.edit('6175', width=110))),
              f'<div style="background:#fff;border-radius:10px;border:1px solid rgba(0,0,0,.2);box-shadow:0 4px 14px rgba(0,0,0,.25);padding:14px;margin-top:-12px">'
              f'<div style="display:flex;gap:6px;align-items:flex-start">{body}</div>{more}</div>')
shot('S13', 'server', 'Getting help', 'A tooltip',
    tip(False), [(1, 'A short explanation, after long-pressing the Port label.'), (2, 'For developers: where the help came from.'), (3, '<b>+ See more</b>, for more.')],
    'Long-press the Port label, and capture the tooltip.')
shot('S14', 'server', 'Getting help', 'After + See more',
    tip(True), [(1, 'The short explanation.'), (2, 'For developers.'), (3, 'The help page, opening at this topic.')],
    'Long-press the Port label, tap + See more, and capture the tooltip.')

# ------------------------------------------------ the imported-store guide
shot('I01', 'seller', '1. Get the app', "Scanning the owner's small code",
    android_dialog('Download local-store-server.apk?', 'From 192.168.49.1 · 11 MB', [('Cancel', None), ('Download', 1)], note="Your browser draws this: replace with a real screenshot."),
    [(1, 'Download the app, then open it to install.')],
    "On the second phone, scan the owner's small QR code with the camera, open the link, and capture the browser's download prompt.", real=True)
shot('I02', 'seller', '2. Get the store', 'Starting an import',
    sp('Local Store Server', S.HINT, status('Stopped', None, 'Nothing published yet — publish from the Share Your Apps plugin, or import a store, below.'),
       S.panel('Import a store', T(S.IMPORT_CAPTION), co(1, S.btn('Import a store…')))),
    [(1, 'Tap <b>Import a store…</b>.')],
    'On a phone with Local Store Server but no store, open Manage server.')
shot('I03', 'seller', '2. Get the store', 'The code, and your phone\'s name',
    sp('Import a store', S.card(S.heading('Import a store'), T("Scan the owner's QR code with this phone's camera, or type the code shown on their phone."),
        co(1, T('Code', 14, bold=True, color=INK, extra=' margin-bottom: 0;') + S.edit('LS-FM50-ZV7G', mono=True, size=22)),
        co(2, T("Your phone's name", 14, bold=True, color=INK, extra=' margin-bottom: 0;') + S.edit("Daniel's S23 Ultra"))),
       co(3, S.btn('Continue'))),
    [(1, "The owner's code, filled in from their QR code, or typed."), (2, 'What the owner sees.'), (3, 'Ask for the copy.')],
    'Tap Import a store…, type a code, and capture the screen before tapping Continue.')
shot('I04', 'seller', '2. Get the store', 'The four digits, then the store arriving',
    sp('Import a store', S.card(S.heading('Top Android apps'), co(1, S.checkbox('Their screen should show', '42 17')),
        S.step('1 Found the store', 'done'), S.step('2 The owner allowed it', 'done'), co(2, S.step('3 Receiving the store', 'now') + S.progress(64) + T('81 files · 4.1 of 6.4 MB', 14)), S.step('4 Ready to serve', 'todo')),
       T('Keep both phones close, with their screens on.', 13), S.btn('Cancel')),
    [(1, "These digits must match the owner's screen."), (2, 'The store arriving.')],
    'After Continue, and the owner allowing it, capture the screen while it receives the store.')
shot('I05', 'seller', '2. Get the store', 'Imported',
    sp('Import a store', S.card(S.heading('Imported Top Android apps'), T('81 files · 6.4 MB. It expires on Oct 27, 2026.'),
        T("It's served as it was published, with your own PIN. It can't be changed or passed on.", 14),
        co(1, T("This phone is on the owner's network. To serve the store yourself, leave it first: join your own Wi-Fi, or turn Wi-Fi off, so this phone starts its own hotspot.", 14, color=S.DANGER))),
       co(2, S.btn('Serve it now')), S.btn('Done')),
    [(1, "Shown only if you joined the owner's hotspot: leave it first."), (2, 'Start serving the store.')],
    "Import over the owner's hotspot, and capture the last screen.")
shot('I06', 'seller', '2. Get the store', 'If it stops partway',
    sp('Import a store', S.card(S.heading('Top Android apps'), T("The import failed: the connection to the owner's phone was lost.", 15, color=S.DANGER)),
       co(1, S.btn('Try again')), S.btn('Start over')),
    [(1, "Try again, while the owner's phone still shows the code.")],
    'Start an import, turn Wi-Fi off on the second phone partway, then on, and capture the screen.')
shot('I07', 'seller', 'A PIN, if you want one', 'Your copy, and its PIN',
    sp('Local Store Server', S.panel('Top Android apps', T('Read-only · Expires Oct 27, 2026 · in 30 days', 15, bold=True, color=INK),
        T("An imported copy of someone else's store. You can serve it; it can't be changed or passed on.", 14),
        co(1, S.btn('Serve this store')),
        co(2, T('Set a PIN or leave blank for no PIN', 15, bold=True, color=INK, extra=' margin-top: 8px;') + S.row(S.edit('••••', width=110), T('Up to 6 digits. Buyers enter it before reaching the store.', 13, extra=' margin: 0;'))),
        co(3, S.btn('Remove this store', color=S.DANGER)))),
    [(1, 'Serve it, with the PIN below.'), (2, 'Your own PIN, or none.'), (3, 'Remove it from this phone.')],
    "With a copy imported and not serving, capture the store's panel in Manage server.")
shot('I08', 'seller', '3. Let people in', 'The codes, for your buyers',
    sp('Local Store Server', S.HINT, status('Serving a copy of Top Android apps', 'http://192.168.43.1:6175/', 'Start serves the imported copy of Top Android apps, below.', qr_co=1)),
    [(1, 'Show the codes people scan.')],
    'While serving the copy, capture the top of Manage server.')
shot('I09', 'seller', 'When the store expires', 'After the store expires',
    sp('Local Store Server', S.panel('A store has expired', T('Your copy of Top Android apps has expired, and has been deleted from this phone. Buyers can no longer open it. To serve it again, ask its owner for a new copy.'), co(1, S.btn('OK')))),
    [(1, 'Close the notice.')],
    "After a copy's date has passed, open Manage server. To see it sooner, import with In a week, then move the phone's date 8 days ahead; turn automatic date and time back on afterwards.")
