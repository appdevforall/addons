#!/usr/bin/env python3
"""Builds web_server_documentation.db, the Share Your Apps Server app's three-tier help.

Same tables and columns as CodeOnTheGo's documentation.db, so its queries work unchanged.
Every page is plain HTML (compression 'none', templateId 0), which needs neither Brotli nor
Pebble to read. Run it, then copy the .db into app/src/main/assets/:

    python3 build_help_db.py ../app/src/main/assets/web_server_documentation.db
"""
import sqlite3, sys, os

CATEGORY = "shareyourapps_server"
CHUNK = 1024 * 1024  # CodeOnTheGo's CONTENT_CHUNK_SIZE: longer rows continue as <path>-1, <path>-2, ...

# The same pale beige as the tooltips, so every tier of help looks alike; code on a slightly deeper beige.
# In dark mode, the same warm dark brown as the tooltips (Palette.help), with light text.
STYLE = """<style>html,body{background:#F5ECD7}body{font-family:sans-serif;line-height:1.5;max-width:40em;margin:0 auto;padding:1em;color:#222}
h1{font-size:1.4em}h2{font-size:1.15em;margin-top:1.4em}code{background:#EADFC6;padding:0 .2em;border-radius:3px}
a{color:#233490}li{margin:.3em 0}
@media (prefers-color-scheme:dark){html,body{background:#3A332A}body{color:#EDE6D8}a{color:#A9BCF5}code{background:#4A4135}}</style>"""

def page(title, body):
    return f"<!doctype html><html><head><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\"><title>{title}</title>{STYLE}</head><body><h1>{title}</h1>{body}</body></html>"

PAGES = {
    "index.html": page("Share Your Apps Server", """
<p>Share Your Apps Server serves the store you publish with the <b>Share Your Apps</b> plugin in Code On The Go.
People on the same Wi-Fi network or hotspot open it in their phone's browser to download your apps and their source.</p>
<h2>How it fits together</h2>
<ol>
<li>In Code On The Go, open the <b>Share Your Apps</b> tab, fill in your catalog, and tap <b>Publish</b>.</li>
<li>The plugin builds a new copy of the store and starts this server on it. You stay in Code On The Go.</li>
<li>This screen then shows <i>Serving</i> and the store's address, with a button beside it that copies it.</li>
</ol>
<h2>Keeping it running</h2>
<p>The server runs as a foreground service, shown by a notification, so it keeps serving while you use other apps.
Under <b>When to run</b>, choose <i>Run always</i>, or <i>Pause when the screen turns off</i> to save battery; it resumes when you unlock the phone.</p>
<p><b>Start</b> serves the last store you published, or, on a phone holding an imported copy, the copy. To change what's in the store, publish again from the plugin.</p>
<h2>No Wi-Fi? A hotspot</h2>
<p>If you've turned on your phone's own Mobile hotspot in Settings, the server serves on it; buyers join it with the name and password from Settings.
Turning it off stops the server.</p>
<p>Otherwise, if the phone isn't on Wi-Fi when the server starts, it starts a hotspot of its own, and Manage Server shows its name and password.
Buyers join that network, then open the store's address. Switch Wi-Fi on (without joining a network) to get a Wi-Fi Direct hotspot,
whose name and password stay the same every time.</p>
<h2>Storage access</h2>
<p>The server needs permission to read the store's files from shared storage. If <b>Grant storage access</b> is showing,
tap it and allow access; it only needs doing once.</p>
<h2>Help topics</h2>
<ul>
<li><a href="serving.html">Serving the store</a>: Status, Start and Stop, and the codes buyers scan</li>
<li><a href="hotspot.html">Hotspots</a>: when there's no Wi-Fi</li>
<li><a href="sharing.html">Sharing your store</a> with another seller</li>
<li><a href="importing.html">Importing a store</a> from another seller</li>
<li><a href="copies.html">Imported copies</a>: serving, PINs, removing, and expiry</li>
<li><a href="running.html">When to run, and logs</a></li>
<li><a href="log.html">Reading the access log</a></li>
<li><a href="mcp.html">MCP control</a>: letting apps on this phone control the server</li>
</ul>"""),
    "log.html": page("Reading the access log", """
<p>The log shows what the server has done, newest lines first. It keeps at most five files and starts a new one when
the current file reaches the size limit you set. A limit of 0 turns logging off.</p>
<h2>Requests</h2>
<p>Each request buyers make is one line, in the standard Apache format: the phone's address, the time, what was asked for,
the result code, the bytes sent, the browser, and the transfer rate. Code <code>200</code> means the file was sent,
<code>302</code> is a redirect such as to the PIN page, and <code>404</code> means there was no such file.</p>
<h2>Status lines</h2>
<p>The server's own lines start with the time, then a tag in brackets, for example
<code>[03/Oct/2026:17:30:20 -0700] [server] serving …</code>. The tags are:</p>
<ul>
<li><code>[server] serving …</code> — the server started, with the store it serves and its address.</li>
<li><code>[server] stopped serving …: reason</code> — it stopped, and why.</li>
<li><code>[server] failed to start: …</code> — it couldn't start, for the reason given.</li>
<li><code>[gzip] …</code> — the server compressing the page and source zips in the background, so they download faster. Once a source zip's compressed copy is done, the zip itself is deleted, to save space — <code>kept only the copy</code> — and buyers are served from the copy.
<code>not smaller</code> means a compressed copy wouldn't have saved anything, so it was discarded.</li>
<li><code>[hotspot] …</code> — the hotspot turning on or off.</li>
<li><code>[sound] …</code> — <b>Play sound</b> and <b>Listen</b>: the tones played, a code heard (with which repeat it came from and how
strong the start signal was), nothing heard, or the self-test's result. A hotspot password heard in the tones is never written to the log.</li>
</ul>
<p><a href="index.html">Back to Share Your Apps Server</a></p>"""),
    "serving.html": page("Serving the store", """
<h2 id=status>Status</h2>
<p>The Status panel says whether the server is serving, and which store: your own, or an imported copy. While it serves, it shows
the store's address, with a button that copies it. The line below says what <b>Start</b> will serve.</p>
<h2 id=start>Start, Stop and the port</h2>
<p><b>Start</b> serves the last store you published, or an imported copy, on a phone that holds one. <b>Stop</b> stops serving;
downloads under way end. The port is the number in the store's address, 6175 unless you change it. Change it only if another app
already uses that port; buyers' codes change with it.</p>
<h2 id=serve>The codes buyers scan</h2>
<p><b>Show QR codes for buyers</b> opens the Serve screen. On a hotspot, the first code joins buyers to its network, and the second
opens the store. On ordinary Wi-Fi, buyers join the same network themselves; the screen can make a join code if you type the
network's name and password, which are never saved. <b>Done</b> closes the screen; the store keeps serving.</p>
<p>A hotspot has no internet, so a buyer's phone may try mobile data instead, and fail to open the store. The Serve screen says so:
have them turn on airplane mode, then turn Wi-Fi back on, and turn airplane mode off afterwards.</p>
<h2 id=grant>Storage access</h2>
<p>The server reads the store's files from shared storage. If <b>Grant storage access</b> shows, tap it and allow access, once.</p>
<p><a href="index.html">Back to Share Your Apps Server</a></p>"""),
    "hotspot.html": page("Hotspots", """
<h2 id=kinds>Which network buyers join</h2>
<ul>
<li><b>Wi-Fi:</b> if the phone is on Wi-Fi, buyers join the same network.</li>
<li><b>Your phone's Mobile hotspot:</b> if it's on, and the phone isn't on Wi-Fi, the server uses it. Buyers join it with the name and
password from Settings.</li>
<li><b>A hotspot of the server's own:</b> otherwise. With Wi-Fi switched on but not connected, it's a Wi-Fi Direct hotspot, whose name
and password stay the same. Without, it's Android's own hotspot, whose name and password change every time.</li>
</ul>
<h2 id=wifi-direct>The Wi-Fi Direct hotspot's name and password</h2>
<p>Its name is <code>DIRECT-LS-</code> and up to 22 more: letters, digits, spaces and punctuation count 1 each, accented letters 2,
and emoji 4. Its password is 8 to 63 letters, digits, spaces and ordinary punctuation, with no accented letters or emoji.
<b>Suggest a password</b> fills in a strong one; <b>Save name &amp; password</b> keeps them, for the next time the hotspot starts.
Buyers who joined before must join again after a change.</p>
<h2 id=location>Location, on Android 9 to 12</h2>
<p>These versions won't start the server's own hotspot while Location is off. The server says so: turn Location on, then tap <b>Start</b>.</p>
<p><a href="index.html">Back to Share Your Apps Server</a></p>"""),
    "sharing.html": page("Sharing your store", """
<p>Someone nearby can serve a copy of your store from their own phone. The copy is served exactly as you published it: they can't change
it, or pass it on. They set their own PIN, and it's deleted from their phone on the date you choose.</p>
<h2 id=share>Sharing</h2>
<ol>
<li>While your own store is served, tap <b>Share this store…</b>. Right after a publish, it may first say <i>Getting the store ready to share…</i></li>
<li>On their phone, they open Share Your Apps Server and tap <b>Import a store</b>, then scan the large QR code, or type the code.</li>
<li>You check the four digits and choose <b>Allow</b>, and the copy is sent.</li>
</ol>
<p>Each code serves one phone, for 10 minutes, and closes after five wrong codes. Both phones must be on the same Wi-Fi network, or
theirs on your hotspot. <b>Cancel</b>, or leaving the screen, ends the share; rotating the phone doesn't. <b>Share again</b> makes a
new code; <b>Done</b> closes the screen.</p>
<h2 id=app>A phone without Share Your Apps Server</h2>
<p>They scan the small QR code with the camera, and install the app it downloads from your phone. Then they scan the large one.</p>
<h2 id=sound>Play sound</h2>
<p><b>Play sound</b> plays the code as a short run of tones, so the other phone can hear it instead of scanning it: on their phone,
they tap <b>Listen</b> on the import screen. It repeats until their phone connects, for up to 30 seconds; <b>Stop sound</b> ends it sooner.
Turn the media volume up, and hold the phones close.</p>
<p>On a hotspot of the server's own, the tones also carry its name and password, so the other phone can join it. Anyone close
enough to hear them gets them too, as anyone who sees the Serve screen does. On other Wi-Fi, the tones never carry a password.</p>
<h2 id=allow>Allow or Deny</h2>
<p>The four digits prove you're talking to the phone in front of you, not another on the same network. If they don't match both
screens, tap <b>Deny</b>; nothing is sent.</p>
<h2 id=expiry>When the copy expires</h2>
<p>In a week, in a month, on a date you choose, or never. On or after that date, it's deleted from their phone. To give them a
newer version, share again.</p>
<p><a href="index.html">Back to Share Your Apps Server</a></p>"""),
    "importing.html": page("Importing a store", """
<h2 id=import>Importing</h2>
<ol>
<li>Tap <b>Import a store…</b> in Manage server, or scan the owner's QR code with the camera and open the link.</li>
<li>The code is filled in, or type it; letters can be either case. Your phone's name is what the owner sees.</li>
<li>Tap <b>Continue</b>.</li>
</ol>
<h2 id=listen>Listen</h2>
<p>Instead of scanning or typing the code, tap <b>Listen</b>, and ask the owner to tap <b>Play sound</b> on their share screen.
The first time, Android asks to let the app use the microphone; it listens only while this screen shows <i>Listening…</i>, for up to
30 seconds, and Android shows its microphone indicator meanwhile. <b>Stop listening</b> ends it sooner.</p>
<p>While it listens, the screen counts down the seconds left and shows a <b>Microphone level</b> meter, in dB: it rises with any sound
the microphone hears, voices and noise too, and is empty when it's quiet. The line above it says whether the tones themselves are heard:
<i>Nothing heard yet</i>, <i>Hearing something faint</i> (hold the phones closer), or <i>Hearing the tones</i>.
Turning the phone doesn't stop listening.</p>
<p>Once it hears the code, the import starts by itself, with your phone's name as shown. If it hears nothing, hold the phones closer,
ask the owner to turn up the media volume, and try again.</p>
<p>If this phone can already reach the owner's phone, the import starts at once. If the owner's phone serves on a hotspot of its own,
the tones carry its name and password: on Android 10 and later, Android asks whether to connect to it; tap <b>Connect</b> within
30 seconds. This phone uses that hotspot only for the import, and leaves it when the import ends. On Android 9, or without the tap,
the screen shows the hotspot's name and password: join it in Wi-Fi settings, then tap <b>Continue</b>.</p>
<p>If the owner's phone is on another Wi-Fi network, the tones carry its name, never its password: if the import can't find the
owner's phone, join that network, then tap <b>Continue</b>.</p>
<h2 id=check>The four digits</h2>
<p>Both screens show the same four digits. The owner checks them before allowing the copy.</p>
<h2 id=progress>While it arrives</h2>
<p>Keep both phones close, with their screens on. You can leave the screen: a notification shows its progress. <b>Cancel</b> stops,
keeping nothing; a copy you already had stays as it was.</p>
<h2 id=failed>If it fails</h2>
<p><b>Try again</b> repeats the transfer while the owner's share screen stays open, with no new code. <b>Start over</b> begins again,
with a new code.</p>
<h2 id=cant>Can't import a store here</h2>
<p>A phone that's serving a store can't import one, so a copy is never replaced under a running server. Stop serving first, or
import on another phone.</p>
<h2 id=done>When it's imported</h2>
<p><b>Serve it now</b> starts serving the copy; <b>Done</b> leaves it for later. If the import happened over the owner's own network,
leave it before serving: join your own Wi-Fi, or turn Wi-Fi off, so the phone starts its own hotspot.</p>
<p><a href="index.html">Back to Share Your Apps Server</a></p>"""),
    "copies.html": page("Imported copies", """
<h2 id=panel>The copy's panel</h2>
<p>Manage server shows an imported copy in a panel of its own, with its name and expiry. It's served exactly as published: it can't be
changed, or passed on. A phone holds one copy at a time.</p>
<h2 id=serve>Serving it</h2>
<p><b>Serve this store</b> serves the copy. On a phone with no store of its own, <b>Start</b> serves it too. Publishing from the
plugin serves your own store instead, keeping the copy.</p>
<h2 id=pin>Its PIN</h2>
<p>Up to 6 digits, which buyers enter before reaching the store, or none. It's yours, not the owner's.</p>
<h2 id=remove>Removing it</h2>
<p><b>Remove this store</b> asks first, then deletes the copy, stopping the server if it's serving it.</p>
<h2 id=expiry>When it expires</h2>
<p>On or after its date, the copy is deleted from the phone, and Manage server says <i>A store has expired</i> until you tap <b>OK</b>.
Downloads under way finish first. To serve it again, ask its owner for a new copy.</p>
<p><a href="index.html">Back to Share Your Apps Server</a></p>"""),
    "running.html": page("When to run, and logs", """
<h2 id=modes>When to run</h2>
<p><b>Run always</b> keeps serving whatever the screen does. <b>Pause when the screen turns off</b> saves battery, and resumes when you
unlock the phone.</p>
<h2 id=logs>Logs</h2>
<p>The log records buyers' requests and what the server does. <b>Log size limit</b> sets how large each file grows; 0 turns logging
off. <b>View logs</b> shows the newest lines first, and <b>Export logs</b> sends every log file off the phone. The log folder is
private to the app, since it records buyers' addresses. See <a href="log.html">Reading the access log</a>.</p>
<p><a href="index.html">Back to Share Your Apps Server</a></p>"""),
    "mcp.html": page("MCP control", """
<p>MCP control lets apps on this phone, such as an AI assistant, control Share Your Apps Server through the Model Context Protocol (MCP).
It's off until you turn it on, and only apps on this phone can reach it: other devices on the network can't.</p>
<h2 id=on>Turning it on</h2>
<p>Turn on <b>MCP control</b> in the Server panel. The app first says what it allows; tap <b>Turn on</b>, then allow the microphone
if Android asks. A notification shows while
it's on, with a <b>Turn off</b> button. The line under the switch says whether it's running.</p>
<p>After the phone restarts, it starts again the next time you open the app.</p>
<h2 id=limits>What apps can and can't do</h2>
<p>Apps can start and stop the server, share this store and import a store. They can't change the store or approve a copy:
a person still taps <b>Allow</b> on the sharing phone.</p>
<h2 id=share>When an app shares this store</h2>
<p>The app gets a code for the other phone, as <b>Share this store…</b> shows. When the other phone asks for a copy, a notification
says so: tap it to open the share screen, check that the four digits match both phones, choose when the copy expires, and tap
<b>Allow</b> or <b>Deny</b>. Nothing is sent until you do.</p>
<p>Opening <b>Share this store…</b> while an app's share is under way shows that share, rather than making a new code, so the app's
code keeps working. Leaving the screen leaves the app's share open; <b>Cancel</b> ends it. A share you open yourself still ends
when you leave the screen.</p>
<p>If <i>Pause when the screen turns off</i> is chosen, the server pauses when the screen turns off, which ends any share.</p>
<h2 id=import>When an app imports a store</h2>
<p>The app gives the code or link from the other phone, and the import starts as if you'd typed it on the import screen. The other
phone's owner still checks the four digits and taps <b>Allow</b>. An app can't import while this phone is serving, starting or paused:
it must stop the server first, and it can't start the server while an import is running.</p>
<p>An app's import replaces an imported copy of a different store without asking. Replacing a newer copy of the same store with an
older one still needs you: a notification asks <i>Import an older copy?</i>. Tap it and answer within 2 minutes, or the copy on
this phone is kept. This phone's own store is never touched.</p>
<p>While any import runs, its notification has a <b>Cancel</b> button. If a transfer fails partway, tap <b>Try again</b> on the
import screen; apps can't retry it.</p>
<h2 id=sound>Sound</h2>
<p>An app can also use sound instead of a code. On the sharing phone, it plays the share's code as tones, as <b>Play sound</b> does,
for up to 30 seconds. On the importing phone, it listens for them, as <b>Listen</b> does, for up to 30 seconds, and starts the import
once it hears the code. Android shows its microphone indicator while it listens. If the sharing phone serves on its own hotspot,
Android asks to join it: tap <b>Connect</b>. Apps are never told the hotspot's password.</p>
<p>The line under the switch says if the microphone isn't allowed, or works only once the app is opened, as after the phone
restarts. Without it, a person uses <b>Listen</b> on the import screen instead.</p>
<h2 id=token>The token</h2>
<p>An app must send the token with every request. <b>Copy token</b> copies it without showing it; paste it into the app's settings.
<b>New token</b> replaces it, and apps using the old one are refused until they get the new one. Keep it as private as a password.</p>
<h2 id=connect>Connecting an app</h2>
<p>The address is <code>http://127.0.0.1:6176/mcp</code>, using MCP's Streamable HTTP transport. The app sends the token in the
header <code>Authorization: Bearer</code>, followed by the token.</p>
<h2 id=problems>If it doesn't start</h2>
<p>If the line under the switch says <i>Failed</i>, another app may be using port 6176. Close that app, then turn MCP control off and
on again. The store's own port can't be 6176, as that's kept for MCP control.</p>
<p><a href="index.html">Back to Share Your Apps Server</a></p>"""),
    # A fixed page for checking that Tier 3 works end to end.
    "x.html": page("Help test page", "<p>If you can read this, in-app help pages load from the help database.</p>"),
}

TOOLTIPS = [  # tag, summary (Tier 1), detail (Tier 2), [(button text, page)]
    ("server.overview", "<b>Share Your Apps Server</b><br>Serves the store you publish from Code On The Go.",
     "<p>Publish from the <b>Share Your Apps</b> tab in Code On The Go, and this app serves the result to phones on the same network.</p>",
     [("About Share Your Apps Server", "index.html")]),
    ("server.status", "Whether the server is running, and what it's serving.",
     "<p>While it's serving, the store's address appears below, with a button beside it that copies it.</p>", []),
    ("server.hotspot", "The hotspot buyers join when this phone isn't on Wi-Fi.",
     "<p>When the phone isn't on a Wi-Fi network, the server starts a hotspot of its own. It tries a Wi-Fi Direct group first, "
     "which keeps the same name and password every time, so phones that joined before rejoin by themselves. That needs "
     "Android 10 or later and Wi-Fi switched on. Otherwise Android's own hotspot is used, and Android picks a new name and password each time.</p>"
     "<p>Buyers join it like any Wi-Fi network, using the name and password shown here, then open the store's address. "
     "The hotspot turns off when the server stops.</p>"
     "<p>If you've turned on your phone's own <b>Mobile hotspot</b> in Settings, the server uses that instead, and this line says so. "
     "Its name and password are in Settings; type them on the QR code screen to show buyers a join code. Buyers on it can use your "
     "phone's mobile data too. Turning it off stops the server.</p>", []),
    ("server.serve", "Show buyers the QR codes for joining the network and opening the store.",
     "<p>Opens a full-screen page to hold up for buyers: one code joins the network, the other opens the store in their browser. "
     "The screen stays on while it's showing.</p>"
     "<p>On a hotspot, the first code is made for you. On Wi-Fi, type the network's password on that page to show the first code; "
     "it's never saved. Without it, buyers join the Wi-Fi themselves and scan only the second code.</p>", []),
    ("server.serve_wifi", "Optional: your Wi-Fi password, to make a code buyers scan to join.",
     "<p>Android doesn't let apps read the password of a network the phone has joined, so type it here if you want a join code. "
     "It's kept only while this screen is open, and is never saved. The code contains the password, so anyone who scans it can join the network.</p>", []),
    ("server.copies", "Give another seller a copy of this store, to serve from their phone.",
     "<p>Someone nearby can serve a copy of this store from their own phone, so it reaches buyers where you aren't. "
     "The copy is served exactly as you published it, with their own PIN: they can't change it, or pass it on to anyone else.</p>"
     "<p>You choose when each copy expires; on that date it's deleted from their phone. To give them a newer version, share again.</p>", []),
    ("server.share", "Show a code for the other phone, then allow or deny its request.",
     "<p>Shows a QR code, and the same as a code to type. The other phone scans or types it in Share Your Apps Server; then you see "
     "its name and a four-digit check code, and choose when the copy expires. Nothing is sent until you tap <b>Allow</b>.</p>"
     "<p>Only works while the server is serving. Both phones must be on the same Wi-Fi network or hotspot.</p>", []),
    ("share.code", "The other phone scans this, or types the code.",
     "<p>The code works for one phone, for 10 minutes. After five wrong codes it stops working, so it can't be guessed. "
     "Keep this screen open: sharing stops when you leave it.</p>", []),
    ("share.app", "Gives the other phone the app itself, if it doesn't have it.",
     "<p>The other phone's camera opens this in its browser, which downloads Share Your Apps Server from this phone: the same app, "
     "signed the same way. Android then asks to install it, and the first time, whether to let the browser install apps. "
     "Both phones must be on the same network. It's only offered while this screen is open.</p>", []),
    ("share.allow", "Check the four digits match the other screen, then choose.",
     "<p>The digits prove you're talking to the phone in front of you, not another on the same network. "
     "If they don't match, tap <b>Deny</b>.</p>", []),
    ("share.expiry", "When the copy is deleted from their phone.",
     "<p>In a week, in a month, on a date you choose, or never. Until then they can serve it as you published it. "
     "To extend it, share again: a newer copy replaces the old one, with its new date.</p>", []),
    ("share.sound", "Play the code as tones, for the other phone to hear.",
     "<p>On the other phone, they tap <b>Listen</b> on the import screen. The tones repeat until that phone connects, for up to "
     "30 seconds; <b>Stop sound</b> ends them sooner. Turn the media volume up and hold the phones close.</p>"
     "<p>On this phone's own hotspot, the tones also carry its name and password, so anyone close enough to hear them gets them. "
     "On other Wi-Fi, they never carry a password.</p>", []),
    ("import.listen", "Hear the code from the owner's phone, instead of scanning or typing it.",
     "<p>Ask the owner to tap <b>Play sound</b> on their share screen. The first time, Android asks to let the app use the microphone. "
     "It listens for up to 30 seconds, only while this screen shows <i>Listening…</i>; <b>Stop listening</b> ends it sooner. "
     "Once it hears the code, the import starts by itself.</p>", []),
    ("server.import", "Serve a copy of someone else's store, from this phone.",
     "<p>With the owner's permission, this phone gets a copy of their store, and can serve it to buyers nearby with its own PIN. "
     "It's served as the owner published it: it can't be changed, or passed on. It's deleted on the date the owner chose.</p>"
     "<p>A phone serving its own store can't import one: stop serving it first, or import on another phone.</p>", []),
    ("import.code", "The code the owner's phone shows, or scan its QR code instead.",
     "<p>Scan the QR code with this phone's camera app, and open the link it offers: the code is filled in for you. Or type the code: "
     "LS, then two groups of four letters and digits. Letters can be upper or lower case, and O and 0 are the same.</p>", []),
    ("import.name", "What the owner sees when you ask.",
     "<p>It starts as this phone's own name, from Android's settings. Change it to something the owner will recognise.</p>", []),
    ("import.check", "Tell the owner these four digits, or check they match theirs.",
     "<p>The same four digits show on both phones. If they match, the owner knows the request really comes from this phone.</p>", []),
    ("server.copy", "A copy of someone else's store, imported to this phone.",
     "<p>It's served exactly as its owner published it: it can't be changed, or passed on to anyone else. "
     "On its expiry date it's deleted from this phone. To serve it longer, or to get a newer version, import a new copy from its owner.</p>", []),
    ("server.copy_serve", "Serve the copy to buyers nearby, from this phone.",
     "<p>Buyers use it exactly as they would any store: <b>Show QR codes for buyers</b> works the same way. "
     "Publishing your own store from Code On The Go serves that instead, and keeps the copy for later.</p>", []),
    ("server.copy_pin", "Optional: a PIN buyers enter before reaching the store.",
     "<p>Up to six digits, or leave it blank for no PIN. It's yours, not the owner's: it's never part of the copy. "
     "A change applies the next time you serve the store.</p>", []),
    ("server.copy_remove", "Delete the copy from this phone.",
     "<p>Stops serving it, if it's being served, and deletes it. To serve it again, ask its owner for a new copy.</p>", []),
    ("server.copy_expired", "An imported copy expired, and was deleted.",
     "<p>Its owner chose when it would expire. To serve the store again, ask its owner for a new copy. Tap <b>OK</b> to dismiss this.</p>", []),
    ("server.hotspot_name", "The name buyers see for this phone's hotspot, after DIRECT-LS-.",
     "<p>Android puts <code>DIRECT-LS-</code> in front, and allows 22 more: letters, digits, spaces and punctuation count 1 each, "
     "accented letters 2, and emoji 4. Buyers who joined before won't reconnect by themselves if you change it: they join again, "
     "with the new details on the Serve screen.</p>", []),
    ("server.hotspot_password", "The hotspot's password: 8 to 63 characters.",
     "<p>Letters, digits, spaces and ordinary punctuation, with no accented letters or emoji, which some phones can't type. "
     "If you change it, buyers who joined before must join again.</p>", []),
    ("server.hotspot_save", "Keep this name and password. They take effect the next time the hotspot starts.",
     "<p>The hotspot already running keeps its old details until the server stops and starts again.</p>", []),
    ("server.hotspot_suggest", "Fill in a strong, random password.",
     "<p>Sixteen random letters and digits. Tap <b>Save name &amp; password</b> to keep it.</p>", []),
    ("share.cancel", "End the share: the code stops working.",
     "<p>A phone that hasn't finished importing can't. Leaving this screen does the same for a share you opened here; rotating the phone doesn't. "
     "A share an app started through MCP control stays open when you leave the screen: Cancel ends it.</p>", []),
    ("share.deny", "Refuse this request: nothing is sent.",
     "<p>The other phone is told you denied it. To share with a phone after all, tap <b>Share again</b> for a new code.</p>", []),
    ("share.again", "Make a new code, for another phone, or to try again.",
     "<p>Each code serves one phone, so every share needs its own.</p>", []),
    ("share.done", "Close this screen. The share has ended.", "<p>Nothing more is sent.</p>", []),
    ("import.cant", "Close this screen.",
     "<p>A phone that's serving a store can't import one, so a copy is never replaced under a running server. "
     "Stop serving first, in Manage server, or import on another phone.</p>", []),
    ("import.continue", "Ask the owner's phone for a copy, with this code.",
     "<p>The owner's phone then shows your phone's name and four digits, and asks whether to allow it. After a failed transfer, "
     "<b>Start over</b> begins again, with a new code from the owner.</p>", []),
    ("import.retry", "Try the transfer again, while the owner's share screen is still open.",
     "<p>It uses the same request, so the owner needn't make a new code or allow it again.</p>", []),
    ("import.cancel", "Stop importing. Nothing is kept.",
     "<p>A copy you already had, if any, stays as it was.</p>", []),
    ("import.serve_now", "Start serving the copy now.",
     "<p>It's served with the PIN set for it in Manage server, in the store's panel, or with none if there isn't one. "
     "You can change the PIN there at any time.</p>", []),
    ("import.done", "Close this screen, without serving yet.",
     "<p>The copy stays on this phone. Serve it whenever you like, from Manage server.</p>", []),
    ("serve.close", "Close this screen.", "<p>Start the server, or publish from the Share Your Apps plugin, and the codes appear here.</p>", []),
    ("serve.done", "Close this screen. The store keeps serving.", "<p>Open it again any time with <b>Show QR codes for buyers</b>.</p>", []),
    ("serve.show_join", "Show the QR code that joins buyers to this network.",
     "<p>Buyers scan it with their camera to join, then scan the store's own code, which appears after it.</p>", []),
    ("server.view_logs", "See every log file, with search and filters.",
     "<p>Opens the full log. Choose a file — <i>Current</i>, or an older one by age — search it, or show only requests or only the "
     "server's own status lines. Pinch to zoom, and swipe to move around. <b>Share</b> sends the file you're looking at.</p>", []),
    ("server.export_logs", "Send all the log files off the phone.",
     "<p>Opens Android's share sheet with every log file attached, so you can save them to Drive, email them, or send them in a chat.</p>", []),
    ("server.log_folder", "Where the logs are kept on this phone.",
     "<p>Tap the path to select it. The folder is private to this app, because the logs record buyers' addresses, so other apps and "
     "file managers can't open it. Use <b>Export logs</b> to send the logs elsewhere.</p>", []),
    ("server.p2p_settings", "The name and password of the Wi-Fi Direct hotspot.",
     "<p>When this phone isn't on Wi-Fi, the server starts a Wi-Fi Direct hotspot with this name and password. The name always begins "
     "with DIRECT-LS-, which Android requires; choose the rest, up to 22 plain letters. The password needs 8 to 63 ordinary characters.</p>"
     "<p>Changes take effect the next time the hotspot starts: tap Stop, then Start. Phones that joined under the old name or password "
     "won't rejoin by themselves. If Wi-Fi Direct can't start, Android's own hotspot is used instead, and Android chooses its name and password.</p>", []),
    ("server.grant", "Lets the server read your published store.",
     "<p>The store's files are in shared storage, so the server needs permission to read them. It only needs granting once.</p>", []),
    ("server.last_published", "What Start will serve.",
     "<p><b>Start</b> always serves the last store you published from the plugin. To change it, publish again.</p>", []),
    ("server.port", "The port the store is served on. The default is 6175.",
     "<p>A number from 1024 to 65535. Change it only if another app already uses 6175. It takes effect the next time the server starts.</p>", []),
    ("server.start", "Start serving the last store you published.",
     "<p>If the server is already running, it restarts. The notification shows while it's serving.</p>", []),
    ("server.stop", "Stop serving.",
     "<p>Phones can't reach the store until you start the server again or publish from the plugin.</p>", []),
    ("server.run_mode", "Keep serving always, or pause while the screen is off.",
     "<p><i>Pause when the screen turns off</i> saves battery. While the screen is off, the server and its hotspot pause; "
     "they resume by themselves when you unlock the phone. The choice applies immediately.</p>"
     "<p>If the server is using Android's own hotspot, rather than a Wi-Fi Direct one, Android picks a new network name and "
     "password each time it resumes, so buyers need to scan the join code again.</p>", []),
    ("server.log_size", "How big the access log can grow, in MB. 0 turns logging off.",
     "<p>From 0 to 100. The server keeps at most five log files. A new limit takes effect the next time the server starts.</p>", []),
    ("server.log", "What the server has done, newest first.",
     "<p>It updates every 2 seconds. Long lines scroll sideways.</p>", [("Reading the access log", "log.html")]),
    ("server.mcp", "Lets apps on this phone control the server, through MCP. Off until you turn it on.",
     "<p>Apps on this phone, such as an AI assistant, can start and stop the server, share this store and import a store. "
     "They can't change the store or approve a copy: you still tap <b>Allow</b>. Other devices on the network can't reach it.</p>"
     "<p>Apps can also play the share code as sound, and use the microphone to hear another phone's, only while they ask it to.</p>"
     "<p>The line below says whether it's running. After the phone restarts, it starts again when you next open the app.</p>", []),
    ("server.mcp_token", "The password apps send to use MCP control.",
     "<p><b>Copy token</b> copies it without showing it, to paste into the app's settings. <b>New token</b> replaces it; "
     "apps using the old one are refused until they get the new one. Keep it as private as a password.</p>", []),
]

# Tier 3 for every entry: the page, and section, each tag's button opens.
TIER3 = [
    (("server.status", "server.last_published"), "Serving the store", "serving.html#status"),
    (("server.port", "server.start", "server.stop"), "Serving the store", "serving.html#start"),
    (("server.grant",), "Serving the store", "serving.html#grant"),
    (("server.serve", "server.serve_wifi", "serve.close", "serve.done", "serve.show_join"), "The codes buyers scan", "serving.html#serve"),
    (("server.hotspot",), "Hotspots", "hotspot.html#kinds"),
    (("server.p2p_settings", "server.hotspot_name", "server.hotspot_password", "server.hotspot_save", "server.hotspot_suggest"), "The hotspot's name and password", "hotspot.html#wifi-direct"),
    (("server.copies", "server.share", "share.code", "share.cancel", "share.again", "share.done"), "Sharing your store", "sharing.html#share"),
    (("share.app",), "A phone without Share Your Apps Server", "sharing.html#app"),
    (("share.sound",), "Play sound", "sharing.html#sound"),
    (("import.listen",), "Listen", "importing.html#listen"),
    (("share.allow", "share.deny"), "Allow or Deny", "sharing.html#allow"),
    (("share.expiry",), "When the copy expires", "sharing.html#expiry"),
    (("server.import", "import.code", "import.name", "import.continue"), "Importing a store", "importing.html#import"),
    (("import.check",), "The four digits", "importing.html#check"),
    (("import.cancel",), "While it arrives", "importing.html#progress"),
    (("import.retry",), "If it fails", "importing.html#failed"),
    (("import.cant",), "Can't import a store here", "importing.html#cant"),
    (("import.serve_now", "import.done"), "When it's imported", "importing.html#done"),
    (("server.copy",), "Imported copies", "copies.html#panel"),
    (("server.copy_serve",), "Serving a copy", "copies.html#serve"),
    (("server.copy_pin",), "A copy's PIN", "copies.html#pin"),
    (("server.copy_remove",), "Removing a copy", "copies.html#remove"),
    (("server.copy_expired",), "When a copy expires", "copies.html#expiry"),
    (("server.run_mode",), "When to run", "running.html#modes"),
    (("server.log_size", "server.view_logs", "server.export_logs", "server.log_folder"), "Logs", "running.html#logs"),
    (("server.mcp",), "MCP control", "mcp.html#on"),
    (("server.mcp_token",), "The token", "mcp.html#token"),
]
def tier3(tag):
    for tags, label, uri in TIER3:
        if tag in tags: return [(label, uri)]
    return []

def build(out):
    if os.path.exists(out): os.remove(out)
    db = sqlite3.connect(out)
    # 1 KB pages: of every page size, this gives the smallest database once compressed in the APK, and
    # keeps it at 55 KB on the phone, against 98 KB with SQLite's default 4 KB pages.
    db.execute("PRAGMA page_size = 1024")
    db.executescript("""
CREATE TABLE DocumentationDatabaseVersion (version INTEGER NOT NULL);
CREATE TABLE Languages (id INTEGER PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE ContentTypes (id INTEGER PRIMARY KEY, value TEXT NOT NULL, compression TEXT NOT NULL DEFAULT 'none');
CREATE TABLE Templates (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE, content BLOB NOT NULL);
CREATE TABLE CompressionDictionary (id INTEGER PRIMARY KEY, dictionary BLOB NOT NULL);
CREATE TABLE Content (path TEXT NOT NULL, content BLOB NOT NULL,
  contentTypeID INTEGER NOT NULL REFERENCES ContentTypes(id),
  languageId INTEGER NOT NULL REFERENCES Languages(id),
  templateId INTEGER NOT NULL DEFAULT 0);
CREATE UNIQUE INDEX ContentPath ON Content(path, languageId);
CREATE TABLE TooltipCategories (id INTEGER PRIMARY KEY, category TEXT NOT NULL UNIQUE);
CREATE TABLE Tooltips (id INTEGER PRIMARY KEY, categoryId INTEGER NOT NULL REFERENCES TooltipCategories(id),
  tag TEXT NOT NULL, summary TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '', UNIQUE(categoryId, tag));
CREATE TABLE TooltipButtonNumbers (id INTEGER PRIMARY KEY, value INTEGER NOT NULL);
CREATE TABLE TooltipButtons (tooltipId INTEGER NOT NULL REFERENCES Tooltips(id), description TEXT NOT NULL,
  uri TEXT NOT NULL, buttonNumberId INTEGER NOT NULL REFERENCES TooltipButtonNumbers(id));
""")
    db.execute("INSERT INTO DocumentationDatabaseVersion VALUES (1)")
    db.execute("INSERT INTO Languages VALUES (1, 'en')")
    db.execute("INSERT INTO ContentTypes VALUES (1, 'text/html', 'none')")
    db.executemany("INSERT INTO TooltipButtonNumbers VALUES (?, ?)", [(i, i) for i in range(10)])
    for path, html in PAGES.items():
        data = html.encode("utf-8")
        parts = [data[i:i + CHUNK] for i in range(0, len(data), CHUNK)] or [b""]
        for n, part in enumerate(parts):
            db.execute("INSERT INTO Content (path, content, contentTypeID, languageId) VALUES (?, ?, 1, 1)",
                       (path if n == 0 else f"{path}-{n}", part))
    cat = db.execute("INSERT INTO TooltipCategories (category) VALUES (?)", (CATEGORY,)).lastrowid
    for tag, summary, detail, buttons in TOOLTIPS:
        buttons = buttons or tier3(tag)
        tid = db.execute("INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (?, ?, ?, ?)",
                         (cat, tag, summary, detail)).lastrowid
        for i, (text, uri) in enumerate(buttons):
            db.execute("INSERT INTO TooltipButtons VALUES (?, ?, ?, ?)", (tid, text, uri, i))
    db.commit()
    db.execute("VACUUM")
    db.close()

if __name__ == "__main__":
    build(sys.argv[1] if len(sys.argv) > 1 else "web_server_documentation.db")
