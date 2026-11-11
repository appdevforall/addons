# Share Your Apps — QA steps

**You'll need two phones:**

- **Phone A, the owner's:** Android 9 or later, with Code On The Go, and one or two apps you've built, so each has an APK.
- **Phone B, a buyer's:** any Android phone with a browser and a camera. In section J, it's also a second seller: it gets Share Your Apps Server from Phone A, and needs no Code On The Go.

Install the plugin with the tester guide attached to this ticket. For each step that fails, add a comment with the step number, what happened, a screenshot, and the logs: in Share Your Apps Server, tap **Export logs**.

Some public and guest Wi-Fi networks stop phones reaching each other. If Phone B can't open the store on Wi-Fi, try a home network, or the hotspot tests in section F.

## A. Install

1. **Install the plugin.** In Code On The Go, open the **Addons Manager**. If an earlier Share Your Apps plugin is installed, uninstall it first: this one is a release build, signed with a different key, so it may be refused otherwise. Then install the `.cgp` from this ticket, and activate it.
   **Expected:** a **Share Your Apps** tab appears.
2. **Open the tab.**
   **Expected:** **Manage Catalog**, grouped into **Store**, **Products** and **Advanced**, with a **Publish** bar at the bottom. It's light or dark to match your phone's setting.

## B. The catalog

1. **Fill it in.** Enter a store name. Tap **+ Add product**, then **Choose** a project and its APK.
   **Expected:** the product's card shows `Source –` and `APK ✓` with the APK's size, in green.
2. **It's kept.** Leave Code On The Go, then return to the tab. Then close Code On The Go completely and reopen it.
   **Expected:** the catalog is exactly as you left it both times.
3. **Rotation and font size.** While typing in a field, rotate the phone. Then change the phone's font size and come back.
   **Expected:** nothing you typed is lost.
4. **HTML warnings.** Type `<b>bold` into the store description.
   **Expected:** a red warning under the field says `<b> is opened but never closed`. Add `</b>` at the end, and the warning disappears.
5. **Warnings on closed cards.** Type `<i>oops` into a product's description, then tap the product's name to close its card.
   **Expected:** the closed card shows a one-line warning about the description's HTML.
6. **Help.** Long-press a heading, such as **PIN (optional)**, then tap **+ See more**, then the button under the detail.
   **Expected:** a short tooltip appears — not *n/a* — then more detail, with a button, *The store's PIN*. It opens the plugin's help page at *PIN*, with no internet needed. Long-pressing the PIN field itself still selects its text, as usual.

7. **A product's Extra JSON.** In a product, type `{"whatsNew": "Faster", "license": "MIT", "author": "You", "website": "https://example.org"}` into **Extra JSON**. Then type something invalid, such as `{oops`.
   **Expected:** while it's invalid, the note under the field says *Not valid yet*, and the last valid value is kept. Long-pressing the heading lists the four keys.

8. **Describing a picture, and captions for a video.** On a product with a picture, tap **Describe** beside it, write what it shows, and **Save**. On one with a video, tap **Describe**, then **Choose captions file**, and pick a `.vtt` file. Publish.
   **Expected:** the list shows *described* and *captions* beside them. On the store page, a screen reader — or, on a computer, the browser's inspector — gives the picture your description, not *…, picture 1 of 2*. The video offers captions, in its menu or its **CC** button.

## C. Publishing

1. **First publish.** Tap **Publish**. A dialog asks you to confirm, saying how many products the store will have; tap **Cancel**. Then tap **Publish** again, and **Publish** in the dialog. Allow each thing Android asks for: installing apps, **All files access**, and **Nearby devices** if you're not on Wi-Fi.
   **Expected:** **Cancel** changes nothing, and nothing starts. After confirming, it ends with `Published Store_… — … files verified.`
2. **A product with nothing to download.** Add a product without choosing an APK, and publish.
   **Expected:** the message ends with `Left out: <its name> has no APK chosen, and Publish the source isn't ticked.`
3. **Removing a product.** Remove a product, publish, then open the store on Phone B.
   **Expected:** the product is gone from the store.
4. **Only one store folder.** In a file manager, open `CodeOnTheGoProjects`.
   **Expected:** exactly one folder named `Store_` and 8 digits.

5. **The timestamp.** Publish, and read the message under the button. Then turn on **Use 24-hour format** in Android's date and time settings, and publish again.
   **Expected:** the message starts with the date and time, then a dot, then *Published*, on one line. After the switch, the time is in 24-hour form, such as `16:32:10`.

6. **The accessible version.** Open the store. Tap **Accessible version**, at the top left.
   **Expected:** a plain, large-text page with every product, and *Standard version* at its top left, leading back.

7. **A publish that updates the server.** Install a newer plugin, as testers do with each build, and publish.
   **Expected:** Android asks to update Share Your Apps Server, and the store serves again once it's done. The version line at the foot of **Manage server** shows the new version.

8. **An unsigned APK.** Build an app with **assembleRelease** but no signing set up, so it makes `app-release-unsigned.apk`. Choose it for a product.
   **Expected:** the APK picker marks it *Unsigned: buyers can't install it*. Publishing goes ahead, and its message ends with a note that the APK isn't signed, so buyers can't install it. Choose a signed build again afterwards.

9. **Progress and timing.** Publish a store with at least one product whose source is ticked, and watch the message under the button.
   **Expected:** while the store is built, it changes every half second, naming what it's doing — such as *zipping Todo App's source* — with files and bytes handled, of their totals. When it's done, the message says *Published … files verified*, as before.

## D. A buyer on the same Wi-Fi

Put both phones on the same Wi-Fi network.

1. **The QR codes.** On Phone A, open **Share Your Apps Server** and tap **Show QR codes for buyers**. Enter the Wi-Fi password, then tap the keyboard's **Done** key.
   **Expected:** the keyboard closes, the fields shrink to `Joining "…" · Change`, and two codes appear: one to join the network, one to open the store.
2. **Open the store.** On Phone B, scan the second code.
   **Expected:** the store opens, with a red-and-cream awning across the top, a teal header, and a card for each product. With no header image set, the octopus in the header moves.
3. **Download the APK.** Tap **Download APK**, then open the download.
   **Expected:** it downloads named after the product, such as `great-scott.apk`, not `app-release.apk`. The phone asks to allow the browser to install apps; allow it, and the app installs. Afterwards, turn that setting off again, in **Settings → Apps → Special app access → Install unknown apps**. On some Samsung phones, **Auto Blocker** blocks the install: turn it off, in **Settings → Security and privacy → Auto Blocker**, install, and it turns itself back on. Note in your report if your phone needed this, and its model, since these obstacles vary between phones.
4. **Download the source.** Tap **Download source**, then open the zip.
   **Expected:** it opens and holds the project's files, but no `.git` folder, no `build` folders, and no keystore or `release.properties` files.
5. **Changes show straight away.** On Phone A, rename a product and publish. On Phone B, reload the store.
   **Expected:** the new name appears on the first reload.
6. **Lists in descriptions.** Give a product the description `<ol><li>one</li><li>two</li></ol>`, publish, and look on Phone B.
   **Expected:** the numbers sit inside the card, not past its edge.
7. **A stalled download.** Give a product a large source zip, over 50 MB. On Phone B, start **Download source**, then switch Phone B's Wi-Fi off. Straight away, open the store on a third device, or on Phone A's browser.
   **Expected:** the store opens straight away. About two minutes later, Share Your Apps Server's log shows `closed a connection … that received nothing for 120 s`.
8. **A paused video.** On Phone B, play one of Code on the Go's videos, and pause it partway. Straight away, open the store on a third device, or on Phone A's browser, and download an APK.
   **Expected:** the store opens and the download starts straight away, with the video still paused on Phone B.

9. **Skipping in a video.** On Phone B, play one of Code on the Go's videos, and drag its position bar to near the end. Then pause a video for over two minutes, and play it again.
   **Expected:** the video jumps to the new position, and plays from there. The paused video carries on from where it stopped, not from the beginning.

10. **Leaving Wi-Fi.** While Phone A serves on Wi-Fi, switch its Wi-Fi off.
   **Expected:** within a few seconds, Share Your Apps Server shows it stopped, because the Wi-Fi network was lost, and says to reconnect or tap Start to use a hotspot.
11. **A missing page.** On Phone B, add `f/nothing` to the end of the store's address.
   **Expected:** a page saying **Not found**, with the awning and the octopus.

12. **What buyers learn about each download.** Look under a product's buttons.
   **Expected:** both buttons show a size. Under them, a line such as *Version 2.1 · Android 9 or later · For 64-bit phones*; an app with no native code says *For all phones*. A product given the Extra JSON from B shows *What's new* under its description, and *By You · License: MIT · Website* under the buttons.

13. **The store's Extra JSON.** On Phone A, set the store's **Extra JSON** to `{"announcement": "New this week", "contact": {"name": "Your name", "email": "you@example.org"}, "accent": "#1E88E5"}`, and publish. Reload the store on Phone B. Then change the accent to `"red"`, and publish again.
   **Expected:** the announcement appears above the products, the contact details at the foot of the page, with the email as a link, and the APK buttons in blue, with black or white text. `"red"` isn't a hex colour, so it's ignored, and the buttons go back to their usual colour. The accessible version shows the announcement and contact, but keeps its own colours.

## E. PIN

1. **Set a PIN.** On Phone A, enter a PIN such as `1234`, and publish. On Phone B, open the store in a new private tab.
   **Expected:** a PIN page with the awning, the octopus and the store's name. A wrong PIN shows a red error; the right one opens the store.
2. **It remembers.** On Phone B, open the store in an ordinary tab — not a private one, which forgets everything when it closes — and enter the PIN. Close the browser, reopen it, and open the store again.
   **Expected:** no PIN is asked for, for up to 24 hours.
3. **Changing the PIN.** Change the PIN on Phone A and publish. Reload the store on Phone B.
   **Expected:** Phone B still gets straight in, without the new PIN.

4. **The PIN field.** Look at the PIN field in Manage Catalog.
   **Expected:** it's on one line with its label, just wide enough for six digits, with *Up to 6 digits* in the note below.

## F. The hotspot — Phone A not on Wi-Fi

1. **Wi-Fi Direct.** On Phone A, switch Wi-Fi **on**, but disconnect from every network. In Share Your Apps Server, tap **Start**, and allow **Nearby devices** if asked.
   **Expected:** the status panel shows a network named `DIRECT-LS-…` and its password. The log shows `Wi-Fi Direct group up`.
2. **Join and shop.** Open **Show QR codes for buyers**. On Phone B, scan the first code, then the second.
   **Expected:** Phone B joins the network, then the store opens.
3. **The same network every time.** Tap **Stop**, then **Start**.
   **Expected:** the same name and password as before, and Phone B reconnects by itself.
4. **Buyers stay connected during a publish.** With Phone B connected, publish from Phone A.
   **Expected:** Phone B stays connected, and a reload shows the new store.
5. **Tap to copy.** On the QR code screen, tap the network name, the password, then the address; then tap the copy icon to the right of the name and password.
   **Expected:** each one flashes a green **Copied!**. The icon copies the name and password together, on two lines.
6. **The fallback hotspot.** Tap **Stop**, switch Wi-Fi **off**, and tap **Start**.
   **Expected:** it starts anyway. The log shows `Wi-Fi Direct group failed`, then `local-only hotspot up`. The name and password differ from the Wi-Fi Direct ones.
7. **Losing the hotspot.** Tap **Stop**. Switch Wi-Fi **on** without connecting, tap **Start**, then switch Wi-Fi **off**.
   **Expected:** within a second or two the server stops, saying Wi-Fi was switched off, which stopped the Wi-Fi Direct group.
8. **Changing the name and password.** In the **Wi-Fi Direct hotspot** panel, type a name after `DIRECT-LS-`, tap **Suggest a password**, then **Save name & password**. Tap **Stop**, then **Start**.
   **Expected:** the limits update as you type, a name that's too long turns red, and saving explains that buyers must join again. After restarting, the new name and password are in use, and Phone B has to join again.

9. **The phone's own Mobile hotspot.** Tap **Stop**. Switch Phone A's Wi-Fi off, turn on its **Mobile hotspot** in Settings, and join Phone B to it. Tap **Start**.
   **Expected:** the status panel says *On this phone's Mobile hotspot*. On the QR code screen, enter the Mobile hotspot's name and password; the join code appears, and Phone B opens the store from the address code. Then turn the Mobile hotspot off: within about 10 seconds, the server stops, saying the phone's Mobile hotspot was turned off.

10. **Buyers on mobile data.** With the server on a hotspot, open **Show QR codes for buyers**.
   **Expected:** under the store's code, a tip says the network has no internet, and, if a buyer's phone can't open the store, to have them turn on airplane mode, then Wi-Fi, and turn airplane mode off afterwards.

11. **Location off, on Android 9 to 12,** if you have such a phone. Turn Location off, turn Wi-Fi off, and tap **Start**.
   **Expected:** the server doesn't start, and Manage Server says to turn Location on, then tap **Start**. With Location on, it starts.

## G. Share Your Apps Server

1. **The layout.** Open the app.
   **Expected:** five raised panels: **Status**, **Server**, **When to run**, **Wi-Fi Direct hotspot** and **Logs**.
2. **Pausing with the screen.** Choose **Pause when the screen turns off**. Turn the screen off, wait a few seconds, then turn it on without unlocking.
   **Expected:** the notification says the server is paused. Now unlock the phone: within a few seconds it's serving again, and the log shows `[server] resuming: the phone was unlocked`.
3. **The full log.** Tap **View logs**. Try an older file's chip, search for `gzip`, switch between **Requests** and **Status**, and pinch to zoom.
   **Expected:** each works; the newest lines are at the top.
4. **Exporting logs.** Tap **Export logs**, and choose Gmail or Drive.
   **Expected:** the attachments are named `share-your-apps-server-log….txt`.
5. **The log pane.** Swipe the small log pane sideways, and wait a few seconds.
   **Expected:** it stays where you left it.
6. **Help.** Long-press a panel's heading, such as **Server**, or a button. Tap the underlined **+ See more**, then the button under the detail. Then long-press it again, and tap the faint **ⓘ** in the tooltip's corner.
   **Expected:** a tooltip on a pale beige, with a faint ⓘ; then its detail, and a button that opens a help page at the right section; the pages, on the same beige, link to each other. **ⓘ** opens *Help data*, listing the database rows behind the tooltip. Long-pressing a text field, such as the port, still selects its text, as usual. **Help**, beside **Start** and **Stop**, opens the table of contents.

7. **Back in help, on Android 13, 14 or 15,** if you have such a phone: open help, follow a link, then press Back.
   **Expected:** Back goes back a page, rather than closing help.

8. **Dark mode.** Switch the phone to dark mode, and open **Manage server**, **Share this store…**, **Import a store…**, **View logs**, and a tooltip and a help page. Then **Show QR codes for buyers**.
   **Expected:** every screen dark, with readable text and buttons, and help on a warm dark brown — except the QR codes screen, which stays black on white, for cameras. Switch back to light mode: each screen follows.

9. **The address, and what Start will serve.** While serving, look at the **Status** panel.
   **Expected:** the address, with a copy icon on its right, which copies it — paste it somewhere to check. Long-pressing the address shows its help. The line under **Show QR codes for buyers** reads *Start will serve…*, with *Start* in a thin box.

10. **The log size limit.** In **Logs**, enter **500**, then **0**, then **100**.
   **Expected:** 500 gets a red marker and a red note naming the valid range, 0 to 100; 0 and 100 are accepted.

11. **Tooltips near the bottom.** Long-press **Done** on **Show QR codes for buyers**, and **Cancel** on **Share this store…**; in each, tap **+ See more**.
   **Expected:** each tooltip stays fully on screen — above its button if there's no room below — also after it grows.

12. **Smaller stores.** Publish a store with at least one product whose source is ticked. Wait a minute, then, in a file manager, open `CodeOnTheGoProjects`, the `Store_…` folder, then `products` and that product's folder. Then, on Phone B, open the store, and tap **Download source** for that product.
   **Expected:** the source zip is there only as `…-source.zip.gz`, with no plain `.zip` beside it. The source downloaded on Phone B is a `.zip` that opens normally, with the project's files inside.
13. **Start and Stop follow the server.** Open **Share Your Apps Server** with the server stopped. Tap **Start**, wait for the address to appear in the **Status** panel, then tap **Stop**. Then tap **Start** again, leave the app, and open it again.
   **Expected:** while the server is stopped, **Status** says *Stopped*, **Start** is enabled, green, with white text, and **Stop** is disabled, with the default grey background. While it's serving, **Start** is disabled, with the default grey background, and **Stop** is enabled, red, with white text. After **Stop**, it's back to the stopped look. Reopening the app while it's serving shows **Start** disabled and **Stop** enabled. A known limit: between tapping **Start** and the address appearing, **Start** stays enabled.
14. **The button colours in both modes, and at a large font.** Look at the **Server** panel in the phone's light mode, then dark mode, with the server stopped and again while it's serving. Then set the phone's font size to its largest, and look again.
   **Expected:** in both modes, the enabled and disabled buttons are easy to tell apart, and each button's text is readable. At the largest font, **Start**, **Stop** and **Help** aren't cut off, and you can reach all three.

## H. Icons and motion

1. **The launcher icon.** Look at Share Your Apps Server on the home screen.
   **Expected:** a violet octopus at a market stall, under a red-and-cream awning.
2. **Themed icons.** Turn on themed icons. On a Samsung: **Settings → Wallpaper and style → Color palette**, then **Apply palette to app icons**.
   **Expected:** the icon becomes a one-colour silhouette of the octopus, holding up two tiles.
3. **Reduced motion.** Turn on **Remove animations** in Android's accessibility settings, then reload the store on Phone B.
   **Expected:** the octopus in the header stands still.

## I. Templates

The four alternative templates are attached to ADFA-6207 as `.peb` files. Save them to Phone A's Download folder.

1. **Choosing a template.** In Manage Catalog, under **Advanced**, tap **Choose a template file**.
   **Expected:** a browser opens in Download, showing folders and `.peb` files only.
2. **Each look.** Choose `theme-neon-arcade.peb`, publish, and open the store on Phone B. Do the same with `theme-morning-edition.peb` and `theme-aurora-deck.peb`.
   **Expected:** three very different looks, each with every product, its pictures, downloads and facts, and an *Accessible version* link at the top left.
3. **Clear Access.** Choose `theme-clear-access.peb`, and publish.
   **Expected:** the store itself is the large-text accessible page, with no *Accessible version* link, since it doesn't need one.
4. **With a screen reader.** On Phone B, turn on **TalkBack** in Android's accessibility settings, and open the accessible version of any store. Swipe through the page, then use TalkBack's heading navigation.
   **Expected:** TalkBack reads a *Skip to the apps* link first, then each app's name as a heading. Download links say whose they are and how big, such as *Download Termux APK, 265 B*, and pictures are read as *Termux, picture 1 of 3*.
5. **Reduced motion.** With **Remove animations** on, open Neon Arcade and Aurora Deck.
   **Expected:** nothing moves.
6. **Back to the default.** Tap **Load the default template**, and publish.
   **Expected:** the store's usual look.

7. **Extending the default.** Make a file `mine.peb` in Download containing just these two lines, choose it with **Choose a template file**, and publish:
   ```
   {% extends "default" %}
   {% block announcement %}<p class=news>Welcome to the new look</p>{% endblock %}
   ```
   **Expected:** the store looks exactly as it does with the default template, apart from the announcement line, which reads *Welcome to the new look*.
8. **A macro and an include, from the template library.** Make the folder `CodeOnTheGoProjects/ShareYourAppsTemplates`, with a file `macros.peb` containing `{% macro badge(text) %}<b>[{{ text }}]</b>{% endmacro %}`, and a file `note.peb` containing `<p>Shared nearby</p>`. Add these lines to `mine.peb`, choose it again, and publish:
   ```
   {% import "macros" %}
   {% block footer %}{{ badge('Hi') }}{% include "note" %}{{ parent() }}{% endblock %}
   ```
   **Expected:** the footer starts with **[Hi]** and *Shared nearby*, followed by the usual footer.
9. **A missing template.** In `mine.peb`, change `"note"` to `"nowhere"`, choose it again, and publish.
   **Expected:** the publish stops, saying *There's no template "nowhere" in CodeOnTheGoProjects/ShareYourAppsTemplates*, and the store keeps serving its previous version. Afterwards, tap **Load the default template**, and publish.

10. **Built-in functions.** Make `mine.peb` contain just these lines, choose it, and publish:
   ```
   {% extends "default" %}
   {% block announcement %}<div class=news>{{ plural(products|length, '# app', '# apps') }} · {{ '**Bold** and [a link](https://example.org)'|markdown }}</div>{% endblock %}
   {% block footer %}{{ qr('https://example.org', 'Example') }}{{ parent() }}{% endblock %}
   ```
   **Expected:** the announcement shows how many apps the store has, such as *3 apps*, then **Bold** and a working link. The footer starts with a QR code that a phone's camera reads as `https://example.org`. Afterwards, tap **Load the default template**, and publish.

## J. Sharing a store with another seller

Phone A serves its own store; Phone B becomes a second seller, serving a copy. Phone B's own browser, or a third device, is the buyer of the copy.

1. **The share screen.** Right after publishing, on Phone A: **Share Your Apps Server** → **Manage server** → *Copies of this store* → **Share this store…**
   **Expected:** it may say *Getting the store ready to share…* for a moment, then shows a QR code, a code such as `LS-FM50-ZV7G` with a copy button beside it, *This code works for one phone, for 10 more minutes*, and a smaller QR code, *No Share Your Apps Server on the other phone?* Rotating Phone A keeps the same code. The screen stays on.
2. **Copying the code.** Tap the copy button, and paste into any text field.
   **Expected:** the code pastes exactly as shown.
3. **The app for Phone B.** If Phone B doesn't have Share Your Apps Server, scan the **small** QR code with its camera app, open the link, and install the download. Allow the browser to install apps if asked, and turn that off again afterwards; on some Samsung phones, turn **Auto Blocker** off first.
   **Expected:** Share Your Apps Server installs. Its version line, at the foot of **Manage server**, matches Phone A's.
4. **Importing by QR code, then denying.** On Phone B, scan the **large** QR code with the camera, and open the link.
   **Expected:** Share Your Apps Server's import screen opens with the code filled in, and the phone's own name. Tap **Continue**: Phone B shows four digits, and Phone A shows *Allow a copy?*, with Phone B's name and the **same** four digits. Tap **Deny** on Phone A: Phone B says the owner denied it, and nothing was imported.
5. **Importing by typed code, and allowing.** On Phone A, tap **Share again**. On Phone B: **Manage server** → **Import a store…**, type the code in lower case, and **Continue**. On Phone A, choose **In a month**, and **Allow**.
   **Expected:** Phone B shows its progress, receiving the store, then *Imported …*, with its expiry date.
6. **Leaving mid-transfer.** Share again, with a store large enough to take a while. As Phone B starts receiving, press Home.
   **Expected:** a notification shows the import's progress, and the import finishes. Opening it again shows *Imported …*.
7. **Cancel mid-transfer.** Share again. As Phone B starts receiving, tap **Cancel**.
   **Expected:** nothing is imported, and Phone B's earlier copy, if any, is untouched.
8. **A transfer that fails, then Try again.** Share again. As Phone B starts receiving, turn its Wi-Fi off for a few seconds, then on.
   **Expected:** Phone B says the import failed, with **Try again**; Phone A says the copy didn't arrive. **Try again** on Phone B finishes it.
9. **Code rules.** On a fresh share: type a wrong code on Phone B five times, then the right one.
   **Expected:** the right one is refused too, and Phone A's screen says the share has ended. Share again, and wait 10 minutes without importing: the code is then refused as expired. And with Phone B importing, a third phone using the same code is refused.
10. **Over Phone A's hotspot, with mobile data on.** Turn Phone A's Wi-Fi off, so it serves on a hotspot, and share. Join the hotspot on Phone B, with its mobile data **on**, and import.
    **Expected:** the import works. Its last screen warns that Phone B is on the owner's network.
11. **Serving the copy.** On Phone B, back on your ordinary Wi-Fi: tap **Serve it now**, or **Manage server** → the store's panel → **Serve this store**, after setting a PIN. Open the store as a buyer, from Phone B's **Show QR codes for buyers**.
    **Expected:** the store asks for Phone B's PIN — not Phone A's — then shows the same store as Phone A's. Downloads work.
12. **The panels' captions.** Look at **Manage server**'s sharing panels on each phone.
    **Expected:** on Phone A, serving: **Share this store…** enabled, and *Import a store* disabled, saying the phone is serving a store. On Phone A, after **Stop**: **Share this store…** disabled, *Start serving your store to share it*. On Phone B, serving its copy: both disabled, with *Copies can't be passed on*, and *This phone is serving a store*. Each changes as soon as the server starts or stops.
13. **Start on Phone B.** Tap **Stop**, then **Start**.
    **Expected:** it serves the copy again. The Status panel says *Start will serve the imported copy of …*, with *Start* in a thin box.
14. **A QR link while serving.** With Phone B serving its copy, scan another share's QR code with its camera.
    **Expected:** the import screen says *Can't import a store here*, and why.
15. **A newer copy.** On Phone B, **Stop**. On Phone A, change something in the catalog, publish, and share again. Import it on Phone B.
    **Expected:** it replaces the old copy without asking, with the change, and its new expiry.
16. **Removing a copy.** On Phone B, **Remove this store**.
    **Expected:** it asks first; **Keep it** changes nothing. **Remove** stops the server if it's serving the copy, and the panel disappears.
17. **Expiry by date.** Import a fresh copy, choosing **In a week**, and serve it. Turn off **Automatic date and time**, and set the date 8 days ahead.
    **Expected:** within a minute, the server stops, and **Manage server** shows *A store has expired*, with no date. A download under way at that moment finishes first. Turn **Automatic date and time** back on, and tap **OK**.
18. **The clock.** Import a fresh copy with **In a week**, and another time with **Never**. Each time, turn off **Automatic date and time**, set the time 10 minutes back, then open Share Your Apps Server. The copy is checked when the app opens or while the server is serving it, not while the server is stopped and the app is closed.
    **Expected:** the **In a week** copy is deleted, with exactly the same *A store has expired* notice as step 17, and nothing mentioning the clock, on screen or in **View logs**. The **Never** copy is kept. Turn **Automatic date and time** back on afterwards.
19. **Optional, with a third phone that has Code On The Go:** publishing on a phone holding a copy. On Phone A, stop serving, and import a copy from the third phone. Then publish from Code On The Go on Phone A.
    **Expected:** Phone A serves its own store, not the copy, without asking. The copy's panel stays, offering **Serve this store**.

20. **The share screen, for Phone B.** On Phone A's share screen, read each line, then look under the small QR code.
   **Expected:** each line makes clear whether it means *this phone* or *the other phone*. Under the small code, *Or open this link in the other phone's browser:*, the whole link — on two lines if it doesn't fit on one — and a copy button that copies it.

## K. Optional: checks that need a terminal

Run these on Phone A, in Code On The Go's terminal, using the address shown in Share Your Apps Server in place of `ADDRESS`.

1. **Compression.** A few seconds after publishing, once the server has compressed the page in the background: `curl -sI -H 'Accept-Encoding: gzip' http://ADDRESS/`
   **Expected:** the headers include `Content-Encoding: gzip` and `Cache-Control: no-cache`.
2. **Without compression.** `curl -sI http://ADDRESS/`
   **Expected:** no `Content-Encoding` header.
3. **The hotspot address.** While the server runs on a hotspot, `curl -s http://ADDRESS/ | head -c 200`
   **Expected:** the start of the store's page.

4. **A catalog replaced by hand.** With the Share Your Apps tab open, go to the terminal and copy another catalog over the plugin's — for example, the demo's: `cp demo/catalog.json ~/.cg/plugins/org.appdevforall.shareyourapps/data/catalog.json` from the plugin project. Return to the tab.
   **Expected:** the tab now shows that catalog, without restarting. If you had unsaved changes in the tab, they're kept instead, with a message saying so. Put your own catalog back the same way afterwards.


## L. MCP control

New in Share Your Apps Server 1.0.48. **MCP control** lets apps on the same phone, such as an AI assistant, start and stop the server, share the store and import a store. It's off until you turn it on, only apps on that phone can reach it, and they can't change the store or approve a copy. Steps 1 to 8 need only Phone A. Steps 9 and 10 are optional and need a terminal.

1. **Off by default.** On Phone A, open **Share Your Apps Server**, and look at the **Server** panel, below **Start**, **Stop** and **Help**.
   **Expected:** a switch, **MCP control**, is off. The line under it says *Off. Turn on to let apps on this phone control the server.* **Copy token** and **New token** aren't shown, and there's no MCP notification.
2. **Turning it on asks first.** Turn the switch on.
   **Expected:** a dialog, *Turn on MCP control?*, says that apps will be able to start and stop the server, share this store and import a store; that they can't change the store or approve a copy; that it also lets the app use the microphone, while receiving a handshake; and that each app needs the token. **Cancel** leaves the switch off. Turn it on again, and tap **Turn on**: the switch is on, and the line under it reads *On, at http://127.0.0.1:6176/mcp*. **Copy token** and **New token** appear, and a notification shows, with a **Turn off** button. The first time, Android then asks to allow the microphone.
3. **The microphone line.** Allow the microphone.
   **Expected:** the line under the switch shows only the address. Now turn MCP control off, take the microphone permission away in Android's **Settings → Apps → Share Your Apps Server → Permissions**, turn MCP control on again, and tap **Turn on**, then **Don't allow** if asked. **Expected:** MCP control still turns on, and a second line reads *Microphone not allowed: hearing a sharing phone's tones needs a person.* Allow the microphone again afterwards. **Optional:** after you restart the phone, and before you open Share Your Apps Server, then open it, the second line may read *Microphone: works once the app is opened.*
4. **Copy token.** Tap **Copy token**, and paste into any text field.
   **Expected:** a long token pastes. The token is never shown on the server screen. On Android 12 and earlier a short *Token copied* message appears; later versions show Android's own copy confirmation.
5. **New token.** Tap **New token**.
   **Expected:** a dialog, *Make a new token?*, says apps using the old token will be refused until they're given the new one. **Cancel** changes nothing. Tap **New token** again, then **Copy token** and paste: the token is different from step 4's.
6. **Help.** Long-press **MCP control**, tap **+ See more**, then the button under the detail. Then long-press **Copy token**.
   **Expected:** a tooltip, *Lets apps on this phone control the server, through MCP. Off until you turn it on.*; then more detail, and a button that opens the **MCP control** help page, with no internet needed. The **Copy token** tooltip begins *The password apps send to use MCP control.*
7. **The store's port can't be 6176.** Set **Port** to `6176`, and tap **Start**.
   **Expected:** the **Status** panel says *Port 6176 is kept for MCP control. Choose another port, such as 6175.* The server doesn't start. Put the port back to 6175 afterwards.
8. **Turning it off.** Turn it off from the notification's **Turn off** button. Then turn it on again, and turn it off with the switch.
   **Expected:** each time, the notification goes, the switch is off, the line reads *Off. Turn on to let apps on this phone control the server.*, and **Copy token** and **New token** disappear.
9. **Optional, in a terminal on Phone A: the token is required.** With MCP control on: `curl -si -X POST http://127.0.0.1:6176/mcp -H 'Content-Type: application/json' -d '{}'`
   **Expected:** `HTTP/1.1 401 Unauthorized`, a `WWW-Authenticate: Bearer realm="Share Your Apps Server"` header, and a message that begins *Missing or wrong token.* With MCP control off, the same command can't connect.
10. **Optional, in a terminal on Phone B: other phones can't reach it.** With MCP control on, on Phone A: use Phone A's own network address, the numbers in the store's address before the port, in `curl -m 5 http://PHONE_A_ADDRESS:6176/mcp -H 'Content-Type: application/json' -d '{}'`
   **Expected:** the connection is refused or times out, with no reply from Share Your Apps Server. Only apps on Phone A itself can reach MCP control.

What apps can do once they have the token, such as sharing a store or importing one, is checked with the scripts in the `mcp` folder of this pack: see `mcp/README.txt`. The same scenarios are in ADFA-6207's Steps to QA.

## M. Sound handshake

New in Share Your Apps Server 1.0.48. Instead of scanning or typing the share code, a phone can hear it: the owner taps **Play sound**, and the other phone taps **Listen**. Sound only carries the handshake; the store itself still goes over Wi-Fi. You'll need both phones close together, with the media volume up and the room fairly quiet.

1. **Play sound.** On Phone A, serving a published store: **Manage server** → **Share this store…**. Tap **Play sound**.
   **Expected:** a short run of tones plays from Phone A, and the button reads **Stop sound**. The tones repeat, for up to 30 seconds, then stop by themselves. A line under the button, *Playing sound… (N attempts remaining)*, counts down the bursts of the code still to play, to none, as the tones end; it follows what you hear. Tap **Stop sound** during them: they stop at once, and the button reads **Play sound** again. In **View logs**, a `[sound]` line says the tones were played. Long-pressing **Play sound** shows its help. With Phone A's media volume at its minimum, tap **Play sound** again: a message says *The media volume is low: turn it up so the other phone hears the tones.*
2. **Listen asks for the microphone, and shows what it hears.** On Phone B: **Manage server** → **Import a store…** → **Listen**. If Android asks to allow the microphone, allow it. Stay quiet for a few seconds, then talk or clap near the phone. Then rotate Phone B.
   **Expected:** the button reads **Stop listening**, and the screen says *Listening… Ask the owner to tap Play sound.* It counts down the seconds left, from 30, and shows a **Microphone level** meter that is empty when it's quiet and rises with any sound. The line above it reads *Nothing heard yet.* when quiet, and *Hearing something faint: hold the phones closer.* for faint sound. Android shows its microphone indicator. Rotating the phone doesn't stop listening.
3. **Importing by sound.** On Phone A, serving, open **Share this store…**. On Phone B, tap **Listen**, then straight away, on Phone A, tap **Play sound**. Keep the phones close.
   **Expected:** Phone B says *Hearing the tones.*, then the import starts by itself, with Phone B's name. Phone B shows four digits, and Phone A shows *Allow a copy?* with the same four digits and Phone B's name. Tap **In a month**, then **Allow**: Phone B receives the store, then shows *Imported …*. Phone A's tones stop by themselves once Phone B connects. In **View logs**, Phone A has a `[sound]` line for the tones played, and Phone B has one for a code heard.
4. **Nothing heard, and stopping.** On Phone B, tap **Listen**, with Phone A silent. After a few seconds, tap **Stop listening**. Then tap **Listen** again, and wait for the 30 seconds to end.
   **Expected:** **Stop listening** ends it at once, with *Stopped.* If you wait, listening ends by itself, with *Didn't hear the code. Hold the phones closer, ask the owner to turn up the media volume, and try again.* Nothing is imported.
5. **Microphone refused.** On Phone B, take the microphone permission away in Android's **Settings**, then tap **Listen**, and choose **Don't allow**.
   **Expected:** the screen says *Without the microphone, the app can't hear the code. Scan the owner's QR code, or type the code, instead.* Typing the code, then **Continue**, still imports a store. Allow the microphone again afterwards.
6. **Over Phone A's own hotspot, Android 10 or later.** Turn Phone A's Wi-Fi on but don't join a network, so it serves on its own hotspot. Join Phone B to a different network. Tap **Share this store…** on Phone A and **Play sound**, and **Listen** on Phone B. Android asks only the first time Phone B joins a hotspot of that name, so to see the prompt again, first change the name under **Wi-Fi Direct hotspot** on Phone A: tap **Save name & password**, then **Stop** and **Start**.
   **Expected:** Android asks Phone B whether to connect to Phone A's hotspot. Tap **Connect** within 30 seconds, and the import goes ahead. When it ends, Phone B leaves that hotspot. On Android 9, or if you don't tap **Connect**, Phone B shows the hotspot's name and password: join it in Wi-Fi settings, then tap **Continue**. On ordinary Wi-Fi, the tones carry the network's name and never its password: if Phone B can't find Phone A, join that network, then tap **Continue**.
7. **The hotspot's password stays out of the log.** After step 6, open **View logs** on both phones.
   **Expected:** the `[sound]` lines show that a code was played or heard, and never the hotspot's password.
8. **The one-phone self-test.** On Phone B's import screen, tap **Listen** three times quickly.
   **Expected:** the screen says *Self-test: playing and listening on this phone…*, plays tones and listens, then says *Self-test passed: this phone heard its own tones*, with a short note in brackets. If the media volume is at its minimum, a message first says *The media volume is low: turn it up for the self-test.*, and the self-test then fails with *Self-test failed: this phone didn't hear its own tones. Turn the media volume up and try again.* A `[sound]` line in **View logs** records the result.
