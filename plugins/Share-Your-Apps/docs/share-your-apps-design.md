# Share Your Apps — Design

A CodeOnTheGo plugin that lets an owner publish their apps (APK, source, or both) to a small web store, and a separate Android app that serves that store to other devices on the same Wi-Fi network or hotspot. No internet, no accounts, no payment.

*Developer notes*, at the end, covers building, releasing and testing. Appendix A describes the default store page, Appendix B is the UI wireframes, and Appendix C holds the store page templates: the default, annotated and as shipped, and four alternatives. Appendix D is a plan for letting owners extend templates, and Appendix E sharing a store with another seller, by QR code, typed code or sound. If a wireframe disagrees with the design, the design wins.

## The two pieces

**Plugin (Manage Catalog)** — runs inside CodeOnTheGo. The owner edits the catalog and taps Publish. The plugin writes the store's files to shared storage, installs and launches the server app, then checks the server is serving the right content.

**Share Your Apps Server** — a separate, ordinary Android app. It serves the files the plugin wrote, controls the hotspot, and shows the two QR codes buyers scan.

Two apps are needed because a plugin can't run a long-lived server; the server app reads the plugin's output from shared storage.

## Scope and background

- **Not goals, by decision:** no payment, pricing or licensing; local networks only — Wi-Fi or the phone's own hotspot, never the internet; no accounts, and no tracking of buyers. **Buyers need only a browser.** Features that would need Code On The Go on the buyer's phone are out of scope, not merely deferred.
- **Deliberate simplifications:** plain HTTP, no minimum PIN length, and no limit on PIN guesses (see *Limitations*).
- **References:** Code On The Go is at `github.com/appdevforall/CodeOnTheGo` (working branch `stage`); its wiki has the plugin developer guide. Example plugins are at `github.com/appdevforall/plugin-examples`. The Serve screen's design follows the **Share Access** screen in KnowledgeToGo. The ticket is ADFA-6207.

## Files

**The server reads only files inside the Store directory**, and writes nothing there except its compressed copies. Publishing copies into it everything the store needs — APKs, source zips, carousel media, header image — so the owner is free to change or delete the originals afterward. Every path in the store (the page's links and every request) is relative to the Store directory: no absolute paths, no `..`. The launch intent names the Store directory rather than giving a path to it.

Plugin private storage (`getPluginDataDirectory()`):

- `catalog.json` — the whole catalog, saved shortly after every change that alters it (see *Plugin UI*).

Shared storage, the **Store directory**, `CodeOnTheGoProjects/Store_<8 random digits>/`. **Each publish builds a new one** with new random digits, alongside the one being served; once the server has switched to the new directory, the old one is deleted. The digits only keep the name unique and clear of any project named `Store`.

```
Store_48291736/
  README.txt                     what this folder is
  index.html
  index.html.gz                  gzip copy, made by the server in the background
  accessible.html                the accessible version, from Clear Access (Appendix C.6), and its .gz copy
  header.<ext>                   header image, if over 10 KB
  products/<slug>/
    <slug>.apk                   the APK, named after the product
    <slug>-source.zip            source, uncompressed zip — until its copy is made, when it's deleted
    <slug>-source.zip.gz         gzip copy, made by the server in the background; afterwards, the only copy
    <carousel files over 10 KB>
    <video>-poster.jpg           a poster for each copied video, when one could be made
```

Everything in it is written by the plugin, except the `.gz` copies, which the server adds after it starts (see *Background compression*).

**Inlining:** media files of **10,240 bytes or less** are inlined into `index.html` as `data:` URIs instead of being written as files, so a buyer's browser makes a few large requests rather than many small ones. APKs and source zips are always files.

`<slug>` is the product name lowercased with anything outside `[a-z0-9-]` turned into `-` (or `product` if nothing is left). If two products in one publish get the same slug, the later one, in `ordinal` order, gets the lowest number that's still free: `<slug>-2`, then `-3`, and so on, skipping any slug a product **earlier** in that order already has; later products don't reserve theirs. So "Great Scott 2", "Great Scott" and a second "Great Scott" get `great-scott-2`, `great-scott` and `great-scott-3`, while "Great Scott", "Great Scott" and "Great Scott 2" get `great-scott`, `great-scott-2` and `great-scott-2-2`; two products can never share a folder.

## catalog.json

```json
{
  "storeDir": "Store_48291736",
  "pendingDir": null,
  "storeName": "Share Your Apps",
  "storeDescriptionHtml": null,
  "headerImage": null,
  "pin": null,
  "templateOverride": null,
  "extra": {},
  "products": [
    {
      "name": "My Todo App",
      "project": "CodeOnTheGoProjects/MyTodoApp",
      "apk": "CodeOnTheGoProjects/MyTodoApp/app/build/outputs/apk/release/app-release.apk",
      "apkPublished": true,
      "sourceZipPublished": true,
      "carousel": ["Pictures/screenshot1.png"],
      "descriptionHtml": null,
      "dateAdded": "2026-09-19T00:00:00Z",
      "ordinal": 1
    }
  ]
}
```

- `storeDir` is the directory currently being served, or `null` before the first publish.
- `pendingDir` is a directory handed to the server by a publish that hasn't finished, or `null`. If Code On The Go's process dies after the server switches but before `storeDir` is saved — likely while the owner is granting a permission — this is the directory the server may really be serving, so it's never deleted as a leftover.
- **An unreadable `catalog.json`** is renamed `catalog.json.bad`, replacing any earlier one, before the owner starts again with an empty catalog, so the next save can't overwrite it and it can be recovered by hand. Manage Catalog says so.
- `project`, `apk`, `carousel` and `headerImage` tell the **plugin** where to copy files from at publish time. They're relative to the shared-storage root (`/sdcard`), never absolute. `catalog.json` is in the plugin's private storage; the server never sees it.
- `headerImage: null` means the built-in icon: the **Eight-Armed Merchant**, a violet octopus minding a market stall under a red-and-cream awning, animated — its side arms wave their app tiles, its eyes blink and glance around, and the counter tiles bob — and still for anyone whose phone asks for reduced motion. It's a hand-optimized SVG of 2,439 bytes (883 gzipped), inlined in the page, so it costs no extra request.
- `pin` is a digit string (up to 6) or `null`. Store it as a string so leading zeros survive.
- `extra` is arbitrary owner JSON, passed to the template as `extra`. The plugin also checks four documented keys, so every template can use them safely: `lang`, `contact`, `announcement` and `accent` (see *Writing a template*).
- Each product's `extra` is its own owner JSON, passed to the template as `p.extra`, with four documented keys: `whatsNew`, `license`, `author` and `website`.
- Each product's `mediaText`, if present, describes its pictures and videos, keyed by their carousel paths: `{"Pictures/chat.png": {"description": "The chat screen, with two messages"}, "Movies/demo.mp4": {"captions": "Movies/demo.vtt"}}`. **Describe**, beside each picture in Manage Catalog, edits it. Templates receive each carousel item's `alt` — the owner's `description`, or else *Termux, picture 1 of 3*, as before — its `description` alone, and a video's `captions`, the link to its copied WebVTT file, which every built-in template adds as a captions track. Captions must be WebVTT, ending `.vtt`; removing a picture removes its text.
- A product appears in the store if it has something to download: an `apk` with `apkPublished` true, or `sourceZipPublished` true. Otherwise it's left out, and the message after publishing names it and says why (see *Plugin UI*). A ticked **Publish the APK** with no APK chosen means no APK, including in the pre-flight check, so a source-only product publishes whether or not that box is ticked.

## Publish

The Publish button is disabled, with a "Publishing…" line shown, until the steps finish. While the store is being built (from plugin step 65), the line updates every half second with what's happening and how far it's got — *Publishing… zipping Todo App's source: 312 of 1,204 files, 12.3 MB of 48.0 MB* — from `PublishProgress`, which `Publisher` updates as it copies each file, embeds a small one in the page, or adds one to a source zip. The totals are known from the first update: `Publisher` lists every project's source files before copying anything, the same work it did before each zip, done earlier. **Why publishing was slow, and how it's fast now (plugin steps 66 and 67).** A timing report — every phase and step, saved to `Download/share-your-apps-publish-timing.txt` when a publish finished, with a summary in the finished message, in plugin steps 66 to 68 only, and removed in step 69 once the cause was fixed — showed 55 of a publish's 63 seconds going to one product's source: Code On The Go's project, 7,012 files, 84.4 MB. Listing it took 17 s, zipping it 38 s: about 2.4 and 5.4 ms per file, against 0.05 and 0.08 ms on a desktop for the same project. The cause is shared storage: every file checked, opened and read waits on Android's storage layer, one call at a time. So `SourceFiles` now lists folders in parallel, as fork-join tasks on six threads, assembling the results in a depth-first walk's order; and writes source zips with six reader threads working up to 32 files, or 16 MB, ahead of one writer — each file of 1 MB or less read once, into memory, where its checksum is computed and from where it's written, and larger files still read twice. Each file's size and date come from the listing, never asked for again. The zips are byte-for-byte what the one-thread code made, checked on the whole project. On the phone, zipping fell from 38 to 4.5 s and listing from 17 to 3.3 s, and the publish from 63 to 12 s. If that's ever too slow, the next step is reusing a source zip when the listing finds its project unchanged. The current store keeps serving buyers until step 7 — unless step 6 updates the server, which stops the old store as it installs.

1. **Check.** `extra` parses as a JSON object, the header image exists, and so do the chosen APK, carousel and captions files of every product being published; and whether each chosen APK is **signed** — a v2-or-later signing block before its zip index, or v1 signature files in `META-INF`. An unsigned APK doesn't stop the publish, since buyers could still see the store, and the demo's placeholder APKs are unsigned by nature; instead, the publish message ends with a note naming each one, and saying buyers can't install it. The APK picker marks unsigned builds too; drafts aren't checked. On failure, show the problem and stop.
2. **New directory.** Delete any `Store_` directory — exactly `Store_` and 8 digits, so a project named, say, `Store_Manager` is never touched, and the project picker hides only those names too — other than `storeDir` and `pendingDir` (left by an interrupted publish), then create a new `Store_<8 random digits>/`, picking new digits if the name is taken.
3. **Copy.** For each published product, copy the APK and every carousel file over 10,240 bytes into `products/<slug>/`. For each video copied, write a **poster**, `<video name>-poster.jpg`: a JPEG of the frame 1.5 seconds in, or halfway through a shorter video — the first frame is often black or mid-fade — made with Android's `MediaMetadataRetriever` and scaled to at most 960 pixels across. The page shows it until the buyer taps play. If no poster can be made, the video has none; that never stops a publish. Copy the header image if it's over 10,240 bytes. A header **video** gets a poster too, `header-poster.jpg`, and the templates show it with native controls and `preload="none"`, playing on tap: autoplaying it would have every buyer download it on every visit, just what not preloading carousel videos avoids.
4. **Source zips.** For each product with `sourceZipPublished`, build the source zip, uncompressed (see below).
5. **Page.** Render `index.html` with Pebble — sort products by `ordinal` in Kotlin first, and pass media of 10,240 bytes or less as `data:` URIs. Also render **`accessible.html`** from the built-in Clear Access template (Appendix C.6), for buyers who use a screen reader or have low vision. The store's page receives `accessibleHref`, `/f/accessible.html`, and every template in Appendix C shows it as an *Accessible version* link at its top left; the accessible page receives `standardHref`, `/`, and links back. If the store's own template is Clear Access — recognised by the comment `share-your-apps:accessible` in it — there's no second page, and neither link appears. Write the pages and `README.txt`.
6. **Install.** The plugin carries the server as the asset `share-your-apps-server.apk`. It installs it when the server isn't installed or the bundled `versionCode` is newer, and refuses to publish — before building anything — when the installed server is newer, since `PackageInstaller` won't downgrade. The owner may be asked to confirm the install. Every plugin file carries the asset, since making one fails without it (see *Bundling the server into the plugin*). The code still copes if the asset is missing — it uses whatever server is installed — but that only happens when the plugin is run in development without a plugin file. CodeOnTheGo declares no package-visibility queries, so it can only see a server it installed itself (with Run, or through the plugin); nothing depends on seeing it — an unseen server is simply installed over. An unseen server that's **newer**, such as one installed from another owner's share screen, makes the install fail as a downgrade; the plugin then says so, and to update the plugin, rather than showing Android's own wording. Every bundled server must be signed with the same key as the installed one, or the owner must uninstall the server once. Installing an update makes Android stop all of the old server's processes, so the store is briefly down, and a local-only hotspot comes back with a new name and password.
7. **Switch.** Save the new directory's name as `pendingDir` in `catalog.json`, straight away. Generate a random token and launch the server with `startActivityForResult`, passing the new directory's name, the token, the PIN and the store name as intent extras. The server stops serving the old directory and starts serving the new one. Its launch activity first asks for any permission the start needs and doesn't have yet — storage access on a fresh install, and *Nearby devices* when a hotspot will be needed — then waits until the new server has bound its socket, then returns `RESULT_OK` with the port — or an error such as "port in use" — and finishes, so the owner stays in CodeOnTheGo. On an error, the plugin deletes the new directory, clears `pendingDir`, reports the error, and stops. If the start fails, the reply says whether the server app had already stopped the old store, which it does before starting the new one: a refused permission fails before anything is stopped, while a taken port or a hotspot that won't start fails after — and if step 6 installed an update, the old store had already stopped, so the reply reports it down either way. So the plugin's message says either that the server is as it was, or that the store is no longer being served, and to tap Start in Share Your Apps Server to serve the previous version.
8. **Save, then clean up.** Save the new name as `storeDir` and clear `pendingDir`, then delete every other `Store_` directory.
9. **Verify.** See below.
10. **Report.** The date and time, then the outcome, on one line. The phone's language and region decide the date's order and wording, and its *Use 24-hour format* setting decides the clock: "Sep 24, 4:32:10 PM · Published Store_48291736 — 7 files verified." or what went wrong, followed by any products left out and why, such as "Left out: Great Scott 2 has no APK chosen, and Publish the source isn't ticked."

**If any of steps 2–6 fails** — running out of space, or any other write or install error — delete the new directory and report the error. Nothing else is touched: the old directory and the running server carry on serving as before.

**Links in the page** are `/f/<path>`, with `<path>` relative to the Store directory — e.g. `/f/products/my-todo-app/my-todo-app-source.zip`. The template receives each as a finished `src` string.

**Source zip.** Collect the project's files — respecting `.gitignore` (including `!` negation and nested `.gitignore` files), and always excluding, at any depth: the directories `.git/`, `build/`, `.gradle/`, `.idea/`, `.cxx/`, `.externalNativeBuild/`, `.kotlin/`, `captures/` and `.cg/` (CodeOnTheGo's per-project state); the files `local.properties`, `keystore.properties`, `release.properties` (signing passwords, in CodeOnTheGo's project templates), `.DS_Store` and `Thumbs.db`; and anything ending in `.iml`, `.hprof`, `.jks`, `.keystore`, `.p12` or `.pfx`. Only regular files and directories are included: symlinks aren't followed, and named pipes, sockets and device files are skipped — reading a pipe would block the publish indefinitely. Write them into one zip with **no compression** (stored entries), which is fast to write. It's named `<slug>-source.zip`, after the product. The server gzips it afterward.

**The plugin does no compression at all.** All compression happens in the server, in the background, after it starts — so publishing finishes sooner.

## Verify (post-publish check)

After launching the server, the plugin checks that the server is serving exactly what it just wrote:

1. Use the port from the launch result. The server is already listening when the result arrives, so there's nothing to wait for.
2. Every verify request carries `?token=<token>`, the token from step 7, so PIN-protected files can be fetched without logging in.
3. `GET /` with `Accept-Encoding: identity` and compare the body to `index.html` byte for byte. Set the header explicitly: Android's `HttpURLConnection` otherwise adds `Accept-Encoding: gzip` itself and silently decompresses the response. The `.gz` copies aren't checked — they may not exist yet.
4. For every file the plugin wrote (except `index.html`, checked above), `GET /f/<path>` with `Accept-Encoding: identity` and compare its length and SHA-256 to the file.
5. Report which files, if any, didn't match or didn't respond.

## Server

- **Runs as a foreground service** of type `connectedDevice`, which has no time limit, so it keeps serving while the owner is in CodeOnTheGo. Two modes: *run always*, or *pause when the screen turns off*. **Pausing** stops the server and turns the hotspot off, but keeps the service running, with a notification saying so. When the phone is **unlocked** — not merely when the screen lights up, so a notification waking the screen doesn't restart anything — the server resumes by itself, as a manual Start would, logging `[server] resuming: the phone was unlocked`. On a Wi-Fi Direct hotspot, buyers' phones rejoin by themselves. On Android's local-only hotspot, the name and password are new each time, so buyers must scan the join code again.
- **Two processes.** The HTTP server runs in its own process (`android:process=":server"`). The app's main process holds the foreground service, the hotspot and the UI, and binds to the server process, which keeps it from being frozen. On each valid start intent, the main process kills the server process, waits for its death notice (`linkToDeath` on the server's binder) so the old process has released the port, and only then starts a fresh one serving the new directory, so nothing leaks from one run to the next. The hotspot lives in the main process, so a restart doesn't drop the network buyers have joined. If the server process dies on its own, Android restarts it, and the main process sends it the same start request again, so serving resumes by itself; the log records *[server] restarted after the server process stopped unexpectedly*. And if the **main** process dies — a phone maker's task killer, or a permission revoked in Settings, which makes Android kill the app — Android disconnects the server process, which stops serving and exits by itself, so nothing is left half-running, and the next Start begins clean. Neither case needs a watch of its own.
- **Binds** `127.0.0.1` plus one external address, chosen by the main process before each start: a hotspot of the server's own that's already running, else the joined Wi-Fi network, else the phone's own Mobile hotspot, else a new hotspot of the server's own (see *The server's own hotspot*). Port configurable in Manage Server, default 6175. The listening socket sets `SO_REUSEADDR` explicitly, so the connections left closing by `Connection: close` don't make a new run's bind fail with "port in use."
- **Watches the Wi-Fi network** while serving on one. If the phone loses the address the server is bound to — it left the network, or got a new address — and still hasn't got it back two seconds later, the server stops with the reason *the Wi-Fi network was lost*, and Manage Server says to reconnect, or to tap Start to use a hotspot instead. Without this, it would keep saying it was serving, on an address the phone no longer has.
- **The phone's own Mobile hotspot,** turned on in Settings, is used when the phone isn't on Wi-Fi, since buyers may already be on it. Android has no public way for apps to ask whether it's on, so it's recognized by interface name: a private IPv4 address on `swlan…` (Samsung), `ap…` or `softap…`, or `wlan1` and up. By Android convention the phone's own Wi-Fi connection is always `wlan0`, so only `wlan0` counts as Wi-Fi. USB and Bluetooth tethering, mobile data and Wi-Fi Direct never count. Manage Server's Status panel says *On this phone's Mobile hotspot*. Apps can't read its name and password, so the Serve screen asks the owner for them, as on Wi-Fi, and never saves them. Buyers on it can use the phone's mobile data too. Android sends apps no notice when it's turned off, so while serving on it — and on Wi-Fi, as a backup — the server checks every 5 seconds that its address is still there. Turning it off stops the server with the reason *the phone's Mobile hotspot was turned off*.
- **The server's own hotspot,** started when the phone has neither Wi-Fi nor its Mobile hotspot on (see *Binds* for the order). It tries a **Wi-Fi Direct group** first (`WifiP2pManager.createGroup()` with a `WifiP2pConfig.Builder` config), whose network name (`DIRECT-LS-` and 4 letters) and 16-character password are generated once, kept in the app's private preferences and reused, so phones that joined before rejoin by themselves; the password leaves out easily confused characters, since buyers may have to type it. **The owner can change both** under *Wi-Fi Direct hotspot* on Manage Server: the name keeps the `DIRECT-LS-` prefix (Android requires `DIRECT-` and two letters or digits) and allows up to 32 UTF-8 bytes in all — 22 plain letters after the prefix; the password needs 8 to 63 printable ASCII characters, and *Suggest a password* fills in one. A short paragraph above the fields states the trade-off before the owner changes anything: the server uses these whenever it starts its own hotspot, phones that joined before won't reconnect by themselves, and buyers will need the new details from the Serve screen. Under each field the limit is shown live — *4 of 22 used after DIRECT-LS-*, noting that accented letters count 2 and emoji 4; *16 characters. It needs 8 to 63…* — and turns red when out of bounds. Edits not yet saved are flagged, suggesting a password says that nothing has changed until **Save name & password**, and saving the values already in use says there's nothing to save. Changes take effect the next time the hotspot starts. The local-only hotspot's name and password can't be chosen: only system apps may set them. Wi-Fi Direct needs Android 10 and Wi-Fi switched **on** (not necessarily connected). If it fails, the server falls back to **Android's local-only hotspot** (`startLocalOnlyHotspot()`), whose name and password Android picks afresh each time. On Android 9 to 12, it won't start while **Location** is off, and Android says only that it failed, so the server checks first, and the owner's message says to turn Location on, then tap **Start**. The server binds whichever address the main process chose. If neither hotspot starts, the start fails with the reason.
  - **Permission:** starting a hotspot needs *Nearby devices* on Android 13+ (declared `neverForLocation`) and location on 12 and earlier. The launch screen and **Start** ask for it only when a hotspot will actually be needed.
  - **Buyers join** the hotspot as an ordinary Wi-Fi network, then open the store's address. Its name and password are shown in Manage Server's Status panel, and on the Serve screen, as text and in the join code.
  - **The hotspot turns off** whenever the server stops or pauses, since there's nothing to serve; the stop's log reason says so. If a hotspot disappears on its own, the server stops too, logging why, and Manage Server says to tap Start or publish again. A local-only hotspot reports this itself. For a Wi-Fi Direct group, the server watches Android's Wi-Fi Direct broadcasts: Wi-Fi Direct turning off — as it does when Wi-Fi is switched off — ends the group at once, and a connection change, which buyers joining or leaving also cause, prompts a check that the group still exists. It counts as lost only if it's still missing a second later, so a momentary gap can't stop the server.
  - **Logged:** `[hotspot] not connected to Wi-Fi, so starting a hotspot`, then `[hotspot] Wi-Fi Direct group up: DIRECT-LS-ABCD at 192.168.49.1` or `[hotspot] Wi-Fi Direct group failed: <reason>`, then, if it came to that, `[hotspot] local-only hotspot up: <name> at <address>` or `… failed: <reason>`. On the phone's own Mobile hotspot, `[hotspot] using the phone's own Mobile hotspot, at <address>`. The main process passes these to the server in the start request, so the server remains the log's only writer; lines from a hotspot attempt that failed entirely are written at the next start.
- **Start intent:** carries the Store directory's name, a random token, the PIN (or none) and the store name. The server acts on it only if `getCallingPackage()` is CodeOnTheGo (`com.itsaky.androidide`) and the name matches `Store_<8 digits>`; any other start intent is ignored. The main process then restarts the server process on that directory and, once it has bound successfully, saves the directory name, PIN and store name in the app's private preferences — never in shared storage (see *Two processes*). The directory is fixed for that run — switching means another start. A manual Start in Manage Server uses the saved values. The server doesn't start by itself after the phone restarts; the owner taps Start, or publishes again.
- **Token:** the start intent's token is passed from the main process to the new server process and held in memory for that run only — never written to disk; in the access log, its value in request lines is replaced by `-`. A request with a matching `?token=` query parameter is authenticated without a PIN; that's how the plugin's verify step reads protected files. A new start replaces the token, so an old one stops working.
- **One thread decides; a thread per response sends.** A single **main thread** reads each request and decides its response — the PIN, cookie and token checks, routing, the file, `304`s and gzip — so none of that ever runs concurrently. It writes the response's headers and any small body into memory, then hands them to a **sender thread**, which writes them, streams the file if there is one, writes the log line and closes the connection. A buyer who stops receiving — a paused video, or a phone gone out of range — so holds up only their own sender.
  - **What senders share:** only their *last progress* time, which each writes and the main thread reads, and the log, whose writes — including rotation — are synchronized. Each sender owns its connection and file outright.
  - **With range requests** (see the *Range rule*), a video paused past the two-minute limit resumes where it stopped: its connection closes, but the browser then asks for the rest from that point.
  - **The main thread owns the list of senders,** and wakes at least once a second to tidy it: it forgets finished senders, and closes the connection of any that has sent nothing for **two minutes**, logging `[server] closed a connection from <address> that received nothing for 120 s`. Closing is how a sender is stopped — Java can't kill threads — since its blocked write then fails and it ends by itself. The limit only frees resources, since a stalled sender holds up nobody; without it, one would wait until TCP gave up, which can take many minutes.
  - **At 16 senders,** a new request gets a small *The store is busy. Try again in a moment.* page with `Retry-After: 5`, written by the main thread, which is small enough never to block.
  - **`stop()`** closes the listening sockets and sends the main thread a *stop* message; the main thread then closes every sender's connection and waits for it, so only the main thread ever touches the senders.
  - `Connection: close` on every response, so a sender's job always ends with its connection, and limits on reading each request: every read within 2 seconds, and the **whole request within 5 seconds** of the main thread taking it, so neither a browser's idle preconnect nor a client sending a byte a second can hold the main thread for long.
- **`minSdk` 28 (Android 9)**, the same as CodeOnTheGo: the server only ever runs on a phone that's running CodeOnTheGo.
- **Storage access** via `MANAGE_EXTERNAL_STORAGE` on Android 11 and later (confirmed working). On Android 9 and 10, where that permission doesn't exist, via `READ_EXTERNAL_STORAGE` and `WRITE_EXTERNAL_STORAGE`, with `requestLegacyExternalStorage` for Android 10. **On a fresh install** the server has no storage access yet, so its launch screen asks for it — the All-files access screen, or the permission dialog on 9 and 10 — and carries on with the publish once it's granted; the launch timeout only starts after that.
- **Confined to the Store directory.** Every served file is opened through one function, which resolves the requested path with `getCanonicalPath()` — following symlinks — and refuses anything not inside the Store directory's canonical path. Nothing else in the server opens files in shared storage.

Routes:

| Request | Response |
|---|---|
| anything, when the Store directory has no `index.html` | 503 "This store hasn't been set up yet" |
| any page or file, PIN set, not authenticated | redirect to `/pin` |
| `GET /pin`, authenticated | redirect to `/` |
| `GET /pin`, not authenticated | PIN entry page, showing `storeName` |
| `POST /pin` | correct → set the `ls_auth` cookie, redirect to `/`; wrong → PIN page with an error |
| `GET /` or `/index.html` | `index.html`, or its `.gz` copy (see below) |
| `GET /f/<path>` | the file at `<path>` inside the Store directory, or its `.gz` copy (see below); 404 if there's no such file |
| anything else | 404 |

**Caching:** every publish reuses the same addresses — `/` and each product's files — for new content, so everything the server serves from the Store directory carries `Cache-Control: no-cache`, making browsers check back each time rather than show an old catalog or hand out an old APK. The PIN page, the message pages and redirects carry `no-store`. Every file also carries `Last-Modified`, and a request whose `If-None-Match` matches the file's weak `ETag` — which names the store's folder, the file's time and its size — or, without one, whose `If-Modified-Since` **equals** `Last-Modified` exactly, gets `304 Not Modified` with no body. Exact, rather than *not modified since*, because on Wi-Fi Direct every store is at 192.168.49.1: a browser's cached page may be another store's, and an older store mustn't confirm it, so a reload costs almost nothing. Each publish copies its files afresh, so their times change whenever their content can have. Carousel videos in the default template don't preload, so viewing the page doesn't start a large download; their posters show what they are instead. Preloading would also cost every buyer a download for every video on the page, and a browser that has fetched a video's start usually stops reading while keeping the connection open, holding a sender until the two-minute limit.

**Every page the server itself sends buyers uses the default store's look** (`Brand.page`), whatever template the catalog page uses: the awning, then a card on the cream ground with the animated octopus above its content. These pages are built into the server app, which never sees the catalog's template. That's the PIN page — with the store's name, the PIN field and an orange **Enter** button — and the four short message pages: *This store hasn't been set up yet* (503), *Not found* (404), *Bad request* (400) and *Method not allowed* (405). The octopus is an `<img>` with a `data:` URI rather than inline SVG, because an inline SVG's styles apply to the whole page.

- **Background compression.** When the server starts on a directory, a background thread gzips the pages at the Store directory's top — `index.html` and `accessible.html` — and each source zip that has no `.gz` copy yet, at maximum compression. Each is written to a temporary name (`<file>.gz.tmp`) and renamed to `<file>.gz` only when finished, so a partial copy is never served. A copy that isn't smaller than the original is deleted instead, so it's never served either. On running out of space or any other error, the thread deletes its temporary file and gives up; the uncompressed file keeps being served. Nothing coordinates with it: a relaunch kills the server process, taking any compression in progress with it, and the leftover temporary file is in the old directory, which the plugin deletes.
- **A source zip keeps only its `.gz` copy** (from server step 70). Once the copy is complete, the server gives it the zip's date and deletes the zip, saving most of a store's space: for Code On The Go's project, measured, 42 MB on disk instead of 138 MB for the zip and its copy, while buyers still download 42 MB. Compressing inside the zip instead, with no copy, would have kept 49 MB, with 49 MB downloads and a slower publish. Serving: `HttpServer` sends the copy as-is to a client that accepts gzip, and otherwise the zip's own bytes, decompressed on the fly — whole, or for a range, decompressed up to its start, then the slice. The size comes from gzip's own record of it, and the date is the zip's, so the ETag, Last-Modified and Content-Length are exactly what they were before the zip was deleted, and browsers' caches and resumed downloads keep working. Sharing sends a zip deleted after its listing from its copy, decompressed, and the plugin's check after publishing compares a deleted zip with its copy the same way, opening the expected file once, so its length and contents can't come from different copies. Pages keep both copies, being tiny; so does a zip of 4 GB or more, since gzip records sizes modulo 4 GB. `GzOnlyTest`'s 12 checks cover it all, against a real server. The cost: a resumed download near the end of a big zip means decompressing everything before that point, a few seconds for the largest.
  - **Logged to the access log**, through the same logger as request lines (see *One logger, thread-safe*), as plain-text lines starting with `[gzip]`:
    - `[gzip] started: 3 files` when the pass begins;
    - `[gzip] done: products/my-todo-app/my-todo-app-source.zip 48213504 -> 21907118 bytes in 41 s` for each file;
    - `[gzip] not smaller: products/my-todo-app/my-todo-app-source.zip 48213504 -> 48250112 bytes; deleted` when the copy isn't smaller;
    - `[gzip] failed: products/my-todo-app/my-todo-app-source.zip: no space left on device` when it gives up on a file, with the reason;
    - `[gzip] finished: 3 compressed, 0 not smaller, 0 failed` when the pass ends.
  - A pass cut short by a relaunch writes no `finished` line, since the process is killed; the next run's lines follow.
  - Like the hotspot lines, these follow the log's size setting.
- **Gzip rule:** if the request's `Accept-Encoding` includes `gzip` and a `.gz` copy of the requested file exists, send the `.gz` copy with `Content-Encoding: gzip`, keeping the original file's `Content-Type` and download name. Otherwise send the file itself.
- **Range rule** (RFC 9110), for skipping ahead in a video, resuming a paused one, and resuming a download: every file response says `Accept-Ranges: bytes`. A single range — `bytes=a-b`, `bytes=a-` or `bytes=-n` — gets `206 Partial Content`, with `Content-Range`, and just that slice; one running past the end is cut short. A range starting past the end gets `416 Range Not Satisfiable`, with `Content-Range: bytes */<size>`. Anything else — several ranges, another unit, or a malformed or backwards range — is ignored, and the whole file sent with `200`, as the standard allows. With `If-Range`, the slice is sent only if the date matches the file's; otherwise the browser's copy is stale, so it gets the whole, new file. Ranges always apply to the plain file, never its `.gz` copy, whose byte positions differ. The main thread works all this out; the sender just sends the given bytes from the given offset.
- **Headers on files:** `Content-Type` from the extension (`application/vnd.android.package-archive` for `.apk`, `application/zip`, image/video/audio types) and `Content-Disposition: attachment; filename="<name>"` for APKs and zips, so downloads keep their real names and install.
- **Authentication cookie, no sessions.** On first start the server generates a random secret and saves it in its own private preferences. A correct PIN sets cookie `ls_auth=<issue time>.<signature>`, where the signature is an HMAC-SHA256 of the issue time made with the secret, with `Max-Age=86400`, `Path=/`, `HttpOnly` and `SameSite=Lax`. The server itself rejects a cookie 24 hours after it was issued, whatever the browser keeps, so a copied cookie can't be reused indefinitely. A request is authenticated by a valid, unexpired cookie (or a matching `?token=`); the server keeps no list of sessions. The cookie never contains the secret. This matters because every Wi-Fi Direct group owner has the address 192.168.49.1, so a buyer's browser sends one store's cookie to any other store at that address. Changing the PIN doesn't affect existing cookies, and they survive a server restart.
- **Access log**, Apache format plus bytes/sec. It rotates at a configurable size (default 10 MB, 0 = off), keeping **at most five files** — the current one and four older ones; the oldest is deleted at each rotation.
  - **A line for every response, including downloads a buyer stopped.** Each line's size is the body bytes actually handed to the network, counted in 8 KB slices: a lower bound, accurate to 8 KB. So a download stopped partway shows a size smaller than the file's — or `-`, none, if it stopped within its first slice, which on phones with small network buffers is common. A response with no body, such as a `304`, also logs `-`, as Apache's combined format does. The line appears as soon as the server's next write fails, or at the latest when the two-minute stall limit closes the connection; how soon a phone reports a hung-up buyer varies.
  - **Server start and stop lines:** `[server] serving Store_48291736 on 192.168.1.5:6175` (with ` with a PIN` when one is set) when a start succeeds; `[server] failed to start: <reason>` when it doesn't; and `[server] stopped serving Store_48291736: <reason>` when a serving server is stopped gracefully. The reasons are *stopped by the owner in Manage Server*, *restarting for a new publish (Store_…)*, *restarted from Manage Server (Store_…)*, *paused: the screen turned off*, *the Wi-Fi network was lost*, *the phone's Mobile hotspot was turned off*, *the app's service was stopped*, and, when a hotspot disappears on its own, *the local-only hotspot stopped*, *the Wi-Fi Direct group stopped* or *Wi-Fi was switched off, which stopped the Wi-Fi Direct group*. A stop that also turns a hotspot off adds *; hotspot turned off*. Because the main process kills the server process, it first asks the server to log the reason and close its sockets, then kills it when the server confirms, or after two seconds regardless. A crash can't log, so it leaves no stop line.
  - **One logger, thread-safe.** Every line — requests, `[hotspot]`, `[gzip]` — goes through a single `synchronized` method in the server process that appends the line, flushes, and, if the file has passed the size limit, rotates. Rotation happens inside the same lock, so no write can land in a file that's being closed or renamed. Only the server process writes; the main process never touches the file directly.
  - **Manage Server's log viewer** runs in the main process and only reads. After a rotation it reopens the current file, and it may briefly show a partial last line.
- **Manage Server screen:** start/stop, port, when to run (*Run always*, or *Pause when the screen turns off*), the log size limit in MB (0–100, default 10, 0 turns logging off; takes effect at the next start; anything else — empty, not a whole number, below 0 or above 100 — gets Android's red error marker on the field and a red note, such as *500 is more than 100. Enter a whole number from 0 to 100. Until then, the limit stays at 10 MB.*, checked by `LogSize.kt`, from server step 65), the Wi-Fi Direct hotspot's name and password, **Show QR codes for buyers**, **Help**, and the access log, newest lines first, refreshing every 2 seconds, with long lines scrolling sideways. The directory to serve always comes from the plugin; Start serves the last one published — or an imported copy, on a phone that holds one, as Appendix E.2 describes. The run mode is checked each time the screen turns off, so changing it applies immediately.
- **Manage Server's layout:** five raised panels — **Status** (status, address, hotspot details, *Show QR codes for buyers*, storage access, last published), **Server** (port, Start, Stop, Help, and the MCP control switch with Copy token and New token), **When to run**, **Wi-Fi Direct hotspot**, and **Logs** (size limit, the live log, View and Export, the log folder). Each has a fake 3D bevel: a face shading from white to light grey, a thin inner highlight, a darker rim along the lower right, and a soft offset shadow, on a slightly darker page. Each panel's heading carries its help.
- **Start and Stop follow the server's state.** **Start** is green and enabled only while the server is stopped; **Stop** is red and enabled only while it is serving. A disabled button has a grey fill, with greyed text (`Palette.fillOff`: `#E6E8EB` by day, `#34383D` at night), so it never looks tappable, and the colors follow the phone's light or dark mode. Known limit: between tapping **Start** and the address appearing, **Start** stays enabled, because `ServerStatus` has no *starting* state. A single button that changed from *Start* to *Stop* was rejected: a second accidental tap would stop the server without the owner noticing.
- **MCP control.** A switch in the Server panel lets apps on the same phone, such as an AI assistant, control the server through MCP, the Model Context Protocol. It is off by default, and turning it on asks first, saying what apps will be able to do.
  - **What speaks MCP.** JSON-RPC 2.0 over the Streamable HTTP transport: `initialize`, `ping`, `tools/list` and `tools/call`, with every POST answered in plain JSON and never an event stream. It accepts protocol versions 2025-06-18, 2025-03-26 and 2024-11-05.
  - **Where.** `http://127.0.0.1:6176/mcp`, on the loopback address only, from a listener of its own (`McpHttp`) apart from the store's HTTP server, so no other device can reach it. A foreground service in the main process (`McpService`) runs it while the switch is on and the app has been opened since the phone started; nothing starts it at boot. Its notification has a **Turn off** button. The store's own port can't be 6176: **Start** says so and doesn't start. (The share code's discovery, in E.3, uses UDP on 6176, which doesn't clash.)
  - **Who may ask.** Before any request reaches the protocol, the listener checks that the peer is a loopback address, that `Host` names `127.0.0.1` or `localhost` (on any port, since `adb forward` can bring a request in on another), that `Origin`, if sent, does too, so a web page can't reach it by DNS rebinding, and that `Authorization: Bearer <token>` matches, compared in constant time. Failures are plain HTTP answers: 401 for a missing or wrong token, 403 for a foreign `Host` or `Origin`, 404 for another path, 405 for GET, 413 for a body over 1 MB, 415 for a body that isn't JSON, and 400 for broken JSON. At most 8 connections at once; an idle one closes after 30 seconds.
  - **The token.** A random secret kept in the app's private preferences (`mcp`), never logged and never shown on screen. **Copy token** puts it on the clipboard. **New token**, after a confirmation, replaces it at once, since the listener reads it on every request, and apps with the old one are refused.
  - **The tools (12).** `server_status`, `store_info`, `server_start`, `server_stop`, `share_start`, `share_status`, `share_stop`, `import_start`, `import_status`, `import_cancel`, `share_play_sound` and `import_listen`. They report, start and stop the server as **Start** and **Stop** do, open and close a share, start and cancel an import, and play or hear the sound handshake (E.8). No tool changes the store's contents, deletes a copy, changes the hotspot's settings, or approves a copy: only a person tapping **Allow** on the sharing phone can. None reports the hotspot's password, the PIN, the plugin's token or the cookie secret. When something needs a person, a tool says so, with what to do, instead of failing.
  - **The microphone.** `McpService` also holds the microphone service type, so `import_listen` can hear while the app is off screen. The line under the switch says *Microphone not allowed: hearing a sharing phone's tones needs a person.* when the permission is missing; MCP control still works without it.
  - **Tests and scripts.** `McpProtocolTest` (33 tests) runs the protocol without Android. The tester pack's `mcp/` folder has curl and shell scripts, with a README, that play the MCP client on a phone or, over `adb forward`, on a computer; QA steps section L covers the switch, the token and the endpoint's refusals.
- **Logs on Manage Server:** the inset log is updated only when the file has changed, keeps its sideways scroll position across updates, and pauses while text is selected. **View logs** opens the full log; **Export logs** opens Android's share sheet with every log file attached. The log folder's path is shown and can be selected with a tap; it's in the app's private storage, deliberately, since the logs record buyers' IP addresses and browsers, so other apps and file managers can't open it and exporting is how logs leave the phone.
- **Full log view** (the wireframe's FullScreenLogView): one chip per file — *Current*, then older files by age, like *2h ago* — with a line count and size; a search box (case-insensitive, over the whole file); *All*, *Requests* and *Status* filters, since the log's failures are all in its `[server]`, `[gzip]` and `[hotspot]` status lines; pinch to zoom; panning in both directions; and **Share** for the chosen file. Newest lines first. It shows at most the newest 2,000 matching lines of a file and says when there are more.
- **Sharing** goes through a read-only content provider that serves only the five log files, as `share-your-apps-server-log.txt` and `share-your-apps-server-log-1.txt` to `-4.txt`, with one read grant per share.
- **Launcher icon:** the Eight-Armed Merchant, as an Android adaptive icon (`mipmap-anydpi-v26/ls_launcher.xml` and `ls_launcher_round.xml`) with two vector layers — `drawable/ls_icon_background.xml` (the teal ground and the awning) and `ls_icon_foreground.xml` (the octopus, its tiles and the counter) — drawn on the 108-dp adaptive grid, with everything important inside the zone every launcher mask keeps. A third layer, `drawable/ls_icon_monochrome.xml`, is the **themed icon** for Android 13 and later: a one-colour silhouette the phone tints to match the wallpaper — the awning with four scallops, the octopus's head behind the counter with its eyes cut out and pupils inside, and its two raised arms holding up upright, rounded tiles — drawn inside the 66-unit circle, since themed icons are always shown cropped to it. The stripes, smile and counter tiles are left out, since they'd blur when tinted and shrunk. They have names of their own, and the manifest points at them, so the template's `ic_launcher` files can't clash with them.
- **What Start will serve** (from server step 69): the line under **Show QR codes for buyers** says *Start will serve the last store published: Store_…*, or *Start will serve the imported copy of …, below.*, with *Start* in a hairline box, in the text's own colour (`BoxSpan`), so it reads as the name of the **Start** button.
- **Manage Server's address** (from server step 68): while serving, the Status panel shows the store's address, with a copy button on its right — the same icon, size and confirmation as the Share screen's — in place of the earlier *(tap to copy)*, which wrapped onto two lines and worked differently from everywhere else. The address itself isn't selectable, since its long-press shows its help. Android 13 and later confirm a copy themselves; older versions show *Address copied*.
- **Serve screen** (**Show QR codes for buyers** on Manage Server): a full-screen page to hold up for buyers, always black on white so cameras read it, portrait on phones, keeping the screen on. Step 1 is a QR code in the standard Wi-Fi format (`WIFI:T:WPA;S:…;P:…;;`, with `\ ; , : "` escaped) to join the network; step 2 is a QR code of the store's address, with the address below it. Codes come from ZXing core 3.5.3 (`implementation("com.google.zxing:core:3.5.3")`). **On the server's own hotspot**, step 1 is made from its name and password, and both are shown as text, since buyers may have to type them. **On Wi-Fi, or the phone's own Mobile hotspot**, Android doesn't let apps read the password, so the owner may type the network's name and password. On Wi-Fi the name is pre-filled when Android allows reading it, which needs location access this app only holds on Android 12 and earlier. The password is never shown as text; the code carries it. With no password typed, only the address code is shown. These owner-facing fields come first on the screen, above the codes. **Show the join code**, or the keyboard's **Done** key on the password, closes the keyboard, generates the code, and collapses the fields to one line — *Joining "…" · Change* — so buyers see just the codes; *Change* brings the fields back. If either field is empty, it says so. The collapsed state is kept across rotation along with the password, in memory only. **Copying:** each shown value — the network name, the hotspot password and the store address — copies when tapped, flashing a green *Copied!* in its place. On a hotspot, a brace drawn to the height of the name and password lines points to a copy icon that copies both, separated by a newline. Copies that include the hotspot password are marked sensitive, which hides them from the clipboard preview on Android 13+. A typed home-network password is never copyable: it isn't shown as text, and it stays off the clipboard. It follows the server's status while it's showing: if the store stops, pauses or moves to another address or hotspot, it redraws, showing *isn't being served* rather than stale codes, and the codes return by themselves when it's serving again. A typed password survives the redraw. On a network without internet — the server's own hotspot, or the phone's Mobile hotspot — it adds a tip under the store's code: *This network has no internet. If a buyer's phone can't open the store, have them turn on airplane mode, then turn Wi-Fi back on. Remind them to turn airplane mode off afterwards.* A buyer's phone may otherwise send their browser over mobile data instead. Airplane mode, then Wi-Fi, leaves Wi-Fi the only way out, and airplane mode is the easiest switch to find on any phone, under the same name; turning off mobile data would work too, but its name and place vary by maker.
- **A typed Wi-Fi or Mobile hotspot password is never written to disk** — not to preferences, files, or `savedInstanceState`. It's kept across rotation, font-size, light/dark and screen-size changes by the framework's retained instance (`onRetainNonConfigurationInstance`, the mechanism ViewModels are built on), in memory only, and is gone when the Serve screen is closed or the process ends. Its field uses the password input type, which blocks copying, with the keyboard's personalized learning turned off.

## Server help (three tiers)

The server app has its own three-tier help, like CodeOnTheGo's: a one-line tooltip on long-press (Tier 1), **+ See more** for detail and a button to a help page (Tier 2), and full help pages (Tier 3). **Every** entry has all three: each has a button to its section of a page. A **Help** button next to Start and Stop opens the main page, a table of contents.

- **Database:** `web_server_documentation.db`, bundled in the APK's assets and built by `help/build_help_db.py`, which also holds the help text. SQLite can't open a file inside the APK, so the asset is copied to the app's `databases/` directory once per app version (so an update brings new help), then only ever **opened read-only** (`SQLiteDatabase.OPEN_READONLY`). It holds 49 tooltips, each with one button, and 9 help pages — serving, hotspots, sharing, importing, imported copies, running and logs, the access log, the index, and a test page — about 26 KB of text; the file is 96 KB, because SQLite stores each of the format's ten tables in whole 4 KB pages, even tables with a single row.
- **Format:** the same tables and columns as CodeOnTheGo's `documentation.db`, read with CodeOnTheGo's own three queries (tooltip by tag and category, its buttons in order, content by path), including its chunking: a row of exactly 1 MB continues as `<path>-1`, `<path>-2`, and so on. Tooltips are in the category `shareyourapps_server`. Every page is stored as plain HTML — `compression = 'none'` and no template — which that format allows, so the app needs neither Brotli nor Pebble. A row declaring either is refused with a clear message rather than misread. The database includes the `x.html` test page.
- **Tier 3** pages open in an in-app WebView that answers every request itself from the database, with JavaScript off, so it can't reach the network; links between help pages work normally.
- **The tooltip.** The summary sits beside a faint **ⓘ**, and **+ See more** is a link, underlined in the app's link colour, not a button, as CodeOnTheGo shows it — with a touch area at least 48 dp tall. It reveals the detail, and the button to the page, which stays a button.
- **Help is on a pale beige, `#F5ECD7`,** in every tier: the tooltips and their *+ See more* form (`HelpUi.kt`), and the Tier 3 pages (`build_help_db.py`'s stylesheet), with the help screen's WebView beige behind them too, so nothing flashes white while a page loads. It sets help apart from the grey screens and white panels around it. Code on the pages sits on a slightly deeper beige, `#EADFC6`. Text, links and code pass the contrast standard easily: 13.5, 9.1 and 12.0 to 1, against 4.5 needed. The tooltip keeps its thin border, and the ⓘ panel, for developers, stays white.
- **The help database uses 1 KB pages,** set by `build_help_db.py`, which also vacuums it. Of every page size SQLite allows, from 512 bytes to 64 KB, that gives the smallest database once compressed in the APK, and keeps it at 55 KB on the phone, against 98 KB with SQLite's default 4 KB pages. Vacuuming alone changed nothing, since the database is built fresh.
- **Tooltips stay on screen** (from server step 65): `HelpUi` places a tooltip below its button or heading if it fits, otherwise above, otherwise on the roomier side, scrolling; it stays within the visible screen with an 8 dp margin, clamped sideways, and is placed again after **+ See more**, since it grows. Before, tooltips always opened below, so those near the bottom — **Done** on the Serve screen, **Cancel** on Share — were cut off. `HelpUi` shows the app's only popups, so this covers every screen.
- **The ⓘ panel,** *Help data*, lists the documentation database rows behind the tooltip, as CodeOnTheGo's debug popup does: the database and its version; the tooltip's row, with its id, category, tag and text lengths; its button rows, with their targets; and each linked page's content row, with its type, compression, language, size and continuation chunks. Its text can be selected, to copy into a report. It's built from what the rows are, not from CodeOnTheGo's own popup, which couldn't be read, so its layout may differ.
- **Buttons link to sections:** `sharing.html#allow`, say. The WebView keeps the part after `#` to itself, and scrolls there once the page loads from the database.
- Every heading, button, checkbox and radio button on every screen has an entry. **Text fields don't:** they keep their own long-press, for selecting, copying and pasting — a seller may well paste a share code or a Wi-Fi password — so their help is on their labels.

## Plugin UI

Manage Catalog, per the wireframes: store name, store description, header image, PIN, the product list, template override, extra JSON, and the Publish button with its status line. There's no Cancel: every change is saved to `catalog.json` shortly after it's made, and when the owner leaves the tab. A save is skipped when the catalog's text is identical to what was last loaded or written, so leaving without changing anything never touches the file, and an edit that's undone doesn't count. **A catalog.json changed by something else** — say, a catalog installed by hand — is noticed by its modification time and size, recorded at each load and save. When the tab returns to the screen, it reloads the file. Publishing checks first too: if the file changed, it reloads it and asks the owner to check it before publishing, so a catalog they haven't seen is never published. With unsaved edits, the edits are kept instead, and the owner is told that saving them will replace the other version. Reloads are skipped while a save is being written, when the file briefly differs from its record. Saving is atomic — written to `catalog.json.tmp`, then renamed into place — so an interrupted or failed save leaves the previous file intact.

- **Look:** the wireframes' palette and components (`wireframe.css`) — pale background, white bordered cards and fields, muted hints, a blue accent for Publish — with a matching dark palette chosen from the system setting, since Code On The Go is often used in dark mode. The form is grouped into **Store**, **Products** and **Advanced**, and the status line and Publish button sit in a bar along the bottom.
- **Publish asks first.** A dialog, *Publish Top Android apps?*, says how many products the store will have, names up to three left out with nothing to download, and says buyers keep the current version until the new one is ready. **Cancel** changes nothing. It comes after the checks that refuse a publish outright — invalid Extra JSON, or a catalog changed outside the plugin — so it appears only when a publish is about to start.
- **The screen stays on while a publish runs,** so a screen timeout can't pause a server in *Pause when the screen turns off* mode partway through, or slow the zipping down.
- **Left-out products are named.** A product with nothing to download — no APK chosen with *Publish the APK* ticked, and *Publish the source* unticked — is left out of the store, and its card shows *draft*. The message after publishing names each one and why, for example *Left out: Great Scott 2 has no APK chosen, and Publish the source isn't ticked.*
- **HTML check for descriptions.** The store description and every product description go into the page as raw HTML, so each is checked as the owner types, by a small checker in the plugin (no library): it tracks open tags on a stack, skipping comments, quoted attribute values and the raw text inside `<script>` and `<style>`, and knows HTML's own rules — tags that never close (`<br>`, `<img>`), optional end tags (`<li>`, `<p>`, `<td>`), and a new `<li>` or block tag closing the previous one. It reports, in plain words and at most five at a time: a tag opened but never closed (*<ol> is opened 2 times but never closed*), a tag closed that was never opened, tags closed in the wrong order, a closing tag for a tag that never takes one, a tag never finished with `>`, and an unclosed comment. Problems show under the field, and on a closed product card as a one-line warning. They never stop a publish, since browsers tolerate a great deal. It doesn't check tag or attribute names, or CSS.

- **Products** are cards showing the name and what's published (`Source ✓`, `APK ✓ 4.2 MB`, or `draft` when there's nothing to download), in green when the product will appear in the store. Tapping a card's header opens its editor: name, project, APK, the two publish checkboxes, pictures and video, description, Extra JSON, Move up/down, and Remove (with a confirmation). The Extra JSON field saves only valid JSON objects; while it's invalid, a note says so, and the last valid value is kept. List order is the product order.
- **Pickers** are in-app lists that return paths relative to shared storage, which is what `catalog.json` stores; Android's own file picker returns content URIs, which can't reliably be turned back into paths. *Project:* the folders in `CodeOnTheGoProjects`, except `Store_` directories. *APK:* the `.apk` files under the project's `build/outputs/apk/`, newest first. *Media:* a folder browser starting in `Pictures`, showing images, video and audio. *Template:* the same browser, starting in `Download`, showing only `.peb` files. Beside **Load the default template**, under **Advanced**, **Choose a template file** loads the chosen file into the template field, replacing what's there. It must end in `.peb`, be no larger than 256 KB, and not be empty; otherwise a message says why, and the field is unchanged.
- **State** — the catalog being edited and the publish in progress — lives in process-wide objects rather than an activity-scoped ViewModel. Static state in the plugin's classes survives the tab being rebuilt (confirmed on device), and a single object is also the natural one-publish-at-a-time lock.
- **Extra JSON** is kept as typed; only a valid JSON object reaches the catalog, and publishing is refused while it's invalid.
- **Tooltips** via `DocumentationExtension`, in three tiers: long-press a heading, button or checkbox for a one-line tooltip; *See more* for detail, with a button; and the button opens the plugin's own help pages, at the right section. The pages, six of them — overview, the store, products, pictures and videos, templates, and publishing — are plain HTML in `src/main/assets/docs/`, loading nothing from the internet, which `getTier3DocsAssetPath()` returns as `"docs"`; CodeOnTheGo indexes them when the plugin is installed, and serves them locally. `HelpPages`, in the plugin's core, maps each of the 21 tags to its button's page and section, and a test checks every one exists. Text fields keep their own long-press for selecting and pasting text, so their help is on their headings. CodeOnTheGo files the entries under `plugin_org.appdevforall.shareyourapps`, derived from the plugin ID, and the Share Your Apps tab passes that category to `showTooltip` explicitly, as CodeOnTheGo's plugin checklist requires; its documentation says the two-argument form looks in the IDE's own category instead. It also adds its own third-party disclaimer to each entry.

## Confirmed on device

- A plugin can install and launch another APK. Use the application context for `PackageInstaller`.
- The server app can read the plugin's files in shared storage via `MANAGE_EXTERNAL_STORAGE`.
- In CodeOnTheGo, fragment-scoped ViewModels are lost on configuration changes; activity-scoped ones survive.
- `plugin.id` is `org.appdevforall.shareyourapps`.
- With two phones, a buyer joins through the QR codes, enters the PIN, and downloads and installs an APK.
- The themed icon appears when Samsung's *Apply palette to app icons* is on.
- A release build carries no CodeOnTheGo LogSender; debug builds do, and show its notification.
- The phone's own Mobile hotspot is recognized on a Samsung, whose hotspot interface is `swlan0`: the server serves on it, and turning it off stops the server.
- In the store's page, a product's picture arrows appear over its picture strip, hide at each end, and move one picture per tap.
- Posters: each carousel video shows its poster, taken 1.5 seconds in, until it's played, for both MP4 and WebM.
- The publish message's timestamp follows the phone's *Use 24-hour format* setting, switching between `4:32:10 PM` and `16:32:10`.
- The server's tests for sharing a store pass on the phone, so every cryptographic call they use works on its own Java.
- A whole share between two phones — an owner's phone and a Galaxy S23 Ultra on Android 16 — with the code, the check code, approval and the transfer of the copy.
- Serving the imported copy on that second phone, with **Serve this store**.
- The server's tests for expiry pass on the phone, including stopping for an expired copy: a download under way finishes, and a new connection is refused.
- Expiry on the seller's phone: with the date moved past a copy's expiry, the server stopped within a minute, and Manage Server showed *A store has expired*, with no date. (The clock rule — the time set back more than five minutes — is still to be tested on a phone.)
- Text fields keep their own long-press, in the server app and the plugin: long-pressing the port or PIN field selects its text, while its label shows the help; the hotspot's **Network name** and a product's **Name** labels have help.
- The plugin without AppCompat or Material: the Share Your Apps tab, its pickers, **Describe**, **Publish** with its confirmation, and long-press help all work as before, in an 8.2 MB `.cgp`, down from 13.9 MB.
- The server app without AppCompat, ConstraintLayout, Material, Startup or Interpolator, with resource shrinking (1.0.36): it works as before, in a 0.3 MB APK, down from 2.5 MB.
- The storekeeper icons (plugin step 70): the Addons Manager shows them in Share Your Apps's entry, switching between the day and night icons with the phone's mode, clear on both. Other plugins' icons are rounded tiles; the storekeeper's transparent background was kept by choice.
- Source zips kept only as their `.gz` copies (server 1.0.47, step 70; plugin step 68): after a publish, every source zip in the store is a `.zip.gz` alone, and the store, 150 MB before, is 63 MB. The publish's check finds no problems, and a source zip downloaded in a browser opens correctly.
- Faster publishing (plugin step 67): the same store published in 12 s, against 63 s before, with identical source zips.
- The publish progress line (plugin step 65): while the store is built, the status updates every half second with what's happening, and files and bytes handled, of their totals.
- Server 1.0.46 (server steps 66 to 69): Manage Server's address has its copy icon, which copies it, and keeps its long-press help; the line under **Show QR codes for buyers** reads *Start will serve…*, with *Start* in a hairline box, in light and dark mode; and the Share screen names *this phone* and *the other phone* throughout, with its download link complete on two lines.
- Server 1.0.42's three fixes (server step 65): the log size limit refuses 500 with its red note and accepts 0 and 100; tooltips near the screen's bottom, such as **Done** and **Cancel**, stay on screen, also after **+ See more**; and the Share screen's link and **Copy** appear under its small QR code. Server 1.0.43, with step 66's wording, is 157,563 bytes; the plugin bundling it, with step 65's progress line, 495,379 bytes.
- The server with compressed code, no Kotlin reflection metadata, and the 1 KB-page help database (1.0.39): **Start**, **Stop**, the buyers' QR codes, and all three tiers of help work as before. The plugin bundling it is 1,571,174 bytes.
- Beige help (server 1.0.37 and 1.0.38): tooltips, their *+ See more* form, and the help pages, with no white flash as a page opens.
- The plugin with AndroidX `compileOnly` and no R8 (plugin step 64, bundling server 1.0.41): it loads, publishes with and without installing the server, and starts the server, with no crashes, in a 492,163-byte `.cgp` — 96% smaller than the original 13.9 MB. Publishing spends 51 to 62 seconds building the store, with or without an install; installing is quick by comparison.
- The plugin's release build, signed with Share Your Apps Server's key (plugin step 58): it installs and works, in a 1.58 MB `.cgp` — 89% smaller than the original 13.9 MB.
- The plugin with compressed code, bundling the 0.3 MB server (plugin step 57): it loads and works as before, in a 2.1 MB `.cgp` — 85% smaller than the original 13.9 MB.
- The plugin without its own Kotlin library, using Code On The Go's, and with plain Fragment (plugin step 56): everything works, including publishing with an alternative template, in a 5.6 MB `.cgp`. The shorter, label-width buttons, with gaps between stacked rows, look right.
- **Publish** asks first: the dialog names the store, counts its products and names those left out; **Cancel** starts nothing, and **Publish** publishes as before.
- The plugin's three-tier help: the **PIN** tooltip showed its help, not *n/a*, and its button opened the plugin's own page, which Code On The Go had indexed at install.
- The built-in functions (D.2): `plural()`, `markdown` and `qr()` in a template extending the default — the QR code readable by a phone's camera — and `t()` translating by the store's language, falling back to English.
- The template library (D.1): extending the default and replacing one block, a macro and an include from `ShareYourAppsTemplates`, `parent()`, and a missing template stopping the publish with its name.
- The sharing panels' captions and buttons, on both phones: disabled, with the reason, when sharing or importing isn't possible, and changing as soon as the server starts or stops.
- The review's sharing fixes, on two phones: sharing waits for compression after a publish; the owner's code survives rotating the phone; an import finishes after leaving its screen, with its progress in a notification; importing over the owner's hotspot works with mobile data on, and warns it's the owner's network; and importing while serving is refused.
- The server's tests all pass on the phone, including stalled downloads and restarting on the same port straight after `stop()`, which `stop()` waiting for its threads made reliable.
- The store page shows the Extra JSON announcement above the products, the accent on the APK buttons, and *Made with Share Your Apps* with the plugin's version at the foot; the facts line under each download; and the *Accessible version* link and page.
- The sound handshake between two phones (Oct 10 and 12, 2026): the code was heard about three seconds after **Play sound**, the four-digit check codes matched, and the import finished. Over the owner's own hotspot, the listening phone joined it and left it afterwards. With the microphone permission denied, the app showed the *scan or type the code* message, and typing the code still imported. Neither phone's log held the hotspot's password.
- Android's **Connect** prompt (Oct 12, 2026): with the owner's phone on its own hotspot, `DIRECT-LS-CNCT`, the listening phone showed *Connect to device?* and a person tapped **Connect**; the check digits matched, the import finished, and the phone left the hotspot afterwards. Android asks only the first time a phone joins a hotspot of a given name, so a second run with the same name showed no prompt.
- MCP control on two phones: an import started through MCP showed the same check code as the sharing phone, and **Cancel** in the import notification, for an import started by MCP and for one a person started, left the phone's copy unchanged.

## Limitations

- **Phones put up obstacles to installing apps from outside an app store,** and Share Your Apps can't remove them. They meet buyers installing an APK from a store, and new sellers installing Share Your Apps Server (Appendix E.2):
  - **Android asks to allow the app doing the installing,** usually the browser, to install unknown apps. Allow it, install, then turn that setting off again, in **Settings → Apps → Special app access → Install unknown apps**.
  - **Some Samsung phones have Auto Blocker,** which blocks installing apps that don't come from a small set of allowed sources, such as Galaxy Store and Google Play. On those phones, turn Auto Blocker off to install — in **Settings → Security and privacy → Auto Blocker** — then install. Auto Blocker turns itself back on automatically.
- Plain HTTP, so a PIN a buyer types crosses the network in plaintext; no limit on PIN guesses; no minimum PIN length, so a one-digit PIN is allowed.
- Right after a publish, until background compression finishes, buyers download uncompressed files.
- Anything in shared storage can be read or changed by other apps on the device.
- A download in progress at the moment the server switches directories is cut off and must be retried; buyers viewing the old page reload.
- If the hotspot disappears on its own — Android stops it, or Wi-Fi is switched off under a Wi-Fi Direct group — the server stops, cutting off downloads; the owner taps Start or publishes again, and buyers on a local-only hotspot must join its new network.
- Changing the Wi-Fi Direct hotspot's name or password means buyers who joined before must join again.
- The server doesn't start by itself after the phone restarts.
- **No DNS server, so no captive portal and no friendly name.** A DNS server answering every name with the phone's own address, with the server answering phones' connectivity checks, would make buyers' phones treat the hotspot as a captive portal, and open the store by themselves, as a café's sign-in page does — and would allow a name such as `http://store/` in place of the address. But apps can't listen on ports below 1024 without root, and DNS needs port 53; and Android runs the hotspot's DNS itself, as part of its own hotspot service, which an app can't replace. So buyers' phones report the hotspot as having no internet, and the QR codes and the Serve screen's airplane-mode tip stay the answer. Advertising the store by name with Android's service discovery, which needs no special ports, would mainly help iPhones and laptops, which open `.local` names; it isn't built. (Decided September 30, 2026.)
- In *Pause when the screen turns off* mode on Android's local-only hotspot, every resume brings a new network name and password.
- A store with its own template still shows the server's own pages — the PIN page and the message pages — in the default look, since the server draws those itself.

## Known open items

Worth doing only if testing shows a need:

- **A Wi-Fi lock.** Holding a high-performance Wi-Fi lock while serving, if downloads turn out to slow down with the screen off.
- **Open question: offering the right APK for a buyer's phone.** Some apps come in separate 32-bit and 64-bit versions — Code On The Go does — and the store can't yet tell which one a buyer needs. What decides it is which kinds of apps the phone's Android can run, not its CPU: many low-cost phones, including many *Android Go* models, have 64-bit CPUs but run 32-bit-only Android, and many phones from about 2023 on run 64-bit apps only. Neither JavaScript nor WebAssembly can see that directly, but the browser's own bitness is a good stand-in: a 64-bit browser means the phone runs 64-bit apps, and a 32-bit browser means it runs 32-bit apps, so choosing by the browser gives a version that installs on essentially every phone. The ways a page could learn that:
  - **`navigator.platform`** — many Android browsers report `Linux aarch64` when 64-bit and `Linux armv8l` or `armv7l` when 32-bit. It's free and harmless, but browsers are trimming such details, so it may be missing or frozen on some.
  - **A WebAssembly memory probe** — a 32-bit browser can't reserve a very large WebAssembly memory, which a 64-bit one usually can. It's a heuristic whose limits differ between browsers, and a large reservation could strain a low-memory phone.
  - **User-Agent Client Hints** state the architecture and bitness directly, but only on HTTPS pages, which Share Your Apps's aren't. WebAssembly's *relaxed SIMD* results separate ARM from Intel, not 32-bit from 64-bit.

  The likely answer: **APK variants** — a product with several APKs, whose architectures the plugin reads from each APK's own `lib/` folders — with templates suggesting one variant by `navigator.platform` and always listing the others, with a line of help: *if Android says the app isn't compatible, use the other*. A wrong guess is harmless, since Android refuses an incompatible APK without changing anything. Before building on either method, a probe page run over Share Your Apps on testers' phones would show what their browsers really report. Until then, the demo catalog lists each variant as its own product. **Already done:** the plugin reads each APK's architectures and shows buyers which phones it's for — *For 64-bit phones*, *For 32-bit phones*, *For 32- and 64-bit phones*, or *For all phones* when it has no native code.
- **Extending templates:** the template library and built-in functions (Appendix D.1, D.2) are built. Owners' own compiled extensions (D.3) are set aside, unless owners come to need something the first two can't do; new needs are better met by more built-ins first.
- **Sharing a store with another seller:** built, as Appendix E describes. Its transfer protocol is to have an independent review before any public release. The review packet is `docs/share-protocol-review.md`: the protocol byte by byte, the threat model, the code and tests, and the questions to answer. The general review prompt, `docs/review-prompt.md`, covers the rest of sharing.

Handled elsewhere, or later:

- **Developer verification:** registering the server's package with Google, and the wider question for Code On The Go, are handled outside this project.
- **Signing in CI,** which would take the server's key off the phone. It needs the projects on GitHub first.

## Automated tests

Both projects carry JUnit tests that run on a computer's Java with `./gradlew testDebugUnitTest`. Each test runs a list of named checks and, if any fail, fails naming them.

- **Server** (`app/src/test/java`, 16 test files, 48 tests): the HTTP server — routes, confinement to the Store directory, the gzip rule, caching headers, exact-match 304s and ETags, the 5-second limit on a request, a client sending a byte a second, senders and stalled downloads, range requests, downloads stopped partway being logged, the 16-sender cap and its busy page, stopping with downloads still being sent, the log under many downloads at once, logging and restarts; PIN, cookie and token; the help database, read with CodeOnTheGo's own queries; Wi-Fi and hotspot address choice; QR codes that decode back exactly; the log search and filters; the hotspot name and password rules; the shared page look; help — every entry with a tier-3 button whose page and section exist, and the ⓘ panel's rows; and **sharing a store** (Appendix E): signatures, the store's identity, the commitment and check code, key derivation against RFC 5869's test case, the encrypted stream and every way it can be damaged, the copy and every unsafe or damaged kind it refuses, a whole share between two servers with every refusal — denial, a second phone, a mismatched key, an expired or guessed code, a mismatched protocol — the app download, cancelling, **expiry** by date and by clock, sparing copies that never expire, the clock restarting at import, the notice saying nothing about why, and downloads finishing while new connections are refused; **MCP control** (33 tests: sessions, methods, tool schemas, bad arguments and every error answer, without Android); and **the sound handshake** (Appendix E.8): the modem and decoder against noise, echo, clipping and a late start, the payload's layout and CRC and its password never being shown, the level meter, the play plan (at least four bursts, filling a 30-second window) and the `[sound]` log lines. Test libraries: JUnit 4.13.2, the SQLite JDBC driver, and `org.json`, whose Android version is an empty stub off-device. The tests must compile against Android's own `org.json`, which lacks some methods the standard one has, such as `keySet()`.
- **Plugin** (`src/test/kotlin`, 3 test files): an end-to-end test that publishes real catalogs, serves them and verifies every file — including posters (with stand-in poster makers), and the header video's poster, played on tap; the template library — extending the default, blocks, `parent()`, macros, includes, and names that try to leave it; each built-in filter and function, including Markdown that tries to inject HTML or a script; the tier-3 help pages — every button's page and section, and nothing loaded from the internet; unique slugs, with the design's example; signed and unsigned APKs, v1 and v2, and refusing to publish an unsigned one; descriptions becoming alt text, escaped, and captions files becoming tracks, with missing and non-WebVTT ones refused; product-named downloads, a pending directory surviving the next publish, and an unreadable catalog kept as `catalog.json.bad` — the HTML checker with the left-out reasons, and a test that keeps the annotated default template (`templates/default-annotated.peb`) in step with the shipped one, ignoring only comments and layout. The end-to-end test serves with a **copy** of nine of the server's core files — its HTTP server, and the files it depends on, including the four for sharing — kept in the plugin's test sources, labelled as copies, and refreshed when the server's change; it never ships in the plugin. Test libraries: JUnit 4.13.2, `org.json` (Android's is an empty stub off-device) and Pebble 4.1.1.

Running them on the phone, and the rules for writing them, are in *Developer notes*.

## Acceptance checklist

Run by hand on a device. The tester-facing version, with steps and expected results for each, is `share-your-apps-qa-steps.md`, on ADFA-6207.

1. Reopening the plugin shows the catalog as it was left.
2. Publishing a catalog with one APK product ends with "verified." A product with nothing to download is named as left out.
3. A phone on the same Wi-Fi scans the QR code and sees the store.
4. The APK downloads, named after the product (such as `great-scott.apk`), and installs.
5. The source zip contains no `.git`, `build/` or keystore files.
6. Images of 10 KB or less are inside `index.html`; larger ones are separate files.
7. Once background compression has finished, a browser receives `index.html` and the source zip with `Content-Encoding: gzip`; `curl` without `Accept-Encoding` receives them uncompressed. The downloaded source zip opens, both before and after compression finishes.
8. With a PIN set, opening the store redirects to the PIN page; a wrong PIN is refused; the right one opens the catalog.
9. After entering the PIN, returning to the store later doesn't ask again, and opening `/pin` goes straight to the catalog. A made-up `ls_auth` cookie value is sent to the PIN page.
10. With a PIN set, verify succeeds using the token. A wrong token, or the previous run's token, is treated as unauthenticated. A start intent sent from any app other than CodeOnTheGo is ignored.
11. After changing the PIN and republishing, an already-signed-in phone keeps working.
12. After removing a product and republishing, it's gone from the page and from disk.
13. While a large catalog is publishing, the old store keeps serving until the switch; afterward exactly one `Store_` directory exists.
14. After a republish, the server runs in a new process (a different PID), and a phone joined to the hotspot stays connected.
15. The store stays reachable for a minute while the owner is in CodeOnTheGo.
16. In hotspot mode, a phone joins with the first QR code and opens the store with the second.
17. With Wi-Fi switched on but not connected, starting the server brings up a `DIRECT-LS-` Wi-Fi Direct group, with the same name and password every time, and Manage Server shows them.
18. With Wi-Fi switched off, starting the server falls back to Android's local-only hotspot, and the log shows the Wi-Fi Direct failure before it.
19. From Code On The Go's terminal on the same phone, `curl` to the hotspot address shown in the log serves the store; stopping the server turns the hotspot off.
20. After a publish, the access log shows a `[gzip] done` line for `index.html` and each source zip, then a `finished` line; filling the disk during compression produces a `failed` line and the uncompressed file keeps being served.
21. A server started before any publish shows "not set up yet."
22. Unsaved edits in Manage Catalog survive rotating the device and changing the font size.
23. A Wi-Fi or Mobile hotspot password typed on the Serve screen survives rotation, font size, light/dark and screen-size changes, and is gone after closing the screen.
24. Long-pressing a Manage Server control shows its tooltip, with a faint ⓘ; the **+ See more** link shows its detail and a button that opens its help page at the right section; ⓘ shows the database rows behind it. Long-pressing a text field still selects text. The Share Your Apps tab's tooltips, likewise, open the plugin's own pages.
25. With the port already in use, the launch returns that error and the plugin reports it. After the port is freed, Start in Manage Server serves the previous store.
26. On the Serve screen, a screenshot opened in Google Lens decodes step 1 as the right network (offering to join it) and step 2 as the store's address; on Wi-Fi with no password typed, only the address code is shown, and a typed password survives rotating the phone.
27. With one buyer's download stalled — their phone's Wi-Fi switched off mid-download — another buyer can still open the store straight away, and after two minutes the log shows the stalled connection closed.
28. Leaving the Wi-Fi network while serving stops the server within a few seconds, with the reason *the Wi-Fi network was lost*.
29. In *Pause when the screen turns off* mode, turning the screen off pauses the server, and unlocking the phone resumes it by itself.
30. Killing Code On The Go while the server's permission screen is showing, then publishing twice, never leaves the store unreachable.
31. With Wi-Fi off and the phone's own Mobile hotspot on, the server serves on it, a buyer who joined it opens the store, and turning the Mobile hotspot off stops the server within about 10 seconds.
32. A publish that updates the server: Android asks to update Share Your Apps Server, and the store serves again afterwards.
33. On Android 13 to 15, Back in help goes back a page.
34. **Sharing a store** (Appendix E; QA steps section J): share and import, by QR code and by typed code, with matching check codes; the app for a phone without it; **Deny**; the code's limits — five wrong codes, 10 minutes, one phone; **Cancel**, leaving mid-transfer, and **Try again**; importing over the owner's hotspot with mobile data on; serving a copy with its own PIN; the sharing panels' captions; **Start** on a copy-only phone; a QR link while serving; a newer copy replacing an older; **Remove this store**; expiry by date and by clock; and publishing on a phone that holds a copy.
35. **MCP control** (QA steps section L): off by default, with no token or notification; turning it on asks first; **Copy token** and **New token**; the endpoint refusing a missing or wrong token, a foreign `Host` or `Origin`, another path, GET, a body that isn't JSON, broken JSON and a body over 1 MB; the server and share tools; bad arguments refused with a reason; port 6176 kept for MCP; an import started through MCP, with matching check codes; and MCP never approving a copy, including after **Cancel** in the import notification.
36. **The sound handshake** (QA steps section M): **Play sound** and its attempts counter, and stopping it; **Listen**, with its 30-second countdown and microphone level; importing by sound on shared Wi-Fi, and over the owner's own hotspot with Android's **Connect** prompt; nothing heard; the microphone permission denied, with typing the code still working; the hotspot's password absent from both logs; and the one-phone self-test.
37. **Start and Stop follow the server's state** (QA steps G.13 and G.14): each enabled only when it makes sense, green and red, grey when disabled, through reopening the screen, dark mode and a large font.

## Developer notes

How to build, release and test Share Your Apps, and pitfalls to know — what isn't obvious from the code or the sections above.

### Versions and build times

Both apps show which build they are, at the foot of their main screen: *Share Your Apps Server 1.0.49 · built Oct 9, 2026, 6:45 PM* in Manage Server, and *Share Your Apps 1.0.2 · built … · with Share Your Apps Server 1.0.49* in the Share Your Apps tab, naming the server it bundles. That matters when two phones must match. The server's `versionName` follows its `versionCode`, as `1.0.<versionCode>`, so raising `versionCode` is all a build needs, and Android's own app settings show the same number. Each build time is a `BuildConfig.BUILD_TIME` field recorded when Gradle configures the build. Release builds run with `--no-configuration-cache`, so theirs is exact; a debug build that reuses a cached configuration can show an older time.

### Edge-to-edge (Android 15 and later)

Apps targeting Android 15 or later are drawn behind the status and navigation bars, and must keep their content clear themselves. Share Your Apps Server targets Android 16, which ignores the opt-out Android 15 allowed. So every screen's content passes through `SystemBars.fit`: after layout, it measures how much the bars and any camera cutout actually overlap it, where it really sits in the window, and pads each edge by exactly that, on top of any padding it already had. On Android 16 the title bar overlaps the content too, so its bottom edge counts as well, found and measured in the window. Measuring matters: where the title bar already pushes the content down, the content is below it, and a fixed padding would leave a gap. Confirmed needed on a Samsung Galaxy S23 Ultra with Android 16. Scrolling screens scroll under the bars, but always clear of them. It remeasures on rotation, and does nothing on Android 14 and older. A new screen must call it too.

### Building

Both projects build in Code On The Go.

- Code On The Go bundles **Gradle 9.6.1**, which needs **AGP 9.3.1**; AGP 8 doesn't work with it. Use exactly 9.3.1, so offline builds resolve it from Code On The Go's bundled repository.
- Keep `plugin-api.jar` and `gradle-plugin.jar` in the plugin project's own `libs/`.
- With AGP 9, **drop the `org.jetbrains.kotlin.android` plugin and the `kotlinOptions {}` block**; Kotlin support is built in.
- Both apps have `minSdk` 28 (Android 9), the same as Code On The Go.
- The plugin's build produces a `.cgp` file in `build/plugin/`. Install it from Code On The Go's **Addons Manager**, then activate it.
- **From the terminal,** in the plugin's folder: `sh ./gradlew assemblePluginDebug -Pandroid.aapt2FromMavenOverride=…`, which makes `build/plugin/share-your-apps-debug.cgp`, or `assemblePlugin` for a release, which makes `build/plugin/share-your-apps.cgp`, with the same `aapt2` path as for the tests (see *Running the tests on the phone*). It ends with `Plugin assembled:` and the `.cgp`'s path.
- **Release builds are signed with Share Your Apps Server's key,** from the same `release.properties` in Code On The Go's private folder (see *The release signing key*). Without that file, a release build still works, but isn't signed. The build file imports `java.io.File` and `java.util.Properties`, since in a Gradle build file `java` alone means Gradle's java extension. R8 stays off — see *Why not R8* below. A release `.cgp` is 1.58 MB, against 2.06 MB for a debug one: it leaves out debugging information, and compresses at a higher level — the debug build uses level 1, the fastest. Since the two are signed with different keys, installing one over the other may need the plugin uninstalled first. If a build seems to ignore a change, add `clean` before the task and `--no-configuration-cache` after it.
- The server needs **ZXing** for its QR codes. Keep this line in its `app/build.gradle.kts`: `implementation("com.google.zxing:core:3.5.3")`.

### Bundling the server into the plugin

The plugin carries the server as `src/main/assets/share-your-apps-server.apk`, and installs it when it's missing or older. Each time the server changes:

1. Raise `versionCode` in the server's `app/build.gradle.kts`. The plugin only installs a server *newer* than the installed one, and refuses to publish if the installed one is newer.
2. Run the plugin's `downloadAssets` task (`./gradlew downloadAssets`, as its own command). It builds the server's **release** variant from `server/` and copies it to `src/main/assets/share-your-apps-server.apk`. CI does this for every plugin build, signed with the release key (see *The release signing key*).
3. Rebuild the plugin. Publishing installs the new server.

The bundled APK is a build output, so it's left out of git by the plugin's `.gitignore`. Copy it in before building a plugin file. **Making a plugin file fails without it**, naming the missing file and what to do, so a clean clone can't hand out a plugin with no server. The guard is on every task whose name starts with `assemblePlugin` — `assemblePluginDebug`, and `assemblePlugin` for a release — since either kind may be what testers get. Compiling and running the tests don't need the server.

### The release signing key

Every server build the plugin installs must be signed with the **same key** as the copy already on the phone. Otherwise Android refuses the update, and the owner must uninstall the server first.

- **The server has its own key,** not Code On The Go's phone-wide one. On first run, Code On The Go generates one key per installation and copies it into every project it creates, in shared storage beside its password, where any app with All-files access, or storage access on Android 9 and 10, could read both. The server is installed with All-files access on every owner's phone, so it's signed separately.
- **The key:** `localstore-server.p12`, alias `localstore-server` (named before the rename to Share Your Apps; an alias can't change), RSA 4096, PKCS12, so its key password equals its store password. Certificate SHA-256: `63:4F:53:2A:FB:9D:22:A7:84:60:6D:E3:D9:CD:7E:F8:61:1A:7E:26:F4:85:70:AE:9C:B8:BA:C0:10:C3:9B:0C`. It's backed up off the phone; losing it means no future build can update installed servers.
- **Release builds are signed in CI.** The **Build addon artifacts**, **Publish addons** and **Update libs from CodeOnTheGo** workflows run `scripts/server-signing-key.sh`, which writes the key from repository secrets to the runner's temporary folder and exports the four `SERVER_SIGNING_*` variables that `app/build.gradle.kts` reads. Then `scripts/build-plugins.sh` runs the plugin's `downloadAssets` task, which builds the server's release and bundles it. The secrets:
  - `SERVER_SIGNING_KEYSTORE_BASE64`: the key file, base64-encoded (`base64 -i localstore-server.p12`)
  - `SERVER_SIGNING_STORE_PASSWORD` and `SERVER_SIGNING_KEY_PASSWORD`: the same password
  - `SERVER_SIGNING_KEY_ALIAS`: `localstore-server`

  Without the secrets, a CI build of this plugin stops at `downloadAssets` rather than bundle an unsigned server; other plugins build as before.
- **Building on the phone:** without the `SERVER_SIGNING_*` variables, `app/build.gradle.kts` reads all four values from `~/keys/release.properties` in Code On The Go's private home folder (`/data/data/com.itsaky.androidide/files/home/keys/`), so no app with storage access can read them. Its `storeFile` is the key's absolute path. **Never put a path or password in the build file itself**, which is committed. The project itself is in shared storage, so an app with All-files access could change its build files or git hooks, and the next build — run as Code On The Go, which can read the key — could steal it. So keep the key off the phone and let CI sign releases; a build on the phone without `release.properties` is unsigned, which is fine for testing.
- **Check which key signed an APK** by certificate fingerprint, never by file hash:
  ```
  java -jar ~/android-sdk/build-tools/36.0.0/lib/apksigner.jar verify --print-certs app/build/outputs/apk/release/app-release.apk | grep "SHA-256"
  ```
  It should print the fingerprint above, in lowercase without colons. A fingerprint starting `dda275e4` is Code On The Go's phone-wide key on the development phone, which must not be used.
- **If signing settings seem ignored,** or a release build takes a second or two, Gradle has reused a configuration saved from an earlier build. Build clean, without it: `sh ./gradlew clean :app:assembleRelease --no-configuration-cache -Pandroid.aapt2FromMavenOverride=…`.
- **Compare keys by certificate fingerprint:** `keytool -list -v -keystore <file> | grep SHA256`. Two keystore files can hold the same key and still hash differently.

### Updating the server's help

The server's three-tier help — long-press tooltips, **See more**, and full help pages — comes from `app/src/main/assets/web_server_documentation.db`. It uses the same tables and queries as Code On The Go's own `documentation.db`, with every page stored as plain HTML.

The help text lives in `help/build_help_db.py`, which generates the database. **The app copies the database only when its `versionCode` changes.** If you edit the help text, rebuild the database, then raise `versionCode` before building, or the old help stays in place:
```
python3 help/build_help_db.py app/src/main/assets/web_server_documentation.db
```

### Running the tests on the phone

On a computer, `./gradlew testDebugUnitTest` from each project's folder is all it takes. On the phone, two things are different:

1. **Start Gradle with `sh`.** Shared storage doesn't keep the executable bit, so `./gradlew` won't run there.
2. **Point Gradle at Code On The Go's own `aapt2`.** From a terminal, Gradle downloads the standard `aapt2`, which is built for Linux computers and can't run on a phone. The build then fails at `processDebugResources` with `Syntax error: word unexpected (expecting ")")`.

The full command:
```
sh ./gradlew testDebugUnitTest -Pandroid.aapt2FromMavenOverride=/data/data/com.itsaky.androidide/files/home/android-sdk/build-tools/36.0.0/aapt2
```
Pass that setting on the command line, **not** in `gradle.properties`, which goes with the project and would break builds on computers. If a later Code On The Go keeps `aapt2` elsewhere, find it with:
```
find /data/data/com.itsaky.androidide/files -name aapt2 -type f 2>/dev/null | grep -v transforms
```

**Expect one skipped check on the phone:** `made a Unix socket`. Unix socket paths are limited to 108 characters, and the test's temporary folder on the phone makes the path too long. Everything else runs. The skipped check still runs on computers.

**Where to find the results.** A check's `PASS`, `FAIL` and `SKIP` lines are in the test's standard output. The summary page only says which test failed, not why. To see the skips:
```
grep -h "^SKIP" build/test-results/testDebugUnitTest/*.xml
```
For the server, the path starts with `app/build/…`. The report `build/reports/tests/testDebugUnitTest/index.html` shows the same lines under each test's **Standard output**. When a test fails, its error is in the same `.xml` file, in the `<failure>` element.

### Writing tests

- **Tests compile against Android's version of Java's library** (`android.jar`), but run on full Java. So they may use only the Java features Android also has. Reach anything newer through reflection, as `PublishTest.kt` does for Unix-domain sockets, or the tests won't compile under Gradle.
- **Android's `org.json` is only an empty stub off the device.** Tests that use it need the real one: `testImplementation("org.json:json:20240303")`.
- **Pebble is `compileOnly` in the plugin,** because Code On The Go provides it at run time. Tests need their own copy: `testImplementation("io.pebbletemplates:pebble:4.1.1")`.
- **The plugin's icons** (from plugin step 70), `assets/icon_day.png` and `assets/icon_night.png`, named in the manifest's `plugin.icon_day` and `plugin.icon_night`, replace the template's *DAY* and *NIGHT* placeholders. They show the storekeeper: Share Your Apps Server's purple octopus, under a red-and-cream awning, with its tentacles on an orange counter, so the plugin and the server look alike. Both are 96 × 96, with transparent backgrounds: the day icon with dark outlines and strong colours, for Code On The Go's light theme, and the night icon with light outlines and lighter colours, for its dark theme. `docs/plugin-icons/draw_icons.py` draws them, at four times the size, then reduces them, for smooth edges.
- **The plugin bundles only what Code On The Go doesn't have,** to keep its `.cgp` small. Its code uses just two AndroidX classes, `Fragment` and `ActivityResultContracts`, so it depends on plain AndroidX Fragment — not `fragment-ktx`, whose Kotlin conveniences it doesn't use — and not on AppCompat or Material, which its template had added. Together those made up most of the old 13.9 MB `.cgp`. The plugin also compresses its compiled code, with `packaging { dex { useLegacyPackaging = true } }`: Android's default stores dex files uncompressed, so apps can run them in place, which had made them 4.3 MB of the `.cgp`; Code On The Go's class loader handles compressed code. With the smaller server, the `.cgp` is now 2.1 MB: 1.8 MB of compressed code, and 0.2 MB of server. Without Material, `PluginTheme` is based on Android's own `android:Theme.DeviceDefault.NoActionBar`, the icon's tint is the template's `@color/plugin_on_surface` rather than Material's `?attr/colorOnSurface`, and the template's unused `fragment_main.xml` is gone. The Kotlin library is `compileOnly` too, taken from Code On The Go like Pebble, with `kotlin.stdlib.default.dependency=false` in `gradle.properties`. That alone isn't enough: AndroidX Fragment brings its own Kotlin 1.8 library, and older 1.6 parts, which then collide as duplicate classes. So every runtime classpath but the unit tests' excludes `kotlin-stdlib`, `-jdk7`, `-jdk8` and `-common`; the tests keep their own copy.
- **AndroidX is `compileOnly` too (plugin step 64).** Code On The Go loads classes **parent-first**: when the plugin uses a class Code On The Go also has, Code On The Go's copy runs, never the plugin's. So a bundled AndroidX was dead weight. Code On The Go's APK (fragment 1.8.9, activity 1.10.1, lifecycle 2.9.3) has all six AndroidX classes the plugin uses, with every member it calls: `Fragment` (`<init>()`, `onResume()`, `onPause()`, `onDestroyView()`, `requireContext()`, `registerForActivityResult`), `ActivityResultContracts$StartActivityForResult`, `ActivityResultLauncher.launch(Object)`, `ActivityResultCallback.onActivityResult(Object)`, `ActivityResult` (`getResultCode()`, `getData()`), and `ActivityResultContract`. An earlier version of this design said Android's build tools don't allow `compileOnly` for AAR libraries such as Fragment; in practice it builds and works. The risk, as for Kotlin, Pebble and the plugin API: a future Code On The Go could drop or change one of these, which would fail at run time, not in the build — so any new AndroidX use is checked against Code On The Go's APK first, as `build.gradle.kts` says. ZXing stays bundled: Code On The Go doesn't have it. The release `.cgp` is about 490 KB: the plugin's own 183 classes, ZXing's 291, and the bundled server.
- **Why not R8.** R8, which shrinks code, was tried for the plugin's release build (plugin steps 59 to 63), and gave a 0.5 MB `.cgp`. It crashed Code On The Go three times, each time by changing what Code On The Go calls in ways R8 couldn't see, since Code On The Go, not the plugin, is the caller: a `NoSuchMethodError` (R8 specialised the bundled `ActivityResultLauncher.launch(Object)` to `launch(Intent)`, which Code On The Go's copy doesn't have); a `NullPointerException` from `requireActivity().startActivity()`, so the install confirmation now opens through a registered launcher; and an `AbstractMethodError`, when R8 removed `onActivityResult` — Code On The Go's activity delivers results, so R8 saw no caller — and with it the bodies of both result callbacks, which `-keep class org.appdevforall.shareyourapps.**` can't protect, since R8 makes `$$ExternalSyntheticLambda` classes itself, after keep rules apply. With AndroidX `compileOnly`, there's almost nothing left for R8 to shrink, so it's off. `proguard-rules.pro` keeps what those crashes taught, in case R8 is ever turned back on.
- **The server app bundles only what it uses: ZXing, for QR codes, and the Kotlin library.** Its code uses nothing from AndroidX or Material — its activities are plain `Activity`s, its theme is Android's own `Theme.Material.Light.DarkActionBar`, and it shares logs through its own `LogProvider` — so it doesn't depend on AppCompat, ConstraintLayout, Material, Startup or Interpolator, which its template had added. Its release build shrinks resources as well as code (`isShrinkResources`), and the template's unused Material 3 `AppTheme` and `activity_main.xml` are gone. That took its APK from 2.5 MB to 0.3 MB (from version 1.0.36). Being a standalone app, it has to bundle the Kotlin library, which R8 trims. From 1.0.39, it also compresses its code, with `packaging { dex { useLegacyPackaging = true } }`, which halves `classes.dex` in the APK, from 270 KB to 131 KB, and leaves out Kotlin's reflection metadata, the `kotlin_builtins` files, since it uses no Kotlin reflection — so the APK is about 150 KB. The trade-off: Android stores app code uncompressed so it can run it from the APK, so with compressed code it unpacks a copy on install, and the installed app takes slightly more space; the download, which sellers pass between phones, halves. Its icon is a vector drawing, so there are no PNGs to convert. The plugin's 17 small PNGs — its two icons, and AndroidX's notification images — stay as they are: indexed colour made them larger, and WebP would save about 500 bytes, but AndroidX's 9-patch images must be PNG, and Code On The Go finds the plugin's icons by their names.
- **Buttons are sized by their labels:** Android's own button style has a minimum of 88 × 48 dp, so the plugin's secondary buttons set no minimum width and a 34 dp minimum height, with 4 dp margins above and below, so stacked rows of buttons don't touch.
- **The server's help test needs the SQLite driver:** `testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")`. Its native library does load under Code On The Go's Java.
- **The plugin's end-to-end test uses a copy of five server files,** in `src/test/kotlin/org/appdevforall/shareyourapps/server/`, to serve and verify a real store. They're test-only and never ship. **When the server's versions of those files change, copy them over again.**
- **Some checks can't run everywhere.** A check that depends on the machine — such as creating a special file — should call `skip()` with the reason, rather than failing or crashing.

### Writing a template

The catalog page is a Pebble template: the default is in Appendix C — annotated in C.1 — along with three alternatives, and owners can replace it under **Advanced** in Manage Catalog. Two of Pebble's rules differ from other template languages:

- **Conditions need `is not empty`.** A bare `{% if p.carousel %}` throws an error in Pebble; write `{% if p.carousel is not empty %}`, as the default template does. A template that throws stops the publish and shows the error, so a mistake never reaches buyers.
- **Pebble's `sort` filter takes no arguments.** There's no `sort(attribute=…)`, so products can't be sorted in the template. They arrive already sorted by `ordinal`, in Manage Catalog's order.

The template also receives the owner's **Extra JSON** as `extra`, and `accessibleHref`, the link to the accessible version, which a template should show near its top. It also receives facts the plugin works out itself, so owners never have to type them: the page's language, `lang` — Extra JSON's `lang` if the owner sets one, such as `"sw"`, else the phone's; when the publish happened; each product's stable ID, `slug`, and its date as an ISO date; the source zip's size; and, from each APK, its version, package name and minimum Android version, read with Android's `PackageManager`, and its native-code architectures, read from its `lib/` folders. Every template shows these under the downloads, such as *Version 2.1 · Android 9 or later · For 64-bit phones*, leaving out any part that can't be read. Pictures carry their width and height, read by the plugin from each file's own header — PNG, GIF, JPEG or WebP — and videos their poster's; and `shareYourAppsVersion` gives a *Made with Share Your Apps* line, whose *Share Your Apps 1.0.0* every built-in template links to App Dev for All's plugins page, `https://www.appdevforall.org/plugins`, in the note's own colour, underlined. It opens only for buyers with internet — not on the server's own hotspot. C.1 lists everything a template receives.

**Documented Extra JSON keys.** The plugin checks these before any template sees them, so templates can use them without checks of their own:
- **Store:** `lang`, the page's language. `contact` — a name, email, phone and website — becomes `contact`, with `emailHref`, `phoneHref` and `urlHref` only for plausible addresses, and only `mailto:`, `tel:` and `http(s):`. `announcement` is a short line of plain text. `accent` is accepted only as `#rgb` or `#rrggbb`, and comes with `accentInk`, black or white, whichever contrasts more.
- **Each product:** `whatsNew`, `license` and `author`, as plain text, and `website`, with `websiteHref` only when it's `http(s):`.

**The accent's contrast guarantee.** For any colour, black or white text on it reaches at least 4.58 to 1 — checked across 4,096 colours in the tests — so a template that puts `accentInk` text on an `accent` background always meets WCAG's 4.5 to 1, whatever the owner picks. It holds only at full strength: text faded with `opacity` loses the guarantee. Every template in Appendix C uses the accent for its APK button; Clear Access ignores it, keeping its own colours.

Every template in Appendix C passes axe-core's WCAG 2.2 level A and AA checks, and its best-practice rules, with the demo catalog. For a new template, the essentials are: text alternatives for pictures and labels for videos (the catalog has no descriptions, so *Termux, picture 1 of 3* is the pattern); a `<main>` region, with the rest in a header or navigation region; real headings for product names; picture strips that scroll made focusable, with `tabindex=0`, a role and a label; decoration hidden with `aria-hidden`; and text contrast of at least 4.5 to 1, including over animated or translucent backgrounds.

### Pitfalls

- **Plugin assets aren't unpacked automatically.** Files in the plugin's `src/main/assets` stay inside the plugin; read them with `openPluginAsset()`, as the plugin does for the bundled server APK. The plugin build also treats asset folders named `lib` or `notebooks` specially, so don't use those names.
- **Back needs handling two ways, and the manifest must opt in.** The server's manifest sets `android:enableOnBackInvokedCallback="true"`: without it, Android 13 to 15 ignore the back callbacks, and deliver no Back key either — which testing only on Android 16 hid. Apps targeting Android 16 get predictive back by default, and then `onBackPressed()` is never called. The help viewer registers an `OnBackInvokedCallback` on Android 13 and later, and handles `KEYCODE_BACK` before that.
- **Debug builds show an extra notification.** Code On The Go adds its LogSender service to debug builds, which shows a *Connected to Code On The Go* notification and makes the server's badge count 2. Release builds don't include it.
- **Plugin tooltips end with a third-party notice.** Code On The Go adds *This documentation is provided by a third-party plugin* to every plugin tooltip. The plugin can't remove it.
- **Downloading a file twice renames the second copy,** for example `PublishTest (1).kt`, so a `cp` of the original name copies the old one. After copying, check for something only the new version contains, with `grep`.
- **Paths with spaces need quotes** in shell commands, as in `"Share Your Apps Server"`.
- **An open store tab shows the previous publish until it's reloaded.** Every publish reuses the same addresses, and the server sends `Cache-Control: no-cache`, so a reload always shows the latest.
- **Themed icons on Samsung** are under **Settings → Wallpaper and style → Color palette → Apply palette to app icons**. The launcher can cache old icons; switching that setting off and on refreshes them.

---

# Appendix A — The default store page

The default page's look comes from the Eight-Armed Merchant icon: the awning across the top (red and cream stripes with scalloped edges) over a teal header, a cream page, and outlined "sticker" cards and buttons with hard offset shadows — **Download APK** in the counter's orange with dark text, **Download source** in white with deep violet text. Every text colour meets 4.5:1 contrast; the icon's own violet (#8E5BFF) is too light for text, so it's used only for outlines and focus rings. No web fonts, since stores run offline. Products flow in **columns** at least 260 px wide, as many as fit — one on a phone held upright — with each card directly under the one above, so a tall card never leaves an empty space beside a short one. Reading order runs down each column, then on to the next, so Manage Catalog's order is kept. A product with more than one picture or video shows small **arrows** over its picture strip, alongside the browser's own scroll bar: the left one is hidden at the first picture, the right one at the last, and tapping one moves a picture. A few lines of script in the page keep them up to date as the strip is swiped, and when the phone is rotated; without the script, the right arrow still shows there's more. Each picture and video keeps 1 pixel clear above and below within its 160-pixel box, and the strip scrolls only sideways, so dragging the page on a picture can't nudge it out of place.

The template itself is in Appendix C: laid out and annotated in C.1, and exactly as the plugin ships it in C.2.

---

# Appendix B — Wireframes (source)

Eleven low-fidelity artboards — the plugin's screen and dialogs, the Share Your Apps Server app's screens, the app icon, and the buyer-facing pages — laid out on one canvas, with the shared CSS. Live canvas: **https://claude.ai/artifact/4TmREWW6G9eJQHn5oEKHPV**. The source below is the literal state of every file on that canvas (`project/*.dc.html`, `project/wireframe.css`, `project/canvas.json`). `wireframe.css` is shared by every artboard except the app icon, a self-contained inline SVG. `canvas.json` is the layout index: which file goes where, at what size, in what order.

**These wireframes came before the build, and the build differs from them in the places below.** As the introduction says, the design wins wherever a wireframe disagrees.

- **Manage Catalog (B.2):** the button is **Publish**, not *Create / Install / Launch Store*. Every change saves as it's made, so there's no Discard.
- **App icon (B.3):** replaced by the Eight-Armed Merchant (see *Launcher icon*).
- **Manage Server (B.4):** raised panels, including *Copies of this store*, *Import a store*, and one for an imported copy (Appendix E). There's no Wi-Fi toggle or Wi-Fi SSID and password display: when the phone isn't on Wi-Fi, the server starts a hotspot itself, and shows its name and password in the Status panel. There are added **Wi-Fi Direct hotspot** settings, **Show QR codes for buyers** and **Help** buttons, and a log folder line. The run modes are *Run always* and *Pause when the screen turns off*.
- **MCP control and the Start and Stop states (B.4):** the Server panel has the **MCP control** switch, with **Copy token** and **New token** below it when it's on, and **Start** and **Stop** show the server's state: green or red when enabled, grey when disabled.
- **Sharing by sound (E.8):** the share screen has a **Play sound** button, with a line counting the attempts left, and the import screen has a **Listen** button, with a countdown and a microphone level meter. Neither the wireframes nor the sharing mockups show them.
- **Serve screen (B.5):** on Wi-Fi, the owner's name and password fields come first and collapse once the code is shown. Values copy on tap, and on a hotspot a brace and copy icon copy the name and password together.
- **Full log view (B.6):** reached from **View logs**; its filters are *All*, *Requests* and *Status*, and its lines are the server's real log format.
- **Buyer catalog pages (B.7, B.8) and PIN page (B.12):** the Eight-Armed Merchant's look — awning, teal header, cream page, outlined cards — rather than the wireframe palette. A wrong PIN shows *That PIN isn't right.*
- **APK picker (B.9):** a plain list of the APKs under the project's `build/outputs/apk/`, newest first, not grouped by build type.
- **Pre-flight error (B.10):** a *Couldn't publish* dialog naming the problem.
- **Confirmation dialogs (B.11):** not built. **Publish** starts straight away, Android shows its own confirmation when the server is installed, and nothing needs discarding.

## B.1 Shared wireframe CSS (`wireframe.css`)

```css
:root{
  --bg:#eef0f3;
  --surface:#ffffff;
  --border:#c7ccd3;
  --border-strong:#9aa1ad;
  --text:#20242b;
  --muted:#6b7280;
  --accent:#485d92;
  --ok:#4caf50;
  --off:#9aa1ad;
  --danger:#f44336;
}
*{box-sizing:border-box}
body{margin:0;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;color:var(--text);background:var(--bg)}
.wf-screen{width:100%;height:100%;background:var(--bg);display:flex;flex-direction:column;overflow:hidden}
.wf-header{padding:20px 20px 14px;border-bottom:1px solid var(--border);background:var(--surface);flex:0 0 auto}
.wf-eyebrow{font-size:11px;letter-spacing:.08em;text-transform:uppercase;color:var(--muted);font-weight:700;margin:0 0 4px}
.wf-title{font-size:20px;font-weight:700;margin:0}
.wf-body{flex:1 1 auto;overflow-y:auto;padding:16px 20px;display:flex;flex-direction:column;gap:18px}
.wf-section-label{font-size:11px;letter-spacing:.06em;text-transform:uppercase;color:var(--muted);font-weight:700;margin:0 0 8px}
.wf-field{display:flex;flex-direction:column;gap:6px}
.wf-label{font-size:13px;font-weight:600;color:var(--text)}
.wf-input{border:1px solid var(--border-strong);border-radius:6px;padding:10px 12px;font-size:14px;background:var(--surface);color:var(--text);width:100%}
.wf-hint{font-size:11px;color:var(--muted)}
.wf-card{border:1px solid var(--border);border-radius:10px;background:var(--surface);padding:12px;display:flex;gap:12px;align-items:flex-start;flex-shrink:0}
.wf-card-stopped{background:#ffebee;border-color:var(--danger)}
.wf-status-stopped-text{color:var(--danger)}
.wf-card-raised{
  box-shadow:
    0 1px 0 rgba(255,255,255,.8) inset,
    0 1px 2px rgba(32,36,43,.08),
    0 4px 10px rgba(32,36,43,.10);
  border-color:#d6dae1;
}
.wf-panel-raised{
  border:1px solid #d6dae1;
  border-radius:12px;
  background:var(--surface);
  padding:14px;
  box-shadow:
    0 1px 0 rgba(255,255,255,.8) inset,
    0 1px 2px rgba(32,36,43,.08),
    0 4px 10px rgba(32,36,43,.10);
}
.wf-link-btn{font-size:11px;color:var(--accent);text-decoration:underline;background:none;border:none;padding:0;margin-top:6px;cursor:pointer;display:inline-block}
.wf-thumb{flex:0 0 auto;background:repeating-linear-gradient(45deg,#e3e6ea,#e3e6ea 6px,#d5d9df 6px,#d5d9df 12px);border:1px solid var(--border-strong);border-radius:8px}
.wf-row{display:flex;align-items:center;justify-content:space-between;gap:10px}
.wf-btn{border-radius:8px;padding:12px 16px;font-size:14px;font-weight:600;border:1px solid var(--border-strong);background:var(--surface);color:var(--text);text-align:center;text-decoration:none;display:inline-flex;align-items:center;justify-content:center;gap:6px;min-height:44px;min-width:0;cursor:pointer}
.wf-btn-primary{background:var(--accent);border-color:var(--accent);color:#fff}
.wf-btn-outline{background:transparent}
.wf-btn-danger{border-color:var(--danger);color:var(--danger);background:transparent}
.wf-btn-icon{min-height:36px;min-width:36px;padding:6px;border-radius:8px;flex:0 0 auto}
.wf-badge{font-size:11px;font-weight:700;border-radius:999px;padding:4px 9px;border:1px solid var(--border-strong);color:var(--muted);white-space:nowrap}
.wf-badge-on{border-color:var(--ok);color:var(--ok)}
.wf-toggle{width:40px;height:22px;border-radius:11px;border:1px solid var(--border-strong);position:relative;background:#e3e6ea;flex:0 0 auto}
.wf-toggle-on{background:var(--accent);border-color:var(--accent)}
.wf-toggle-knob{position:absolute;top:2px;left:2px;width:16px;height:16px;border-radius:50%;background:#fff;border:1px solid var(--border-strong)}
.wf-toggle-on .wf-toggle-knob{left:20px;border-color:var(--accent)}
.wf-toggle-v{width:22px;height:44px;border-radius:11px;border:1px solid var(--danger);position:relative;background:var(--danger);flex:0 0 auto;cursor:pointer}
.wf-toggle-v-on{background:var(--ok);border-color:var(--ok)}
.wf-toggle-v-knob{position:absolute;left:2px;bottom:2px;width:16px;height:16px;border-radius:50%;background:#fff;border:1px solid var(--danger)}
.wf-toggle-v-on .wf-toggle-v-knob{bottom:auto;top:2px;border-color:var(--ok)}
.wf-dot{width:8px;height:8px;border-radius:50%;background:var(--off);flex:0 0 auto}
.wf-dot-busy{background:var(--ok)}
.wf-divider{height:1px;background:var(--border)}
.wf-textarea{border:1px solid var(--border-strong);border-radius:6px;padding:10px 12px;font-size:13px;font-family:ui-monospace,Menlo,monospace;background:var(--surface);color:var(--muted);min-height:64px;white-space:pre-line;width:100%;resize:vertical}
.wf-progress{height:8px;border-radius:4px;background:#e3e6ea;overflow:hidden;border:1px solid var(--border)}
.wf-progress-fill{height:100%;background:var(--accent)}
.wf-product-desc{font-size:12px;color:var(--muted);line-height:1.4}
.wf-desc-box{position:relative;max-height:60px;overflow-y:auto;padding-right:14px}
.wf-desc-box p{margin:0;font-size:12px;line-height:1.4;color:var(--muted)}
.wf-desc-scrollbar{position:absolute;top:0;right:2px;bottom:0;width:4px;border-radius:2px;background:rgba(32,36,43,.14);pointer-events:none}
.wf-desc-scrollbar::after{content:"";position:absolute;top:4%;left:0;width:100%;height:46%;border-radius:2px;background:rgba(32,36,43,.4)}
.wf-desc-expand-btn{position:absolute;top:0;right:-2px;width:24px;height:24px;border-radius:50%;background:var(--surface);border:1px solid var(--border-strong);color:var(--muted);display:flex;align-items:center;justify-content:center;cursor:pointer;flex:0 0 auto}
.wf-desc-box-dark p{color:rgba(255,255,255,.85)}
.wf-desc-scrollbar-dark{background:rgba(255,255,255,.2)}
.wf-desc-scrollbar-dark::after{background:rgba(255,255,255,.6)}
.wf-desc-expand-btn-dark{background:rgba(255,255,255,.16);border-color:rgba(255,255,255,.3);color:#fff}
.wf-desc-overlay{position:absolute;inset:0;background:#233490;z-index:5;display:flex;flex-direction:column;padding:20px;box-sizing:border-box}
.wf-desc-overlay-body{flex:1;overflow-y:auto;color:#fff;transition:font-size .15s ease}
.wf-desc-zoom-row{display:flex;justify-content:flex-end;align-items:center;gap:8px;margin-top:12px}
.wf-desc-zoom-btn{width:32px;height:32px;border-radius:8px;background:rgba(255,255,255,.14);border:1px solid rgba(255,255,255,.3);color:#fff;font-size:16px;font-weight:700;cursor:pointer}
.wf-qr{border:1px solid var(--border-strong);border-radius:10px;background:var(--surface);padding:10px;flex:0 0 auto}
.wf-copy-btn{width:24px;height:24px;border-radius:6px;border:1px solid var(--border-strong);background:transparent;display:inline-flex;align-items:center;justify-content:center;flex:0 0 auto;cursor:pointer;color:var(--muted);vertical-align:middle}
.wf-copy-btn.copied{border-color:var(--ok);color:var(--ok)}
.wf-dialog-backdrop{width:100%;height:100%;background:rgba(20,22,26,.45);display:flex;align-items:center;justify-content:center}
.wf-dialog{width:90%;max-width:320px;background:var(--surface);border:1px solid var(--border);border-radius:14px;padding:20px;display:flex;flex-direction:column;gap:14px;box-shadow:0 8px 24px rgba(0,0,0,.18);flex-shrink:0}
.wf-list-row{display:flex;align-items:center;gap:12px;padding:12px 0;border-bottom:1px solid var(--border)}
.wf-list-row:last-child{border-bottom:none}
.wf-group-label{font-size:12px;font-weight:700;color:var(--muted);text-transform:uppercase;letter-spacing:.05em;margin:18px 0 4px}
.wf-group-label:first-child{margin-top:0}
.wf-credential-grid{display:grid;grid-template-columns:auto 1fr;column-gap:8px;row-gap:8px;align-items:center}
.wf-credential-label{font-size:13px;font-weight:600;color:var(--text);white-space:nowrap}
.wf-credential-value-btn{font-family:ui-monospace,Menlo,monospace;font-size:13px;color:var(--text);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;background:none;border:none;padding:0;margin:0;text-align:left;cursor:pointer}
.wf-credential-value-btn.copied{color:var(--ok);animation:wf-copy-blink .3s ease-in-out 2}
@keyframes wf-copy-blink{0%,100%{opacity:1}50%{opacity:.25}}

/* Log viewer */
.wf-log-panel{position:relative;flex:0 0 auto;background:#1c1f26;border-top:1px solid #000;display:flex;flex-direction:column;overflow:hidden}
.wf-log-header{display:flex;align-items:center;justify-content:space-between;padding:8px 12px;flex:0 0 auto}
.wf-log-title{color:#cfd6e3;font-size:11px;font-weight:700;text-transform:uppercase;letter-spacing:.06em}
.wf-log-body{flex:1;overflow:hidden;padding:8px 0 6px 14px;position:relative}
.wf-log-line{font-family:ui-monospace,Menlo,monospace;font-size:11px;line-height:1.6;color:#b9c2d3;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.wf-log-line-err{color:#ff8a80}
.wf-log-line b{color:#7fd08a;font-weight:600}
.wf-scrollbar-v{position:absolute;top:0;right:2px;bottom:2px;width:5px;border-radius:3px;background:rgba(255,255,255,.16);pointer-events:none}
.wf-scrollbar-v::after{content:"";position:absolute;top:8%;left:0;width:100%;height:38%;border-radius:3px;background:rgba(255,255,255,.45)}
.wf-scrollbar-h{position:absolute;left:0;right:8px;bottom:2px;height:5px;border-radius:3px;background:rgba(255,255,255,.16);pointer-events:none}
.wf-scrollbar-h::after{content:"";position:absolute;left:6%;top:0;height:100%;width:46%;border-radius:3px;background:rgba(255,255,255,.5)}
.wf-expand-btn{width:30px;height:30px;border-radius:50%;background:rgba(255,255,255,.14);border:1px solid rgba(255,255,255,.28);display:flex;align-items:center;justify-content:center;flex:0 0 auto}
.wf-log-hint{flex:0 0 auto;padding:2px 14px 8px;font-size:9px;color:#7f8aa0}

/* Full-screen log toolbar */
.wf-log-toolbar{display:flex;align-items:center;gap:8px;padding:10px 14px;background:#1c1f26;border-bottom:1px solid #000;flex:0 0 auto}
.wf-icon-btn-dark{width:36px;height:36px;border-radius:8px;border:1px solid rgba(255,255,255,.25);background:rgba(255,255,255,.08);color:#cfd6e3;display:flex;align-items:center;justify-content:center;flex:0 0 auto}
.wf-search-input-dark{flex:1;background:rgba(255,255,255,.08);border:1px solid rgba(255,255,255,.25);border-radius:8px;padding:8px 10px;color:#cfd6e3;font-size:13px}
.wf-filter-row{display:flex;gap:8px;padding:10px 14px;background:#1c1f26;border-bottom:1px solid #000;flex:0 0 auto}
.wf-chip{font-size:12px;font-weight:600;padding:6px 12px;border-radius:999px;border:1px solid rgba(255,255,255,.28);color:#cfd6e3}
.wf-chip-on{background:#3a4a6b;border-color:#3a4a6b;color:#fff}

/* Disabled action-row state (buttons + publishing status line) */
.wf-btn-disabled{opacity:.45;cursor:not-allowed;pointer-events:none}
.wf-publishing-status{display:flex;align-items:center;gap:6px;font-size:12px;font-weight:600;color:var(--accent);padding:0 20px 8px}
```

## B.2 `Main.dc.html` — Plugin — Manage Catalog (the plugin's one screen)

Now interactive: tapping "Create / Install / Launch Store" simulates a publish in progress — both action-row buttons dim and stop responding, and a "Publishing…" status line appears above the row, for ~2.4s before reverting. Demonstrates the disabled-button-and-status-line decision in *Publish*; the confirmation dialog that precedes a real tap is its own artboard (`ConfirmationDialogs.dc.html`, B.11) and isn't wired up here.

```html
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Plugin — Manage Catalog</title>
<script src="./support.js"></script>
<link rel="stylesheet" href="./wireframe.css">
</head>
<body>
<x-dc>
<helmet>
<style>
body{margin:0}
</style>
</helmet>
<div class="wf-screen" style="width: 390px; height: 844px;">
  <div class="wf-header" style="padding:12px 20px">
    <h1 class="wf-title">Manage Catalog</h1>
  </div>
  <div class="wf-body" style="gap:0">
    <div style="display:flex;flex-direction:column;gap:12px">

    <div class="wf-field">
      <label class="wf-label">Store name</label>
      <input class="wf-input" value="My Share Your Apps">
    </div>

    <div class="wf-field">
      <label class="wf-label">Store description (HTML, optional)</label>
      <textarea class="wf-textarea" rows="2">&lt;p&gt;Thanks for stopping by! Everything here is free.&lt;/p&gt;</textarea>
      <p class="wf-hint">Rendered as-is, unescaped, directly under the store name on the buyer's page</p>
    </div>

    <div class="wf-row">
      <svg width="44" height="44" viewBox="0 0 160 160" xmlns="http://www.w3.org/2000/svg" style="flex:0 0 auto;border-radius:10px" role="img" aria-label="Share Your Apps Server app icon">
        <rect x="20" y="8" width="120" height="144" rx="20" fill="#233490"/>
        <circle cx="80" cy="23" r="2.6" fill="#141f66"/>
        <rect x="34" y="34" width="92" height="90" rx="8" fill="#2c3fae"/>
        <g transform="translate(48,57.5)" stroke="#00be00" stroke-width="4.2" fill="none" stroke-linecap="round" stroke-linejoin="round">
          <path d="M0 2 H8 L14 28 H50 L58 8 H16"/>
          <g transform="translate(20,36)">
            <circle r="5" fill="#2c3fae" stroke="#00be00" stroke-width="1.4"/>
            <g stroke="#00be00" stroke-width="1.4"><line x1="-3.6" y1="0" x2="3.6" y2="0"/><line x1="0" y1="-3.6" x2="0" y2="3.6"/></g>
          </g>
          <g transform="translate(44,36)">
            <circle r="5" fill="#2c3fae" stroke="#00be00" stroke-width="1.4"/>
            <g stroke="#00be00" stroke-width="1.4"><line x1="-3.6" y1="0" x2="3.6" y2="0"/><line x1="0" y1="-3.6" x2="0" y2="3.6"/></g>
          </g>
        </g>
      </svg>
      <div style="flex:1">
        <div class="wf-label">Header image</div>
        <div class="wf-hint">share-your-apps-server-icon.svg (default)</div>
      </div>
      <a href="#" class="wf-btn wf-btn-outline" style="min-height:36px;padding:8px 12px;font-size:13px">Change</a>
    </div>

    <div class="wf-field">
      <div class="wf-row">
        <div style="display:flex;align-items:baseline;gap:6px">
          <label class="wf-label">PIN (optional)</label>
          <span class="wf-hint">up to 6 digits</span>
        </div>
        <input class="wf-input" type="text" inputmode="numeric" pattern="[0-9]{1,6}" maxlength="6" placeholder="No minimum length" value="048291" style="max-width:110px;text-align:right">
      </div>
      <p class="wf-hint">Buyers enter this before reaching the store. Sent in cleartext — no HTTPS.</p>
    </div>

    <div class="wf-divider"></div>

    <div class="wf-panel-raised">
      <div class="wf-row" style="margin-bottom:10px">
        <p class="wf-section-label" style="margin:0">Products (3)</p>
        <button class="wf-btn wf-btn-outline" style="min-height:36px;padding:8px 12px;font-size:13px">+ Add product</button>
      </div>

      <div style="display:flex;flex-direction:column;gap:10px">
                <div class="wf-card">
          <svg width="48" height="48" viewBox="0 0 100 100" style="flex:0 0 auto;border-radius:8px;border:1px solid var(--border-strong)" role="img" aria-label="Vitruvian figure">
<rect width="100" height="100" fill="#f3ead7"/>
<circle cx="50" cy="48" r="36" fill="none" stroke="#a89273" stroke-width="2"/>
<rect x="16" y="30" width="68" height="68" fill="none" stroke="#a89273" stroke-width="2"/>
<g stroke="#5a4632" stroke-width="3.2" fill="none" stroke-linecap="round">
<line x1="50" y1="30" x2="50" y2="62"/>
<line x1="50" y1="36" x2="17" y2="36"/><line x1="50" y1="36" x2="83" y2="36"/>
<line x1="50" y1="36" x2="27" y2="21"/><line x1="50" y1="36" x2="73" y2="21"/>
<line x1="50" y1="62" x2="42" y2="96"/><line x1="50" y1="62" x2="58" y2="96"/>
<line x1="50" y1="62" x2="28" y2="76"/><line x1="50" y1="62" x2="72" y2="76"/>
</g>
<circle cx="50" cy="23" r="6.5" fill="#5a4632"/>
</svg>
          <div style="flex:1;display:flex;flex-direction:column;gap:6px">
            <div class="wf-row">
              <span style="font-weight:700;font-size:14px">My Todo App</span>
              <button class="wf-btn wf-btn-icon wf-btn-outline" aria-label="Remove product"><svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg></button>
            </div>
            <div style="display:flex;gap:6px">
              <span class="wf-badge">Source &ndash;</span>
              <span class="wf-badge wf-badge-on">APK &check; 4.2 MB</span>
            </div>
            <textarea class="wf-textarea" rows="2" style="font-size:12px;min-height:44px" placeholder="Product description (HTML, optional)">A simple, offline-first to-do list. No account needed.</textarea>
          </div>
        </div>

        <div class="wf-card">
          <svg width="48" height="48" viewBox="0 0 100 100" style="flex:0 0 auto;border-radius:8px;border:1px solid var(--border-strong)" role="img" aria-label="Cloud with raindrops and a question mark">
<rect width="100" height="100" fill="#e6eef7"/>
<g fill="#7d8ea8">
<circle cx="28" cy="42" r="12"/><circle cx="42" cy="33" r="15"/><circle cx="55" cy="43" r="11"/>
<rect x="28" y="42" width="27" height="12" rx="6"/>
</g>
<g fill="#4a90d9">
<path d="M0 0 c4 6 6 9 6 12 a6 6 0 0 1 -12 0 c0 -3 2 -6 6 -12 Z" transform="translate(30,60)"><animateTransform attributeName="transform" type="translate" additive="sum" values="0 0;0 18" dur="0.9s" repeatCount="indefinite" begin="0s"/><animate attributeName="opacity" values="1;1;0" keyTimes="0;0.55;1" dur="0.9s" repeatCount="indefinite" begin="0s"/></path>
<path d="M0 0 c4 6 6 9 6 12 a6 6 0 0 1 -12 0 c0 -3 2 -6 6 -12 Z" transform="translate(42,67)"><animateTransform attributeName="transform" type="translate" additive="sum" values="0 0;0 18" dur="0.9s" repeatCount="indefinite" begin="0.3s"/><animate attributeName="opacity" values="1;1;0" keyTimes="0;0.55;1" dur="0.9s" repeatCount="indefinite" begin="0.3s"/></path>
<path d="M0 0 c4 6 6 9 6 12 a6 6 0 0 1 -12 0 c0 -3 2 -6 6 -12 Z" transform="translate(53,60)"><animateTransform attributeName="transform" type="translate" additive="sum" values="0 0;0 18" dur="0.9s" repeatCount="indefinite" begin="0.6s"/><animate attributeName="opacity" values="1;1;0" keyTimes="0;0.55;1" dur="0.9s" repeatCount="indefinite" begin="0.6s"/></path>
</g>
<text x="82" y="60" font-family="-apple-system,Segoe UI,Roboto,sans-serif" font-size="44" font-weight="700" fill="#485d92" text-anchor="middle">?</text>
</svg>
          <div style="flex:1;display:flex;flex-direction:column;gap:6px">
            <div class="wf-row">
              <span style="font-weight:700;font-size:14px">Weather Widget</span>
              <button class="wf-btn wf-btn-icon wf-btn-outline" aria-label="Remove product"><svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg></button>
            </div>
            <div style="display:flex;gap:6px">
              <span class="wf-badge wf-badge-on">Source &check;</span>
              <span class="wf-badge wf-badge-on">APK &check; 1.8 MB</span>
            </div>
            <textarea class="wf-textarea" rows="2" style="font-size:12px;min-height:44px" placeholder="Product description (HTML, optional)"></textarea>
          </div>
        </div>

        <div class="wf-card">
          <svg width="48" height="48" viewBox="0 0 100 100" style="flex:0 0 auto;border-radius:8px;border:1px solid var(--border-strong)" role="img" aria-label="Penguin riding a bicycle">
<rect width="100" height="100" fill="#eaf3ee"/>
<g fill="none" stroke="#485d92" stroke-width="3" stroke-linecap="round">
<circle cx="26" cy="78" r="14"/><circle cx="74" cy="78" r="14"/>
<line x1="50" y1="78" x2="26" y2="78"/><line x1="44" y1="56" x2="26" y2="78"/>
<line x1="50" y1="78" x2="44" y2="56"/><line x1="44" y1="56" x2="68" y2="56"/>
<line x1="50" y1="78" x2="69" y2="57"/><line x1="70" y1="54" x2="74" y2="78"/>
<line x1="70" y1="54" x2="78" y2="49"/>
</g>
<ellipse cx="43" cy="54" rx="6.5" ry="2.4" fill="#485d92"/>
<path d="M46 54 L53 69" stroke="#20242b" stroke-width="4.5" stroke-linecap="round" fill="none"/>
<ellipse cx="44" cy="42" rx="10.5" ry="13.5" fill="#20242b"/>
<ellipse cx="45.5" cy="44.5" rx="6.5" ry="9" fill="#ffffff"/>
<path d="M48 40 Q60 44 70 51" stroke="#20242b" stroke-width="5" stroke-linecap="round" fill="none"/>
<circle cx="50" cy="24" r="8.5" fill="#20242b"/>
<circle cx="52.5" cy="26" r="5" fill="#ffffff"/>
<circle cx="53" cy="23" r="1.7" fill="#20242b"/>
<path d="M58 23.5 L65.5 26.5 L58 29.5 Z" fill="#ff9800"/>
<ellipse cx="54" cy="71" rx="4.2" ry="2.3" fill="#ff9800"/>
</svg>
          <div style="flex:1;display:flex;flex-direction:column;gap:6px">
            <div class="wf-row">
              <span style="font-weight:700;font-size:14px">Recipe Box (draft)</span>
              <button class="wf-btn wf-btn-icon wf-btn-outline" aria-label="Remove product"><svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg></button>
            </div>
            <div style="display:flex;gap:6px">
              <span class="wf-badge">Source &ndash;</span>
              <span class="wf-badge">APK &ndash;</span>
            </div>
            <textarea class="wf-textarea" rows="2" style="font-size:12px;min-height:44px" placeholder="Product description (HTML, optional)"></textarea>
          </div>
        </div>
      </div>
    </div>

    <div class="wf-divider"></div>

    <div class="wf-field">
      <div class="wf-row">
        <label class="wf-label">Template override (Pebble, optional)</label>
        <button class="wf-btn wf-btn-icon wf-btn-outline" aria-label="Copy default template into the field below">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>
        </button>
      </div>
      <textarea class="wf-textarea" rows="4" style="min-height:76px">&lt;!-- leave empty to use the default template --&gt;</textarea>
      <p class="wf-hint">Currently using the default template. Tap the copy icon to load its source here as a starting point, then edit it — this field replaces the default when non-empty.</p>
    </div>

    <div class="wf-field">
      <label class="wf-label">Extra JSON (optional)</label>
      <textarea class="wf-textarea" rows="2">{ }</textarea>
      <p class="wf-hint">Passed into the Pebble context alongside products</p>
    </div>

    </div>
  </div>

  <sc-if value="{{publishing}}" hint-placeholder-val="{{true}}">
    <div class="wf-publishing-status">
      <span class="wf-dot wf-dot-busy"></span>
      <span>Publishing…</span>
    </div>
  </sc-if>
  <div class="wf-row" style="padding:14px 20px;border-top:1px solid var(--border);background:var(--surface)">
    <button class="wf-btn wf-btn-primary {{publishBtnClass}}" style="flex:2" onClick="{{simulatePublish}}">Create / Install / Launch Store</button>
    <button class="wf-btn wf-btn-outline {{cancelBtnClass}}" style="flex:1">Cancel</button>
  </div>
</div>
</x-dc>
<script type="text/x-dc" data-dc-script data-props='{"$preview":{"width":390,"height":844}}'>
class Component extends DCLogic {
  renderVals() {
    const publishing = !!(this.state && this.state.publishing);
    const simulatePublish = () => {
      if (publishing) return;
      this.setState({ publishing: true });
      setTimeout(() => this.setState({ publishing: false }), 2400);
    };
    return {
      publishing,
      simulatePublish,
      publishBtnClass: publishing ? 'wf-btn-disabled' : '',
      cancelBtnClass: publishing ? 'wf-btn-disabled' : '',
    };
  }
}
</script>
</body>
</html>
```

## B.3 `Icon.dc.html` — Share Your Apps Server app icon

Real color (App Dev for All blue/green), animated cart-wheel rotation via SMIL — the one artboard here that isn't a gray-box wireframe, since it's a finished asset rather than a layout sketch.

```html
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Share Your Apps Server — app icon</title>
<script src="./support.js"></script>
</head>
<body>
<x-dc>
<helmet>
<style>
body{margin:0;background:transparent}
</style>
</helmet>
<div style="width: 160px; height: 160px; display: flex; align-items: center; justify-content: center; background: transparent;">
<svg viewBox="0 0 160 160" width="160" height="160" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="Share Your Apps Server app icon">
  <rect x="20" y="8" width="120" height="144" rx="20" fill="#233490"/>
  <circle cx="80" cy="23" r="2.6" fill="#141f66"/>
  <rect x="34" y="34" width="92" height="90" rx="8" fill="#2c3fae"/>
  <g transform="translate(48,52)" stroke="#00be00" stroke-width="4.2" fill="none" stroke-linecap="round" stroke-linejoin="round">
    <path d="M0 2 H8 L14 28 H50 L58 8 H16"/>
    <g transform="translate(20,36)">
      <circle r="4.4" fill="#2c3fae"/>
      <line x1="0" y1="0" x2="0" y2="-4.4"/>
      <animateTransform attributeName="transform" type="rotate" from="0 0 0" to="360 0 0" dur="1.8s" repeatCount="indefinite" additive="sum"/>
    </g>
    <g transform="translate(44,36)">
      <circle r="4.4" fill="#2c3fae"/>
      <line x1="0" y1="0" x2="0" y2="-4.4"/>
      <animateTransform attributeName="transform" type="rotate" from="0 0 0" to="360 0 0" dur="1.8s" repeatCount="indefinite" additive="sum"/>
    </g>
  </g>
  <g transform="translate(0,132)" stroke="#7f8fd9" stroke-width="2.4" fill="none" stroke-linecap="round" stroke-linejoin="round">
    <path d="M68 6 l-5 4 l5 4"/>
    <rect x="77" y="5" width="7" height="7" rx="1.4"/>
    <path d="M95 5 v7 M91.5 8.5 h7"/>
  </g>
</svg>
</div>
</x-dc>
<script type="text/x-dc" data-dc-script data-props='{"$preview":{"width":160,"height":160}}'>
class Component extends DCLogic {
  renderVals() {
    return {};
  }
}
</script>
</body>
</html>
```

## B.4 `ManageServer.dc.html` — Share Your Apps Server app — Manage Server

The most-iterated screen; it shows the interaction decisions settled while designing it (busy/idle dot, running/stopped card color, tap-to-copy credentials, vertical Wi-Fi toggle, inline Port/Run-mode/log-size fields, the inset Combined-log panel). What was built differs; see the list at the top of this appendix.

```html
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Share Your Apps Server — Manage Server</title>
<script src="./support.js"></script>
<link rel="stylesheet" href="./wireframe.css">
</head>
<body>
<x-dc>
<helmet>
<style>
body{margin:0}
</style>
</helmet>
<div class="wf-screen" style="width: 390px; height: 844px;">
  <div class="wf-header" style="padding:8px 20px">
    <h1 class="wf-title" style="font-size:17px">Manage Server</h1>
  </div>
  <div class="wf-body" style="gap:0">
    <div style="display:flex;flex-direction:column;gap:10px">

    <div class="wf-card {{statusCardClass}}" style="align-items:center">
      <div style="flex:1">
        <span class="{{statusTitleClass}}" style="font-weight:700;font-size:15px">{{statusTitle}}</span>
        <sc-if value="{{running}}" hint-placeholder-val="{{true}}">
          <button class="wf-link-btn" onClick="{{simulateRequest}}" style="margin:0 0 0 6px;vertical-align:baseline">s</button>
        </sc-if>
        <sc-if value="{{running}}" hint-placeholder-val="{{true}}">
          <div class="wf-row" style="justify-content:flex-start;gap:6px;margin-top:4px">
            <span class="{{dotClass}}"></span>
            <span class="wf-hint">{{statusHint}}</span>
          </div>
        </sc-if>
      </div>
      <button class="wf-btn wf-btn-outline" onClick="{{toggleRunning}}" style="min-height:40px;padding:8px 14px">{{statusButtonLabel}}</button>
    </div>

      <div class="wf-row">
        <div style="display:flex;align-items:baseline;gap:6px">
          <label class="wf-label">Port</label>
          <span class="wf-hint">1024–65535</span>
        </div>
        <input class="wf-input" type="number" min="1024" max="65535" style="max-width:76px;text-align:right;padding:6px 10px" value="6175">
      </div>

      <div class="wf-row" style="align-items:center">
        <label class="wf-label">Run mode</label>
        <div style="display:flex;border:1px solid var(--border-strong);border-radius:8px;overflow:hidden;flex:1;max-width:260px;margin-left:12px">
          <div style="flex:1;text-align:center;padding:6px 4px;font-size:11px;font-weight:600;white-space:nowrap;background:var(--accent);color:#fff">Run always</div>
          <div style="flex:1;text-align:center;padding:6px 4px;font-size:11px;font-weight:600;white-space:nowrap;color:var(--muted)">On-screen only</div>
        </div>
      </div>

      <div class="wf-card wf-card-raised" style="align-items:center;gap:20px">
        <div class="wf-credential-grid" style="flex:1;min-width:0">
          <span class="wf-credential-label">WiFi SSID</span>
          <button class="wf-credential-value-btn {{ssidCopiedClass}}" onClick="{{copySsid}}">{{ssidDisplay}}</button>
          <span class="wf-credential-label">Password</span>
          <button class="wf-credential-value-btn {{passwordCopiedClass}}" onClick="{{copyPassword}}">{{passwordDisplay}}</button>
        </div>
        <div class="wf-toggle-v {{toggleClass}}" onClick="{{toggleHotspot}}" aria-label="Toggle Wi-Fi hotspot" role="button">
          <div class="wf-toggle-v-knob"></div>
        </div>
      </div>

    <div class="wf-divider"></div>

      <div class="wf-row">
        <button class="wf-btn wf-btn-outline" style="flex:1">View logs</button>
        <button class="wf-btn wf-btn-outline" style="flex:1">Export logs</button>
      </div>
      <div class="wf-row">
        <label class="wf-label">Max log size (MB, 0 = off)</label>
        <input class="wf-input" type="number" min="0" max="100" style="max-width:64px;text-align:right;padding:6px 10px" value="10">
      </div>

    </div>
  </div>

  <div class="wf-log-panel" style="height:194px">
    <div class="wf-log-header">
      <span class="wf-log-title">Combined log</span>
      <button class="wf-expand-btn" aria-label="Full screen">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="#cfd6e3" stroke-width="2.3" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 3 21 3 21 9"></polyline><polyline points="9 21 3 21 3 15"></polyline><line x1="21" y1="3" x2="14" y2="10"></line><line x1="3" y1="21" x2="10" y2="14"></line></svg>
      </button>
    </div>
    <div class="wf-log-body">
      <div class="wf-log-line">192.168.1.42 - - [21/Sep:14:02:11] "<b>GET</b> /f/12" 200 41230 812B/s host=pixel-6a</div>
      <div class="wf-log-line">192.168.1.55 - - [21/Sep:14:02:09] "<b>GET</b> /index.html" 200 8120 2.1kB/s host=-</div>
      <div class="wf-log-line wf-log-line-err">[ERR] 14:02:05 write failed: broken pipe (192.168.1.55)</div>
      <div class="wf-log-line">192.168.1.42 - - [21/Sep:14:01:58] "<b>POST</b> /pin" 200 12 118B/s host=pixel-6a</div>
      <div class="wf-log-line">192.168.1.61 - - [21/Sep:14:01:44] "<b>GET</b> /manifest.json" 200 940 2.0kB/s host=-</div>
      <div class="wf-log-line">192.168.1.70 - - [21/Sep:14:01:22] "<b>GET</b> /f/8" 200 18210 640B/s host=-</div>
      <div class="wf-scrollbar-v"></div>
      <div class="wf-scrollbar-h"></div>
    </div>
  </div>
</div>
</x-dc>
<script type="text/x-dc" data-dc-script data-props='{"$preview":{"width":390,"height":844}}'>
class Component extends DCLogic {
  renderVals() {
    const ssid = 'ShareYourApps-4F2A';
    const password = '8k2m-plaz';

    const ssidCopied = !!(this.state && this.state.ssidCopied);
    const passwordCopied = !!(this.state && this.state.passwordCopied);

    const copySsid = () => {
      try { navigator.clipboard.writeText(ssid); } catch (e) {}
      this.setState({ ssidCopied: true });
      setTimeout(() => this.setState({ ssidCopied: false }), 900);
    };
    const copyPassword = () => {
      try { navigator.clipboard.writeText(password); } catch (e) {}
      this.setState({ passwordCopied: true });
      setTimeout(() => this.setState({ passwordCopied: false }), 900);
    };

    const hotspotOn = this.state && this.state.hotspotOn !== undefined ? this.state.hotspotOn : true;
    const toggleClass = hotspotOn ? 'wf-toggle-v-on' : '';
    const toggleHotspot = () => this.setState({ hotspotOn: !hotspotOn });

    const running = this.state && this.state.running !== undefined ? this.state.running : true;
    const busy = !!(this.state && this.state.busy);
    const toggleRunning = () => this.setState({ running: !running, busy: false });
    const simulateRequest = () => {
      this.setState({ busy: true });
      setTimeout(() => this.setState({ busy: false }), 1200);
    };
    const statusCardClass = running ? '' : 'wf-card-stopped';
    const statusTitleClass = running ? '' : 'wf-status-stopped-text';
    const statusTitle = running ? 'Running' : 'Stopped';
    const dotClass = busy ? 'wf-dot wf-dot-busy' : 'wf-dot';
    const statusHint = busy ? 'Responding' : 'idle (waiting)';
    const statusButtonLabel = running ? 'Stop' : 'Start';

    return {
      ssidDisplay: ssidCopied ? 'Copied!' : ssid,
      passwordDisplay: passwordCopied ? 'Copied!' : password,
      ssidCopiedClass: ssidCopied ? 'copied' : '',
      passwordCopiedClass: passwordCopied ? 'copied' : '',
      copySsid,
      copyPassword,
      toggleClass,
      toggleHotspot,
      running,
      toggleRunning,
      simulateRequest,
      statusCardClass,
      statusTitleClass,
      statusTitle,
      dotClass,
      statusHint,
      statusButtonLabel,
    };
  }
}
</script>
</body>
</html>
```

## B.5 `ServeScreen.dc.html` — Share Your Apps Server app — Serve (customer-facing, full screen)

Two-step QR flow: join Wi-Fi, then browse the store.

**Read this before implementing from the markup below.** The markup shows Step 1's QR, the SSID, and the passphrase unconditionally, all as copyable text — accurate for **hotspot mode**, where the app generates the network and always knows its own passphrase. **LAN mode is different** (see *Serve screen* in *Server*): the passphrase isn't readable from a merely-joined network, so it comes from an optional, session-only field the owner types in — and **if it's blank, Step 1 is hidden entirely**, replaced by "Join [SSID] first if you're not already on it" as plain text with no QR and no passphrase line. The markup below has no representation of that hidden state; the design's *Serve screen* describes it. Everything else in this wireframe — the two-step structure, layout, copy affordances, Step 2 — is unaffected and still authoritative for both modes.

```html
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Share Your Apps Server — Serve</title>
<script src="./support.js"></script>
<link rel="stylesheet" href="./wireframe.css">
</head>
<body>
<x-dc>
<helmet>
<style>
body{margin:0}
</style>
</helmet>
<div class="wf-screen" style="width: 390px; height: 844px; align-items: center; text-align: center; position: relative;">
  <div style="width:100%;box-sizing:border-box;padding:20px 20px 12px;display:flex;flex-direction:column;gap:6px;text-align:left">
    <div style="display:flex;align-items:center;gap:12px">
      <svg width="40" height="40" viewBox="0 0 160 160" xmlns="http://www.w3.org/2000/svg" style="flex:0 0 auto" role="img" aria-label="Share Your Apps Server app icon">
        <rect x="20" y="8" width="120" height="144" rx="20" fill="#233490"/>
        <circle cx="80" cy="23" r="2.6" fill="#141f66"/>
        <rect x="34" y="34" width="92" height="90" rx="8" fill="#2c3fae"/>
        <g transform="translate(48,57.5)" stroke="#00be00" stroke-width="4.2" fill="none" stroke-linecap="round" stroke-linejoin="round">
          <path d="M0 2 H8 L14 28 H50 L58 8 H16"/>
          <g transform="translate(20,36)">
            <circle r="5" fill="#2c3fae" stroke="#00be00" stroke-width="1.4"/>
            <g stroke="#00be00" stroke-width="1.4"><line x1="-3.6" y1="0" x2="3.6" y2="0"/><line x1="0" y1="-3.6" x2="0" y2="3.6"/></g>
          </g>
          <g transform="translate(44,36)">
            <circle r="5" fill="#2c3fae" stroke="#00be00" stroke-width="1.4"/>
            <g stroke="#00be00" stroke-width="1.4"><line x1="-3.6" y1="0" x2="3.6" y2="0"/><line x1="0" y1="-3.6" x2="0" y2="3.6"/></g>
          </g>
        </g>
      </svg>
      <div style="font-weight:700;font-size:17px">My Share Your Apps</div>
    </div>
    <div style="position:relative;padding-right:28px">
      <div class="wf-desc-box">
        <p>{{storeDescriptionHtml}}</p>
        <div class="wf-desc-scrollbar"></div>
      </div>
      <button class="wf-desc-expand-btn" onClick="{{toggleDescExpand}}" aria-label="Full screen">
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 3 21 3 21 9"></polyline><polyline points="9 21 3 21 3 15"></polyline><line x1="21" y1="3" x2="14" y2="10"></line><line x1="3" y1="21" x2="10" y2="14"></line></svg>
      </button>
    </div>
    <div class="wf-hint">Scan to join Wi-Fi, then scan to browse — skip the first scan if you're already connected</div>
  </div>

  <sc-if value="{{descExpanded}}" hint-placeholder-val="{{false}}">
    <div class="wf-desc-overlay">
      <div class="wf-row" style="flex:0 0 auto">
        <span style="color:#fff;font-weight:700">About this store</span>
        <button class="wf-desc-zoom-btn" onClick="{{toggleDescExpand}}" aria-label="Close">✕</button>
      </div>
      <div class="wf-desc-overlay-body" style="font-size:{{descFontSize}}px">{{storeDescriptionHtml}}</div>
      <div class="wf-desc-zoom-row">
        <button class="wf-desc-zoom-btn" onClick="{{zoomOut}}" aria-label="Smaller text">–</button>
        <button class="wf-desc-zoom-btn" onClick="{{zoomIn}}" aria-label="Larger text">+</button>
      </div>
    </div>
  </sc-if>

  <div class="wf-body" style="align-items:center;gap:16px">

    <div style="display:flex;flex-direction:column;align-items:center;gap:10px">
      <p class="wf-section-label" style="margin:0">Step 1 — Join Wi-Fi</p>
      <div class="wf-qr">
        <svg viewBox="0 0 90 90" width="120" height="120" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="QR code to join Wi-Fi">
          <rect width="90" height="90" fill="#fff"/>
          <rect x="4" y="4" width="24" height="24" fill="#20242b"/><rect x="10" y="10" width="12" height="12" fill="#fff"/><rect x="13" y="13" width="6" height="6" fill="#20242b"/>
          <rect x="62" y="4" width="24" height="24" fill="#20242b"/><rect x="68" y="10" width="12" height="12" fill="#fff"/><rect x="71" y="13" width="6" height="6" fill="#20242b"/>
          <rect x="4" y="62" width="24" height="24" fill="#20242b"/><rect x="10" y="68" width="12" height="12" fill="#fff"/><rect x="13" y="71" width="6" height="6" fill="#20242b"/>
          <rect x="36" y="8" width="6" height="6" fill="#20242b"/><rect x="46" y="8" width="6" height="6" fill="#20242b"/>
          <rect x="36" y="18" width="6" height="6" fill="#20242b"/><rect x="52" y="18" width="6" height="6" fill="#20242b"/>
          <rect x="8" y="36" width="6" height="6" fill="#20242b"/><rect x="18" y="40" width="6" height="6" fill="#20242b"/>
          <rect x="34" y="36" width="6" height="6" fill="#20242b"/><rect x="44" y="42" width="6" height="6" fill="#20242b"/>
          <rect x="54" y="36" width="6" height="6" fill="#20242b"/><rect x="64" y="40" width="6" height="6" fill="#20242b"/>
          <rect x="74" y="36" width="6" height="6" fill="#20242b"/><rect x="34" y="50" width="6" height="6" fill="#20242b"/>
          <rect x="44" y="54" width="6" height="6" fill="#20242b"/><rect x="54" y="50" width="6" height="6" fill="#20242b"/>
          <rect x="64" y="56" width="6" height="6" fill="#20242b"/><rect x="36" y="64" width="6" height="6" fill="#20242b"/>
          <rect x="46" y="70" width="6" height="6" fill="#20242b"/><rect x="56" y="64" width="6" height="6" fill="#20242b"/>
          <rect x="66" y="72" width="6" height="6" fill="#20242b"/><rect x="76" y="64" width="6" height="6" fill="#20242b"/>
        </svg>
      </div>
      <div style="display:flex;align-items:center;gap:6px;font-size:13px;font-weight:600">
        <span>SSID: {{ssid}}</span>
        <button class="wf-copy-btn {{ssidCopiedClass}}" onClick="{{copySsid}}" aria-label="Copy SSID">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>
        </button>
      </div>
      <div style="display:flex;align-items:center;gap:6px" class="wf-hint">
        <span>Passphrase: {{passphrase}}</span>
        <button class="wf-copy-btn {{passphraseCopiedClass}}" onClick="{{copyPassphrase}}" aria-label="Copy passphrase">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>
        </button>
      </div>
    </div>

    <div class="wf-divider" style="width:100%"></div>

    <div style="display:flex;flex-direction:column;align-items:center;gap:10px">
      <p class="wf-section-label" style="margin:0">Step 2 — Browse the store</p>
      <div class="wf-qr">
        <svg viewBox="0 0 90 90" width="120" height="120" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="QR code to open the store">
          <rect width="90" height="90" fill="#fff"/>
          <rect x="4" y="4" width="24" height="24" fill="#20242b"/><rect x="10" y="10" width="12" height="12" fill="#fff"/><rect x="13" y="13" width="6" height="6" fill="#20242b"/>
          <rect x="62" y="4" width="24" height="24" fill="#20242b"/><rect x="68" y="10" width="12" height="12" fill="#fff"/><rect x="71" y="13" width="6" height="6" fill="#20242b"/>
          <rect x="4" y="62" width="24" height="24" fill="#20242b"/><rect x="10" y="68" width="12" height="12" fill="#fff"/><rect x="13" y="71" width="6" height="6" fill="#20242b"/>
          <rect x="38" y="10" width="6" height="6" fill="#20242b"/><rect x="50" y="14" width="6" height="6" fill="#20242b"/>
          <rect x="40" y="22" width="6" height="6" fill="#20242b"/><rect x="56" y="24" width="6" height="6" fill="#20242b"/>
          <rect x="10" y="40" width="6" height="6" fill="#20242b"/><rect x="22" y="34" width="6" height="6" fill="#20242b"/>
          <rect x="38" y="38" width="6" height="6" fill="#20242b"/><rect x="50" y="46" width="6" height="6" fill="#20242b"/>
          <rect x="62" y="38" width="6" height="6" fill="#20242b"/><rect x="72" y="46" width="6" height="6" fill="#20242b"/>
          <rect x="38" y="54" width="6" height="6" fill="#20242b"/><rect x="50" y="58" width="6" height="6" fill="#20242b"/>
          <rect x="62" y="52" width="6" height="6" fill="#20242b"/><rect x="40" y="68" width="6" height="6" fill="#20242b"/>
          <rect x="52" y="74" width="6" height="6" fill="#20242b"/><rect x="64" y="68" width="6" height="6" fill="#20242b"/>
          <rect x="74" y="76" width="6" height="6" fill="#20242b"/>
        </svg>
      </div>
      <sc-if value="{{hasPin}}" hint-placeholder-val="{{true}}">
        <div style="font-size:13px;font-weight:600">Enter PIN on that page</div>
      </sc-if>
      <div style="display:flex;align-items:center;gap:6px" class="wf-hint">
        <span>{{url}}</span>
        <button class="wf-copy-btn {{urlCopiedClass}}" onClick="{{copyUrl}}" aria-label="Copy URL">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>
        </button>
      </div>
    </div>

  </div>
</div>
</x-dc>
<script type="text/x-dc" data-dc-script data-props='{"$preview":{"width":390,"height":844}}'>
class Component extends DCLogic {
  renderVals() {
    const ssid = 'ShareYourApps-4F2A';
    const passphrase = '8k2m-plaz';
    const url = 'http://192.168.1.42:6175';
    const storeDescriptionHtml = "Thanks for stopping by! Everything here is free, no account needed, no ads, nothing tracked. If something doesn't work, come find me and I'll fix it on the spot — I'm usually right around here on market days.";

    const ssidCopied = !!(this.state && this.state.ssidCopied);
    const passphraseCopied = !!(this.state && this.state.passphraseCopied);
    const urlCopied = !!(this.state && this.state.urlCopied);

    const copySsid = () => {
      try { navigator.clipboard.writeText(ssid); } catch (e) {}
      this.setState({ ssidCopied: true });
      setTimeout(() => this.setState({ ssidCopied: false }), 900);
    };
    const copyPassphrase = () => {
      try { navigator.clipboard.writeText(passphrase); } catch (e) {}
      this.setState({ passphraseCopied: true });
      setTimeout(() => this.setState({ passphraseCopied: false }), 900);
    };
    const copyUrl = () => {
      try { navigator.clipboard.writeText(url); } catch (e) {}
      this.setState({ urlCopied: true });
      setTimeout(() => this.setState({ urlCopied: false }), 900);
    };

    const descExpanded = !!(this.state && this.state.descExpanded);
    const descFontSize = (this.state && this.state.descFontSize) || 15;
    const toggleDescExpand = () => this.setState({ descExpanded: !descExpanded });
    const zoomIn = () => this.setState({ descFontSize: Math.min(descFontSize + 2, 28) });
    const zoomOut = () => this.setState({ descFontSize: Math.max(descFontSize - 2, 12) });

    return {
      ssid,
      passphrase,
      url,
      copySsid,
      copyPassphrase,
      copyUrl,
      ssidCopiedClass: ssidCopied ? 'copied' : '',
      passphraseCopiedClass: passphraseCopied ? 'copied' : '',
      urlCopiedClass: urlCopied ? 'copied' : '',
      hasPin: true,
      storeDescriptionHtml,
      descExpanded,
      descFontSize,
      toggleDescExpand,
      zoomIn,
      zoomOut,
    };
  }
}
</script>
</body>
</html>
```

## B.6 `FullScreenLogView.dc.html` — Share Your Apps Server app — full-screen combined log view

Reached from Manage Server's inset panel's expand button; its file chips, search and filters are described in the design's *Full log view*.

```html
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Share Your Apps Server — Full-screen log</title>
<script src="./support.js"></script>
<link rel="stylesheet" href="./wireframe.css">
</head>
<body>
<x-dc>
<helmet>
<style>
body{margin:0}
</style>
</helmet>
<div class="wf-screen" style="width: 390px; height: 844px; background:#14161b;">

  <div class="wf-log-toolbar">
    <button class="wf-icon-btn-dark" aria-label="Back">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#cfd6e3" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"></polyline></svg>
    </button>
    <div style="flex:1">
      <div style="color:#fff;font-weight:700;font-size:15px">Combined log</div>
      <div style="color:#7f8aa0;font-size:11px">1,204 lines · 3.2 MB</div>
    </div>
  </div>

  <div class="wf-filter-row" style="overflow-x:auto">
    <span class="wf-chip wf-chip-on">Current</span>
    <span class="wf-chip">2h ago</span>
    <span class="wf-chip">6h ago</span>
    <span class="wf-chip">1d ago</span>
    <span class="wf-chip">2d ago</span>
  </div>

  <div class="wf-log-toolbar" style="padding-top:0">
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="#7f8aa0" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" style="flex:0 0 auto"><circle cx="11" cy="11" r="7"></circle><line x1="21" y1="21" x2="16.65" y2="16.65"></line></svg>
    <input class="wf-search-input-dark" placeholder="Search log — text or regex">
    <button class="wf-icon-btn-dark" style="width:28px;height:28px" aria-label="Clear search">✕</button>
  </div>

  <div class="wf-filter-row">
    <button class="wf-icon-btn-dark" style="width:28px;height:28px" aria-label="Search">
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#cfd6e3" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="7"></circle><line x1="21" y1="21" x2="16.65" y2="16.65"></line></svg>
    </button>
    <button class="wf-icon-btn-dark" style="width:28px;height:28px" aria-label="Filter">
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#cfd6e3" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><polygon points="4 4 20 4 14 12.5 14 19 10 21 10 12.5 4 4"></polygon></svg>
    </button>
    <span class="wf-chip wf-chip-on">All</span>
    <span class="wf-chip">Access</span>
    <span class="wf-chip">Errors</span>
  </div>

  <div class="wf-log-body" style="flex:1">
    <div class="wf-log-line">192.168.1.42 - - [21/Sep:14:02:11] "<b>GET</b> /f/12" 200 41230 812B/s host=pixel-6a</div>
    <div class="wf-log-line">192.168.1.55 - - [21/Sep:14:02:09] "<b>GET</b> /index.html" 200 8120 2.1kB/s host=-</div>
    <div class="wf-log-line wf-log-line-err">[ERR] 14:02:05 write failed: broken pipe (192.168.1.55)</div>
    <div class="wf-log-line">192.168.1.42 - - [21/Sep:14:01:58] "<b>POST</b> /pin" 200 12 118B/s host=pixel-6a</div>
    <div class="wf-log-line">192.168.1.61 - - [21/Sep:14:01:44] "<b>GET</b> /manifest.json" 200 940 2.0kB/s host=-</div>
    <div class="wf-log-line">192.168.1.61 - - [21/Sep:14:01:44] "<b>GET</b> /products/weather/icon.png" 200 3021 host=-</div>
    <div class="wf-log-line wf-log-line-err">[ERR] 14:01:40 accept() timed out, retrying</div>
    <div class="wf-log-line">192.168.1.70 - - [21/Sep:14:01:22] "<b>GET</b> /f/8" 200 18210 640B/s host=-</div>
    <div class="wf-log-line">192.168.1.42 - - [21/Sep:14:00:59] "<b>GET</b> /index.html" 200 8120 1.4kB/s host=pixel-6a</div>
    <div class="wf-log-line">192.168.1.55 - - [21/Sep:14:00:41] "<b>GET</b> /f/12" 200 41230 900B/s host=-</div>
    <div class="wf-log-line">192.168.1.61 - - [21/Sep:14:00:30] "<b>POST</b> /pin" 401 0 host=-</div>
    <div class="wf-log-line wf-log-line-err">[ERR] 14:00:30 pin rejected for 192.168.1.61</div>
    <div class="wf-log-line">192.168.1.42 - - [21/Sep:14:00:12] "<b>GET</b> /manifest.json" 200 940 host=pixel-6a</div>
    <div class="wf-scrollbar-v"></div>
    <div class="wf-scrollbar-h"></div>
  </div>
  <div class="wf-log-hint" style="text-align:center">Pinch to zoom · swipe to pan (mirrors scrollbars)</div>

</div>
</x-dc>
<script type="text/x-dc" data-dc-script data-props='{"$preview":{"width":390,"height":844}}'>
class Component extends DCLogic {
  renderVals() {
    return {};
  }
}
</script>
</body>
</html>
```

## B.7 `BuyerCatalogPortrait.dc.html` — Buyer's browser — rendered catalog page, portrait phone

```html
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Buyer catalog — portrait phone</title>
<script src="./support.js"></script>
<link rel="stylesheet" href="./wireframe.css">
</head>
<body>
<x-dc>
<helmet>
<style>
body{margin:0}
</style>
</helmet>
<div class="wf-screen" style="width: 390px; height: 844px; position: relative;">
  <div class="wf-header" style="display:flex;flex-direction:column;align-items:flex-start;gap:6px;border-bottom:none;background:#233490;color:#fff">
    <div style="display:flex;align-items:center;gap:12px">
      <svg width="36" height="36" viewBox="0 0 160 160" xmlns="http://www.w3.org/2000/svg" style="flex:0 0 auto" role="img" aria-label="Share Your Apps Server app icon">
        <rect x="20" y="8" width="120" height="144" rx="20" fill="#233490"/>
        <circle cx="80" cy="23" r="2.6" fill="#141f66"/>
        <rect x="34" y="34" width="92" height="90" rx="8" fill="#2c3fae"/>
        <g transform="translate(48,57.5)" stroke="#00be00" stroke-width="4.2" fill="none" stroke-linecap="round" stroke-linejoin="round">
          <path d="M0 2 H8 L14 28 H50 L58 8 H16"/>
          <g transform="translate(20,36)">
            <circle r="5" fill="#2c3fae" stroke="#00be00" stroke-width="1.4"/>
            <g stroke="#00be00" stroke-width="1.4"><line x1="-3.6" y1="0" x2="3.6" y2="0"/><line x1="0" y1="-3.6" x2="0" y2="3.6"/></g>
          </g>
          <g transform="translate(44,36)">
            <circle r="5" fill="#2c3fae" stroke="#00be00" stroke-width="1.4"/>
            <g stroke="#00be00" stroke-width="1.4"><line x1="-3.6" y1="0" x2="3.6" y2="0"/><line x1="0" y1="-3.6" x2="0" y2="3.6"/></g>
          </g>
        </g>
      </svg>
      <h1 class="wf-title" style="color:#fff">My Share Your Apps</h1>
    </div>
    <div style="position:relative;padding-right:28px;width:100%">
      <div class="wf-desc-box wf-desc-box-dark">
        <p>{{storeDescriptionHtml}}</p>
        <div class="wf-desc-scrollbar wf-desc-scrollbar-dark"></div>
      </div>
      <button class="wf-desc-expand-btn wf-desc-expand-btn-dark" onClick="{{toggleDescExpand}}" aria-label="Full screen">
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 3 21 3 21 9"></polyline><polyline points="9 21 3 21 3 15"></polyline><line x1="21" y1="3" x2="14" y2="10"></line><line x1="3" y1="21" x2="10" y2="14"></line></svg>
      </button>
    </div>
  </div>
  <div class="wf-body" style="padding-top:16px">
          <div class="wf-card" style="flex-direction:column;padding:0;overflow:hidden">
        <div style="width:100%;height:150px;background:#f3ead7;display:flex;align-items:center;justify-content:center;border-bottom:1px solid var(--border)">
<svg width="96" height="96" viewBox="0 0 100 100" role="img" aria-label="Vitruvian figure">
<circle cx="50" cy="48" r="36" fill="none" stroke="#a89273" stroke-width="2"/>
<rect x="16" y="30" width="68" height="68" fill="none" stroke="#a89273" stroke-width="2"/>
<g stroke="#5a4632" stroke-width="3.2" fill="none" stroke-linecap="round">
<line x1="50" y1="30" x2="50" y2="62"/>
<line x1="50" y1="36" x2="17" y2="36"/><line x1="50" y1="36" x2="83" y2="36"/>
<line x1="50" y1="36" x2="27" y2="21"/><line x1="50" y1="36" x2="73" y2="21"/>
<line x1="50" y1="62" x2="42" y2="96"/><line x1="50" y1="62" x2="58" y2="96"/>
<line x1="50" y1="62" x2="28" y2="76"/><line x1="50" y1="62" x2="72" y2="76"/>
</g>
<circle cx="50" cy="23" r="6.5" fill="#5a4632"/>
</svg>
</div>
        <div style="padding:14px;display:flex;flex-direction:column;gap:10px;width:100%">
          <div class="wf-row">
            <span style="font-weight:700;font-size:15px">My Todo App</span>
            <span class="wf-hint">Sep 19, 2026</span>
          </div>
          <p class="wf-product-desc">A simple, offline-first to-do list. No account needed.</p>
          <div style="display:flex;gap:8px">
            <a href="#" class="wf-btn wf-btn-outline" style="flex:1;min-height:40px;padding:8px 12px;font-size:13px">Download source</a>
            <a href="#" class="wf-btn wf-btn-primary" style="flex:1;flex-direction:column;gap:1px;min-height:40px;padding:6px 12px">
              <span style="font-size:13px">Download APK</span>
              <span style="font-size:9px;font-weight:400;opacity:.85">4.2 MB</span>
            </a>
          </div>
        </div>
      </div>

      <div class="wf-card" style="flex-direction:column;padding:0;overflow:hidden">
        <div style="width:100%;height:150px;background:#e6eef7;display:flex;align-items:center;justify-content:center;border-bottom:1px solid var(--border)">
<svg width="96" height="96" viewBox="0 0 100 100" role="img" aria-label="Cloud with raindrops and a question mark">
<g fill="#7d8ea8">
<circle cx="28" cy="42" r="12"/><circle cx="42" cy="33" r="15"/><circle cx="55" cy="43" r="11"/>
<rect x="28" y="42" width="27" height="12" rx="6"/>
</g>
<g fill="#4a90d9">
<path d="M0 0 c4 6 6 9 6 12 a6 6 0 0 1 -12 0 c0 -3 2 -6 6 -12 Z" transform="translate(30,60)"><animateTransform attributeName="transform" type="translate" additive="sum" values="0 0;0 18" dur="0.9s" repeatCount="indefinite" begin="0s"/><animate attributeName="opacity" values="1;1;0" keyTimes="0;0.55;1" dur="0.9s" repeatCount="indefinite" begin="0s"/></path>
<path d="M0 0 c4 6 6 9 6 12 a6 6 0 0 1 -12 0 c0 -3 2 -6 6 -12 Z" transform="translate(42,67)"><animateTransform attributeName="transform" type="translate" additive="sum" values="0 0;0 18" dur="0.9s" repeatCount="indefinite" begin="0.3s"/><animate attributeName="opacity" values="1;1;0" keyTimes="0;0.55;1" dur="0.9s" repeatCount="indefinite" begin="0.3s"/></path>
<path d="M0 0 c4 6 6 9 6 12 a6 6 0 0 1 -12 0 c0 -3 2 -6 6 -12 Z" transform="translate(53,60)"><animateTransform attributeName="transform" type="translate" additive="sum" values="0 0;0 18" dur="0.9s" repeatCount="indefinite" begin="0.6s"/><animate attributeName="opacity" values="1;1;0" keyTimes="0;0.55;1" dur="0.9s" repeatCount="indefinite" begin="0.6s"/></path>
</g>
<text x="82" y="60" font-family="-apple-system,Segoe UI,Roboto,sans-serif" font-size="44" font-weight="700" fill="#485d92" text-anchor="middle">?</text>
</svg>
</div>
        <div style="padding:14px;display:flex;flex-direction:column;gap:10px;width:100%">
          <div class="wf-row">
            <span style="font-weight:700;font-size:15px">Weather Widget</span>
            <span class="wf-hint">Sep 12, 2026</span>
          </div>
          <div style="display:flex;gap:8px">
            <a href="#" class="wf-btn wf-btn-outline" style="flex:1;min-height:40px;padding:8px 12px;font-size:13px">Download source</a>
            <a href="#" class="wf-btn wf-btn-primary" style="flex:1;flex-direction:column;gap:1px;min-height:40px;padding:6px 12px">
              <span style="font-size:13px">Download APK</span>
              <span style="font-size:9px;font-weight:400;opacity:.85">1.8 MB</span>
            </a>
          </div>
        </div>
      </div>

      <div class="wf-card" style="flex-direction:column;padding:0;overflow:hidden">
        <div style="width:100%;height:150px;background:#eaf3ee;display:flex;align-items:center;justify-content:center;border-bottom:1px solid var(--border)">
<svg width="96" height="96" viewBox="0 0 100 100" role="img" aria-label="Penguin riding a bicycle">
<g fill="none" stroke="#485d92" stroke-width="3" stroke-linecap="round">
<circle cx="26" cy="78" r="14"/><circle cx="74" cy="78" r="14"/>
<line x1="50" y1="78" x2="26" y2="78"/><line x1="44" y1="56" x2="26" y2="78"/>
<line x1="50" y1="78" x2="44" y2="56"/><line x1="44" y1="56" x2="68" y2="56"/>
<line x1="50" y1="78" x2="69" y2="57"/><line x1="70" y1="54" x2="74" y2="78"/>
<line x1="70" y1="54" x2="78" y2="49"/>
</g>
<ellipse cx="43" cy="54" rx="6.5" ry="2.4" fill="#485d92"/>
<path d="M46 54 L53 69" stroke="#20242b" stroke-width="4.5" stroke-linecap="round" fill="none"/>
<ellipse cx="44" cy="42" rx="10.5" ry="13.5" fill="#20242b"/>
<ellipse cx="45.5" cy="44.5" rx="6.5" ry="9" fill="#ffffff"/>
<path d="M48 40 Q60 44 70 51" stroke="#20242b" stroke-width="5" stroke-linecap="round" fill="none"/>
<circle cx="50" cy="24" r="8.5" fill="#20242b"/>
<circle cx="52.5" cy="26" r="5" fill="#ffffff"/>
<circle cx="53" cy="23" r="1.7" fill="#20242b"/>
<path d="M58 23.5 L65.5 26.5 L58 29.5 Z" fill="#ff9800"/>
<ellipse cx="54" cy="71" rx="4.2" ry="2.3" fill="#ff9800"/>
</svg>
</div>
        <div style="padding:14px;display:flex;flex-direction:column;gap:10px;width:100%">
          <div class="wf-row">
            <span style="font-weight:700;font-size:15px">Recipe Box</span>
            <span class="wf-hint">Sep 5, 2026</span>
          </div>
          <p class="wf-product-desc">Family recipes, organized by course.</p>
          <div style="display:flex;gap:8px">
            <a href="#" class="wf-btn wf-btn-outline" style="flex:1;min-height:40px;padding:8px 12px;font-size:13px">Download source</a>
            <a href="#" class="wf-btn wf-btn-primary" style="flex:1;flex-direction:column;gap:1px;min-height:40px;padding:6px 12px">
              <span style="font-size:13px">Download APK</span>
              <span style="font-size:9px;font-weight:400;opacity:.85">3.1 MB</span>
            </a>
          </div>
        </div>
      </div>

  </div>

  <sc-if value="{{descExpanded}}" hint-placeholder-val="{{false}}">
    <div class="wf-desc-overlay">
      <div class="wf-row" style="flex:0 0 auto">
        <span style="color:#fff;font-weight:700">About this store</span>
        <button class="wf-desc-zoom-btn" onClick="{{toggleDescExpand}}" aria-label="Close">✕</button>
      </div>
      <div class="wf-desc-overlay-body" style="font-size:{{descFontSize}}px">{{storeDescriptionHtml}}</div>
      <div class="wf-desc-zoom-row">
        <button class="wf-desc-zoom-btn" onClick="{{zoomOut}}" aria-label="Smaller text">–</button>
        <button class="wf-desc-zoom-btn" onClick="{{zoomIn}}" aria-label="Larger text">+</button>
      </div>
    </div>
  </sc-if>
</div>
</x-dc>
<script type="text/x-dc" data-dc-script data-props='{"$preview":{"width":390,"height":844}}'>
class Component extends DCLogic {
  renderVals() {
    const storeDescriptionHtml = "Thanks for stopping by! Everything here is free, no account needed, no ads, nothing tracked. If something doesn't work, come find me and I'll fix it on the spot — I'm usually right around here on market days.";
    const descExpanded = !!(this.state && this.state.descExpanded);
    const descFontSize = (this.state && this.state.descFontSize) || 15;
    const toggleDescExpand = () => this.setState({ descExpanded: !descExpanded });
    const zoomIn = () => this.setState({ descFontSize: Math.min(descFontSize + 2, 28) });
    const zoomOut = () => this.setState({ descFontSize: Math.max(descFontSize - 2, 12) });

    return { storeDescriptionHtml, descExpanded, descFontSize, toggleDescExpand, zoomIn, zoomOut };
  }
}
</script>
</body>
</html>
```

## B.8 `BuyerCatalogLandscape.dc.html` — Buyer's browser — rendered catalog page, landscape tablet

```html
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Buyer catalog — landscape tablet</title>
<script src="./support.js"></script>
<link rel="stylesheet" href="./wireframe.css">
</head>
<body>
<x-dc>
<helmet>
<style>
body{margin:0}
</style>
</helmet>
<div class="wf-screen" style="width: 900px; height: 700px; position: relative;">
  <div class="wf-header" style="display:flex;flex-direction:column;align-items:flex-start;gap:6px;border-bottom:none;background:#233490;color:#fff">
    <div style="display:flex;align-items:center;gap:12px">
      <svg width="36" height="36" viewBox="0 0 160 160" xmlns="http://www.w3.org/2000/svg" style="flex:0 0 auto" role="img" aria-label="Share Your Apps Server app icon">
        <rect x="20" y="8" width="120" height="144" rx="20" fill="#233490"/>
        <circle cx="80" cy="23" r="2.6" fill="#141f66"/>
        <rect x="34" y="34" width="92" height="90" rx="8" fill="#2c3fae"/>
        <g transform="translate(48,57.5)" stroke="#00be00" stroke-width="4.2" fill="none" stroke-linecap="round" stroke-linejoin="round">
          <path d="M0 2 H8 L14 28 H50 L58 8 H16"/>
          <g transform="translate(20,36)">
            <circle r="5" fill="#2c3fae" stroke="#00be00" stroke-width="1.4"/>
            <g stroke="#00be00" stroke-width="1.4"><line x1="-3.6" y1="0" x2="3.6" y2="0"/><line x1="0" y1="-3.6" x2="0" y2="3.6"/></g>
          </g>
          <g transform="translate(44,36)">
            <circle r="5" fill="#2c3fae" stroke="#00be00" stroke-width="1.4"/>
            <g stroke="#00be00" stroke-width="1.4"><line x1="-3.6" y1="0" x2="3.6" y2="0"/><line x1="0" y1="-3.6" x2="0" y2="3.6"/></g>
          </g>
        </g>
      </svg>
      <h1 class="wf-title" style="color:#fff">My Share Your Apps</h1>
    </div>
    <div style="position:relative;padding-right:28px;max-width:420px">
      <div class="wf-desc-box wf-desc-box-dark">
        <p>{{storeDescriptionHtml}}</p>
        <div class="wf-desc-scrollbar wf-desc-scrollbar-dark"></div>
      </div>
      <button class="wf-desc-expand-btn wf-desc-expand-btn-dark" onClick="{{toggleDescExpand}}" aria-label="Full screen">
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 3 21 3 21 9"></polyline><polyline points="9 21 3 21 3 15"></polyline><line x1="21" y1="3" x2="14" y2="10"></line><line x1="3" y1="21" x2="10" y2="14"></line></svg>
      </button>
    </div>
  </div>
  <div class="wf-body" style="padding-top:20px">
    <div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(240px,1fr));gap:20px;align-items:start">
              <div class="wf-card" style="flex-direction:column;padding:0;overflow:hidden">
          <div style="width:100%;height:130px;background:#f3ead7;display:flex;align-items:center;justify-content:center;border-bottom:1px solid var(--border)">
<svg width="84" height="84" viewBox="0 0 100 100" role="img" aria-label="Vitruvian figure">
<circle cx="50" cy="48" r="36" fill="none" stroke="#a89273" stroke-width="2"/>
<rect x="16" y="30" width="68" height="68" fill="none" stroke="#a89273" stroke-width="2"/>
<g stroke="#5a4632" stroke-width="3.2" fill="none" stroke-linecap="round">
<line x1="50" y1="30" x2="50" y2="62"/>
<line x1="50" y1="36" x2="17" y2="36"/><line x1="50" y1="36" x2="83" y2="36"/>
<line x1="50" y1="36" x2="27" y2="21"/><line x1="50" y1="36" x2="73" y2="21"/>
<line x1="50" y1="62" x2="42" y2="96"/><line x1="50" y1="62" x2="58" y2="96"/>
<line x1="50" y1="62" x2="28" y2="76"/><line x1="50" y1="62" x2="72" y2="76"/>
</g>
<circle cx="50" cy="23" r="6.5" fill="#5a4632"/>
</svg>
</div>
          <div style="padding:14px;display:flex;flex-direction:column;gap:10px;width:100%">
            <div class="wf-row">
              <span style="font-weight:700;font-size:14px">My Todo App</span>
              <span class="wf-hint">Sep 19, 2026</span>
            </div>
            <p class="wf-product-desc">A simple, offline-first to-do list. No account needed.</p>
            <div style="display:flex;gap:8px">
              <a href="#" class="wf-btn wf-btn-outline" style="flex:1;min-height:40px;padding:8px 12px;font-size:13px">Source</a>
              <a href="#" class="wf-btn wf-btn-primary" style="flex:1;flex-direction:column;gap:1px;min-height:40px;padding:6px 12px">
                <span style="font-size:13px">Download APK</span>
                <span style="font-size:9px;font-weight:400;opacity:.85">4.2 MB</span>
              </a>
            </div>
          </div>
        </div>

        <div class="wf-card" style="flex-direction:column;padding:0;overflow:hidden">
          <div style="width:100%;height:130px;background:#e6eef7;display:flex;align-items:center;justify-content:center;border-bottom:1px solid var(--border)">
<svg width="84" height="84" viewBox="0 0 100 100" role="img" aria-label="Cloud with raindrops and a question mark">
<g fill="#7d8ea8">
<circle cx="28" cy="42" r="12"/><circle cx="42" cy="33" r="15"/><circle cx="55" cy="43" r="11"/>
<rect x="28" y="42" width="27" height="12" rx="6"/>
</g>
<g fill="#4a90d9">
<path d="M0 0 c4 6 6 9 6 12 a6 6 0 0 1 -12 0 c0 -3 2 -6 6 -12 Z" transform="translate(30,60)"><animateTransform attributeName="transform" type="translate" additive="sum" values="0 0;0 18" dur="0.9s" repeatCount="indefinite" begin="0s"/><animate attributeName="opacity" values="1;1;0" keyTimes="0;0.55;1" dur="0.9s" repeatCount="indefinite" begin="0s"/></path>
<path d="M0 0 c4 6 6 9 6 12 a6 6 0 0 1 -12 0 c0 -3 2 -6 6 -12 Z" transform="translate(42,67)"><animateTransform attributeName="transform" type="translate" additive="sum" values="0 0;0 18" dur="0.9s" repeatCount="indefinite" begin="0.3s"/><animate attributeName="opacity" values="1;1;0" keyTimes="0;0.55;1" dur="0.9s" repeatCount="indefinite" begin="0.3s"/></path>
<path d="M0 0 c4 6 6 9 6 12 a6 6 0 0 1 -12 0 c0 -3 2 -6 6 -12 Z" transform="translate(53,60)"><animateTransform attributeName="transform" type="translate" additive="sum" values="0 0;0 18" dur="0.9s" repeatCount="indefinite" begin="0.6s"/><animate attributeName="opacity" values="1;1;0" keyTimes="0;0.55;1" dur="0.9s" repeatCount="indefinite" begin="0.6s"/></path>
</g>
<text x="82" y="60" font-family="-apple-system,Segoe UI,Roboto,sans-serif" font-size="44" font-weight="700" fill="#485d92" text-anchor="middle">?</text>
</svg>
</div>
          <div style="padding:14px;display:flex;flex-direction:column;gap:10px;width:100%">
            <div class="wf-row">
              <span style="font-weight:700;font-size:14px">Weather Widget</span>
              <span class="wf-hint">Sep 12, 2026</span>
            </div>
            <div style="display:flex;gap:8px">
              <a href="#" class="wf-btn wf-btn-outline" style="flex:1;min-height:40px;padding:8px 12px;font-size:13px">Source</a>
              <a href="#" class="wf-btn wf-btn-primary" style="flex:1;flex-direction:column;gap:1px;min-height:40px;padding:6px 12px">
                <span style="font-size:13px">Download APK</span>
                <span style="font-size:9px;font-weight:400;opacity:.85">1.8 MB</span>
              </a>
            </div>
          </div>
        </div>

        <div class="wf-card" style="flex-direction:column;padding:0;overflow:hidden">
          <div style="width:100%;height:130px;background:#eaf3ee;display:flex;align-items:center;justify-content:center;border-bottom:1px solid var(--border)">
<svg width="84" height="84" viewBox="0 0 100 100" role="img" aria-label="Penguin riding a bicycle">
<g fill="none" stroke="#485d92" stroke-width="3" stroke-linecap="round">
<circle cx="26" cy="78" r="14"/><circle cx="74" cy="78" r="14"/>
<line x1="50" y1="78" x2="26" y2="78"/><line x1="44" y1="56" x2="26" y2="78"/>
<line x1="50" y1="78" x2="44" y2="56"/><line x1="44" y1="56" x2="68" y2="56"/>
<line x1="50" y1="78" x2="69" y2="57"/><line x1="70" y1="54" x2="74" y2="78"/>
<line x1="70" y1="54" x2="78" y2="49"/>
</g>
<ellipse cx="43" cy="54" rx="6.5" ry="2.4" fill="#485d92"/>
<path d="M46 54 L53 69" stroke="#20242b" stroke-width="4.5" stroke-linecap="round" fill="none"/>
<ellipse cx="44" cy="42" rx="10.5" ry="13.5" fill="#20242b"/>
<ellipse cx="45.5" cy="44.5" rx="6.5" ry="9" fill="#ffffff"/>
<path d="M48 40 Q60 44 70 51" stroke="#20242b" stroke-width="5" stroke-linecap="round" fill="none"/>
<circle cx="50" cy="24" r="8.5" fill="#20242b"/>
<circle cx="52.5" cy="26" r="5" fill="#ffffff"/>
<circle cx="53" cy="23" r="1.7" fill="#20242b"/>
<path d="M58 23.5 L65.5 26.5 L58 29.5 Z" fill="#ff9800"/>
<ellipse cx="54" cy="71" rx="4.2" ry="2.3" fill="#ff9800"/>
</svg>
</div>
          <div style="padding:14px;display:flex;flex-direction:column;gap:10px;width:100%">
            <div class="wf-row">
              <span style="font-weight:700;font-size:14px">Recipe Box</span>
              <span class="wf-hint">Sep 5, 2026</span>
            </div>
            <p class="wf-product-desc">Family recipes, organized by course.</p>
            <div style="display:flex;gap:8px">
              <a href="#" class="wf-btn wf-btn-outline" style="flex:1;min-height:40px;padding:8px 12px;font-size:13px">Source</a>
              <a href="#" class="wf-btn wf-btn-primary" style="flex:1;flex-direction:column;gap:1px;min-height:40px;padding:6px 12px">
                <span style="font-size:13px">Download APK</span>
                <span style="font-size:9px;font-weight:400;opacity:.85">3.1 MB</span>
              </a>
            </div>
          </div>
        </div>
    </div>
  </div>

  <sc-if value="{{descExpanded}}" hint-placeholder-val="{{false}}">
    <div class="wf-desc-overlay">
      <div class="wf-row" style="flex:0 0 auto">
        <span style="color:#fff;font-weight:700">About this store</span>
        <button class="wf-desc-zoom-btn" onClick="{{toggleDescExpand}}" aria-label="Close">✕</button>
      </div>
      <div class="wf-desc-overlay-body" style="font-size:{{descFontSize}}px;max-width:480px">{{storeDescriptionHtml}}</div>
      <div class="wf-desc-zoom-row">
        <button class="wf-desc-zoom-btn" onClick="{{zoomOut}}" aria-label="Smaller text">–</button>
        <button class="wf-desc-zoom-btn" onClick="{{zoomIn}}" aria-label="Larger text">+</button>
      </div>
    </div>
  </sc-if>
</div>
</x-dc>
<script type="text/x-dc" data-dc-script data-props='{"$preview":{"width":900,"height":700}}'>
class Component extends DCLogic {
  renderVals() {
    const storeDescriptionHtml = "Thanks for stopping by! Everything here is free, no account needed, no ads, nothing tracked. If something doesn't work, come find me and I'll fix it on the spot — I'm usually right around here on market days.";
    const descExpanded = !!(this.state && this.state.descExpanded);
    const descFontSize = (this.state && this.state.descFontSize) || 15;
    const toggleDescExpand = () => this.setState({ descExpanded: !descExpanded });
    const zoomIn = () => this.setState({ descFontSize: Math.min(descFontSize + 2, 28) });
    const zoomOut = () => this.setState({ descFontSize: Math.max(descFontSize - 2, 12) });

    return { storeDescriptionHtml, descExpanded, descFontSize, toggleDescExpand, zoomIn, zoomOut };
  }
}
</script>
</body>
</html>
```

## B.9 `ApkVariantPicker.dc.html` — Plugin — APK variant picker

Grouped by build type (Release / Debug / per-ABI splits). The built picker is simpler; see the list at the top of this appendix.

```html
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Plugin — APK variant picker</title>
<script src="./support.js"></script>
<link rel="stylesheet" href="./wireframe.css">
</head>
<body>
<x-dc>
<helmet>
<style>
body{margin:0}
</style>
</helmet>
<div class="wf-screen" style="width: 390px; height: 844px;">
  <div class="wf-header" style="padding:12px 20px">
    <p class="wf-eyebrow">MyTodoApp — app/build/outputs/apk</p>
    <h1 class="wf-title">Choose an APK</h1>
  </div>
  <div class="wf-body" style="gap:0">

    <div class="wf-group-label">Release</div>
    <div class="wf-list-row">
      <div style="width:34px;height:34px;border-radius:7px;background:#eef1f8;display:flex;align-items:center;justify-content:center;flex:0 0 auto;border:1px solid var(--border-strong)"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#485d92" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" role="img" aria-label="APK file"><path d="M21 8 12 3 3 8l9 5 9-5Z"/><path d="M3 8v8l9 5 9-5V8"/><path d="M12 13v8"/></svg></div>
      <div style="flex:1">
        <div style="font-weight:600;font-size:14px">app-release.apk</div>
        <div class="wf-hint">4.2 MB · built Sep 19, 9:41 AM</div>
      </div>
      <span style="color:var(--muted)">›</span>
    </div>

    <div class="wf-group-label">Debug</div>
    <div class="wf-list-row">
      <div style="width:34px;height:34px;border-radius:7px;background:#eef1f8;display:flex;align-items:center;justify-content:center;flex:0 0 auto;border:1px solid var(--border-strong)"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#485d92" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" role="img" aria-label="APK file"><path d="M21 8 12 3 3 8l9 5 9-5Z"/><path d="M3 8v8l9 5 9-5V8"/><path d="M12 13v8"/></svg></div>
      <div style="flex:1">
        <div style="font-weight:600;font-size:14px">app-debug.apk</div>
        <div class="wf-hint">6.8 MB · built Sep 19, 9:38 AM</div>
      </div>
      <span style="color:var(--muted)">›</span>
    </div>
    <div class="wf-list-row">
      <div style="width:34px;height:34px;border-radius:7px;background:#eef1f8;display:flex;align-items:center;justify-content:center;flex:0 0 auto;border:1px solid var(--border-strong)"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#485d92" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" role="img" aria-label="APK file"><path d="M21 8 12 3 3 8l9 5 9-5Z"/><path d="M3 8v8l9 5 9-5V8"/><path d="M12 13v8"/></svg></div>
      <div style="flex:1">
        <div style="font-weight:600;font-size:14px">app-quickbuild.apk</div>
        <div class="wf-hint">7.1 MB · built Sep 18, 4:02 PM</div>
      </div>
      <span style="color:var(--muted)">›</span>
    </div>

    <div class="wf-group-label">Per-ABI splits (release)</div>
    <div class="wf-list-row">
      <div style="width:34px;height:34px;border-radius:7px;background:#eef1f8;display:flex;align-items:center;justify-content:center;flex:0 0 auto;border:1px solid var(--border-strong)"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#485d92" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" role="img" aria-label="APK file"><path d="M21 8 12 3 3 8l9 5 9-5Z"/><path d="M3 8v8l9 5 9-5V8"/><path d="M12 13v8"/></svg></div>
      <div style="flex:1">
        <div style="font-weight:600;font-size:14px">app-armeabi-v7a-release.apk</div>
        <div class="wf-hint">2.9 MB · built Sep 19, 9:41 AM</div>
      </div>
      <span style="color:var(--muted)">›</span>
    </div>
    <div class="wf-list-row">
      <div style="width:34px;height:34px;border-radius:7px;background:#eef1f8;display:flex;align-items:center;justify-content:center;flex:0 0 auto;border:1px solid var(--border-strong)"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#485d92" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" role="img" aria-label="APK file"><path d="M21 8 12 3 3 8l9 5 9-5Z"/><path d="M3 8v8l9 5 9-5V8"/><path d="M12 13v8"/></svg></div>
      <div style="flex:1">
        <div style="font-weight:600;font-size:14px">app-arm64-v8a-release.apk</div>
        <div class="wf-hint">3.1 MB · built Sep 19, 9:41 AM</div>
      </div>
      <span style="color:var(--muted)">›</span>
    </div>
    <div class="wf-list-row">
      <div style="width:34px;height:34px;border-radius:7px;background:#eef1f8;display:flex;align-items:center;justify-content:center;flex:0 0 auto;border:1px solid var(--border-strong)"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#485d92" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" role="img" aria-label="APK file"><path d="M21 8 12 3 3 8l9 5 9-5Z"/><path d="M3 8v8l9 5 9-5V8"/><path d="M12 13v8"/></svg></div>
      <div style="flex:1">
        <div style="font-weight:600;font-size:14px">app-x86-release.apk</div>
        <div class="wf-hint">3.0 MB · built Sep 19, 9:41 AM</div>
      </div>
      <span style="color:var(--muted)">›</span>
    </div>

  </div>
</div>
</x-dc>
<script type="text/x-dc" data-dc-script data-props='{"$preview":{"width":390,"height":844}}'>
class Component extends DCLogic {
  renderVals() {
    return {};
  }
}
</script>
</body>
</html>
```

## B.10 `PreflightErrorDialog.dc.html` — Plugin — pre-flight validation error dialog

```html
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Plugin — pre-flight error dialog</title>
<script src="./support.js"></script>
<link rel="stylesheet" href="./wireframe.css">
</head>
<body>
<x-dc>
<helmet>
<style>
body{margin:0}
</style>
</helmet>
<div class="wf-dialog-backdrop" style="width: 390px; height: 600px;">
  <div class="wf-dialog">
    <div style="display:flex;align-items:flex-start;gap:10px">
      <div style="width:28px;height:28px;border-radius:50%;border:2px solid var(--danger);color:var(--danger);flex:0 0 auto;display:flex;align-items:center;justify-content:center">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="8" x2="12" y2="13"></line><line x1="12" y1="16.5" x2="12" y2="16.6"></line></svg>
      </div>
      <div>
        <div style="font-weight:700;font-size:16px">Can't publish yet</div>
        <p class="wf-hint" style="margin:4px 0 0">Nothing has been changed or published.</p>
      </div>
    </div>
    <div class="wf-divider"></div>
    <div class="wf-field">
      <div class="wf-label">Extra JSON is invalid</div>
      <div class="wf-textarea" style="color:var(--danger);background:#ffebee;border-color:var(--danger)">Unexpected token '}' at line 4, column 3</div>
      <p class="wf-hint">Fix the error shown above, then tap "Create / Install / Launch Store" again.</p>
    </div>
    <button class="wf-btn wf-btn-primary">OK</button>
  </div>
</div>
</x-dc>
<script type="text/x-dc" data-dc-script data-props='{"$preview":{"width":390,"height":600}}'>
class Component extends DCLogic {
  renderVals() {
    return {};
  }
}
</script>
</body>
</html>
```

## B.11 `ConfirmationDialogs.dc.html` — Plugin — Create/Install/Launch and Cancel confirmation dialogs

```html
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Plugin — confirmation dialogs</title>
<script src="./support.js"></script>
<link rel="stylesheet" href="./wireframe.css">
</head>
<body>
<x-dc>
<helmet>
<style>
body{margin:0}
</style>
</helmet>
<div style="width: 420px; height: 880px; background: var(--bg); display: flex; flex-direction: column; align-items: center; gap: 32px; padding: 40px 0; box-sizing: border-box; overflow-y: auto;">

  <div class="wf-dialog" style="max-width:340px">
    <div style="font-weight:700;font-size:16px">Create / Install / Launch Store?</div>
    <div style="display:flex;align-items:center;gap:8px">
      <span class="wf-dot"></span>
      <span class="wf-hint">Server idle — safe to publish</span>
    </div>
    <div class="wf-field" style="gap:8px">
      <div style="font-size:13px">This will:</div>
      <div style="font-size:13px;color:var(--muted);display:flex;flex-direction:column;gap:4px">
        <div>• Copy 2 APKs and build 1 source zip</div>
        <div>• Install the Share Your Apps Server app (if needed)</div>
        <div>• Start the server on port 6175</div>
      </div>
    </div>
    <p class="wf-hint">Publishing now may interfere with anyone currently downloading from the store.</p>
    <div class="wf-row">
      <button class="wf-btn wf-btn-outline" style="flex:1">Back</button>
      <button class="wf-btn wf-btn-primary" style="flex:1">Confirm</button>
    </div>
  </div>

  <div class="wf-dialog" style="max-width:340px">
    <div style="font-weight:700;font-size:16px">Discard unsaved changes?</div>
    <p class="wf-hint" style="margin:0">Leaving now will lose edits to your catalog that haven't been published yet.</p>
    <div class="wf-row">
      <button class="wf-btn wf-btn-outline" style="flex:1">Keep editing</button>
      <button class="wf-btn wf-btn-danger" style="flex:1">Discard</button>
    </div>
  </div>

</div>
</x-dc>
<script type="text/x-dc" data-dc-script data-props='{"$preview":{"width":420,"height":880}}'>
class Component extends DCLogic {
  renderVals() {
    return {};
  }
}
</script>
</body>
</html>
```

## B.12 `PinEntryPage.dc.html` — Buyer's browser — PIN entry page, default and incorrect-PIN states

```html
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Buyer catalog — PIN entry page</title>
<script src="./support.js"></script>
<link rel="stylesheet" href="./wireframe.css">
</head>
<body>
<x-dc>
<helmet>
<style>
body{margin:0}
</style>
</helmet>
<div style="width: 820px; height: 844px; display: flex; gap: 40px; background: var(--bg);">

  <div class="wf-screen" style="width: 390px; height: 844px; align-items: center; justify-content: center; text-align: center;">
    <div style="width:44px;height:44px;background:#233490;border-radius:10px;margin-bottom:16px"></div>
    <div style="font-weight:700;font-size:18px;margin-bottom:4px">My Share Your Apps</div>
    <p class="wf-hint" style="margin:0 0 22px">Enter the PIN to continue</p>
    <div class="wf-field" style="width:220px">
      <input class="wf-input" style="text-align:center;letter-spacing:.3em;font-size:18px" placeholder="••••">
      <button class="wf-btn wf-btn-primary" style="margin-top:10px">Enter</button>
    </div>
  </div>

  <div class="wf-screen" style="width: 390px; height: 844px; align-items: center; justify-content: center; text-align: center;">
    <div style="width:44px;height:44px;background:#233490;border-radius:10px;margin-bottom:16px"></div>
    <div style="font-weight:700;font-size:18px;margin-bottom:4px">My Share Your Apps</div>
    <p class="wf-hint" style="margin:0 0 22px">Enter the PIN to continue</p>
    <div class="wf-field" style="width:220px">
      <input class="wf-input" style="text-align:center;letter-spacing:.3em;font-size:18px;border-color:var(--danger);color:var(--danger)" value="••••">
      <div style="font-size:12px;color:var(--danger);font-weight:600">Incorrect PIN</div>
      <button class="wf-btn wf-btn-primary" style="margin-top:6px">Enter</button>
    </div>
  </div>

</div>
</x-dc>
<script type="text/x-dc" data-dc-script data-props='{"$preview":{"width":820,"height":844}}'>
class Component extends DCLogic {
  renderVals() {
    return {};
  }
}
</script>
</body>
</html>
```

## B.13 Canvas layout index (`canvas.json`)

```json
{
  "v": 3,
  "createdOnFiles": { "v": 1, "at": "2026-09-21T00:00:00Z" },
  "title": "Share Your Apps Plugin — Wireframes",
  "launch": { "view": "canvas" },
  "pages": [],
  "boards": {
    "Main.dc.html": { "x": 0, "y": 0, "w": 390, "h": 844, "title": "Manage Catalog", "is_interactive": true },
    "Icon.dc.html": { "x": 470, "y": 0, "w": 160, "h": 160, "title": "App icon" },
    "ManageServer.dc.html": { "x": 710, "y": 0, "w": 390, "h": 844, "title": "Manage Server", "is_interactive": true },
    "ServeScreen.dc.html": { "x": 1180, "y": 0, "w": 390, "h": 844, "title": "Serve (customer-facing)", "is_interactive": true },
    "FullScreenLogView.dc.html": { "x": 1650, "y": 0, "w": 390, "h": 844, "title": "Full-screen log view" },
    "BuyerCatalogPortrait.dc.html": { "x": 0, "y": 964, "w": 390, "h": 844, "title": "Buyer catalog — portrait", "is_interactive": true },
    "BuyerCatalogLandscape.dc.html": { "x": 470, "y": 964, "w": 900, "h": 700, "title": "Buyer catalog — landscape", "is_interactive": true },
    "ApkVariantPicker.dc.html": { "x": 1450, "y": 964, "w": 390, "h": 844, "title": "APK variant picker" },
    "PreflightErrorDialog.dc.html": { "x": 0, "y": 1928, "w": 390, "h": 600, "title": "Pre-flight error dialog" },
    "ConfirmationDialogs.dc.html": { "x": 470, "y": 1928, "w": 420, "h": 880, "title": "Confirmation dialogs" },
    "PinEntryPage.dc.html": { "x": 1450, "y": 1928, "w": 820, "h": 844, "title": "PIN entry page" }
  },
  "order": [
    "Main.dc.html",
    "Icon.dc.html",
    "ManageServer.dc.html",
    "ServeScreen.dc.html",
    "FullScreenLogView.dc.html",
    "BuyerCatalogPortrait.dc.html",
    "BuyerCatalogLandscape.dc.html",
    "ApkVariantPicker.dc.html",
    "PreflightErrorDialog.dc.html",
    "ConfirmationDialogs.dc.html",
    "PinEntryPage.dc.html"
  ],
  "notes": {
    "row1": { "x": 0, "y": -80, "text": "Plugin & Share Your Apps Server app screens", "kind": "title1", "maxW": 1570 },
    "row2": { "x": 0, "y": 884, "text": "Buyer-facing pages & pickers", "kind": "title1", "maxW": 1840 },
    "row3": { "x": 0, "y": 1848, "text": "Dialogs", "kind": "title1", "maxW": 1840 }
  },
  "designSystems": []
}
```

---

# Appendix C — Store page templates

The store page is a Pebble template. The plugin ships one, the default, which owners can replace under **Advanced** in Manage Catalog: paste a template into **Template override**, or tap **Choose a template file** to load a `.peb` file, starting in the phone's Download folder. Clearing the field, or tapping **Load the default template**, goes back to the default. *Writing a template*, in *Developer notes*, lists the Pebble rules that catch people out.

This appendix holds six templates:

- **C.1 — the default, annotated:** laid out for reading, with comments explaining how it works. It's a working template too, and renders the same page as C.2: published with the demo catalog and screenshotted, the two pages match pixel for pixel, apart from the octopus's animation.
- **C.2 — the default, as shipped:** exactly the text in the plugin's `Templates.kt`, minified so it costs buyers as little as possible.
- **C.3 to C.5 — three alternatives,** which present the same information in very different ways: Neon Arcade, Morning Edition and Aurora Deck.
- **C.6 — Clear Access,** for buyers who use a screen reader or have low vision.

All of them use everything the page receives, as listed at the top of C.1. Every publish also renders C.6, Clear Access, as `accessible.html`, and each of the others links to it from its top left as *Accessible version*. All six pass axe-core's WCAG 2.2 level A and AA checks, and its best-practice rules, with the demo catalog; the only items left for review are video captions, which the catalog can't supply, and text over animated or gradient backgrounds, which axe can't measure. They work offline, with no web fonts or outside files, and stop animating for anyone whose phone asks for reduced motion. Each was published with the demo catalog through the plugin's own publisher and Pebble, produced valid HTML and a poster for each video, and was checked in phone-width screenshots in WebKit.

## C.1 The default template, annotated

```
{#
  BLOCKS: this template is split into blocks — styles, header, announcement,
  card, downloads and footer. A template of your own can extend it and replace just one:

    {% extends "default" %}
    {% block downloads %}<a class="b b1" href="{{ p.apk.src }}">Get it</a>{% endblock %}

  {{ parent() }} inside a block keeps the original too. Other templates, and macros, go in the
  template library, CodeOnTheGoProjects/ShareYourAppsTemplates/, for {% include %} and {% import %}.
#}
{#
  The default Share Your Apps page, laid out and annotated. It renders the same page as the compact
  copy the plugin ships. Comments like this one are Pebble comments: Pebble removes
  them while rendering, so none reach buyers. The /* … */ comments in the CSS and script do reach
  the page, as ordinary CSS and JavaScript comments.

  What the template receives:
    lang                   the page's language, such as "en-US": Extra JSON's "lang" if set, else the phone's
    publishedAt            when this publish happened, as an ISO date and time; publishedLabel is "Sep 24, 2026"
    shareYourAppsVersion      the plugin's version, such as "1.0.0", for a "Made with Share Your Apps" line
    contact                from Extra JSON, checked: .name, .email, .phone and .url, each with an
                           .emailHref, .phoneHref or .urlHref only when it's a plausible address
    announcement           from Extra JSON: a short line of news, as plain text
    accent, accentInk      from Extra JSON: a colour, only ever #rrggbb, and black or white — whichever
                           reads better on it, which always reaches WCAG's 4.5 to 1
    storeName              the store's name, as text
    storeDescriptionHtml   the owner's description, as HTML, or null
    headerImage            .src and .type ("image" or "video") — the octopus when none is chosen
    products               in Manage Catalog's order, each with:
      .name, .ordinal, .slug (a stable ID, such as "termux"), .descriptionHtml (HTML or null)
      .dateAddedLabel      "Sep 24, 2026"; .dateAddedIso is the same as an ISO date and time
      .carousel            pictures, videos and sounds: .src, .type, .poster for a video, and
                           .width and .height where known
      .apk                 .src, .sizeLabel ("6.8 MB") and .sizeBytes, or null; and what the plugin reads
                           from the APK: .versionName, .versionCode, .packageName, .minSdk and
                           .androidLabel ("Android 9 or later") — each null when it can't be read —
                           and .abis, its native-code architectures, with .abiLabel for buyers
                           ("64-bit phones", "32-bit phones", "32- and 64-bit phones", or "all
                           phones" when it has no native code)
      .sourceZip           .src, .sizeLabel and .sizeBytes, or null
      .whatsNew, .license, .author
                           from the product's Extra JSON, checked: plain text, cut to 500, 80 and 80
                           characters, or null. They sit on the product itself: p.whatsNew, not
                           p.extra.whatsNew
      .website, .websiteHref
                           from the product's Extra JSON: the text, cut to 300 characters, and the same
                           as a link, only when it's an http or https address, else null
      .extra               the product's whole Extra JSON, exactly as typed and not checked: any key the
                           owner added is here, but nothing in it is safe to use as a link. Prefer the
                           checked values above
    extra                  the store's whole Extra JSON, exactly as typed and not checked. Its "strings"
                           table feeds t("key", "fallback"), which picks the text for the store's
                           language; the other documented keys have checked values above (lang,
                           contact, announcement, accent)
    accessibleHref         the link to the accessible version of the page, accessible.html, which
                           every publish also makes from the Clear Access template

  Every .src is ready to use: a /f/… link to a file in the store, or, for anything of 10 KB or
  less, the file itself as a data: URI.

  Pebble rules that differ from other template languages: a condition needs "is not empty" —
  a bare {% if x %} is an error — and the sort filter takes no arguments, which is why products
  arrive already in order. {{ … }} escapes HTML; |raw prints the owner's HTML as it is.
#}
<!doctype html>
<html lang="{{lang}}">
<head>
<meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1">
<title>{{storeName}}</title>
{% block styles %}<style>
/* Start every element from nothing, so browsers' own margins and paddings don't differ. */
* {
	box-sizing:border-box;
	margin:0;
	padding:0;
}

/* The page: cream ground, dark violet ink, and the phone's own rounded font where it has one.
   No web fonts: the store works offline. */
body {
	background:#FFF3D6;
	color:#2B1B3D;
	font-family:ui-rounded,"SF Pro Rounded",system-ui,sans-serif;
}

/* The awning across the top, drawn entirely with gradients — no image file:
     1. red half-circles every 48 px, at 12 px,
     2. cream half-circles every 48 px, at 36 px, between the red ones,
     3. red and cream stripes, 24 px each, above them,
     4. the teal of the header behind everything. */
.aw {
	height:26px;
	background:
		radial-gradient(circle at 12px 14px,#FF4F3A 11.5px,transparent 12px) 0 0/48px 26px repeat-x,
		radial-gradient(circle at 36px 14px,#FFF3D6 11.5px,transparent 12px) 0 0/48px 26px repeat-x,
		repeating-linear-gradient(90deg,#FF4F3A 0 24px,#FFF3D6 24px 48px) 0 0/100% 14px no-repeat,
		#0B6E6E;
}

/* The header: the store's icon beside its name and description. */
header {
	background:#0B6E6E;
	color:#fff;
	padding:6px 16px 20px;
	display:flex;
	align-items:center;
	gap:14px;
}
header img,
header video {
	width:64px;
	height:64px;
	border-radius:16px;
	border:3px solid #2B1B3D;
	background:#fff;
	object-fit:cover;
	flex-shrink:0;
}
/* min-width:0 lets a long name wrap instead of pushing the icon off the screen. */
header .ht {
	display:flex;
	flex-direction:column;
	gap:4px;
	min-width:0;
}
h1 {
	font-size:22px;
	font-weight:800;
	overflow-wrap:anywhere;
}
.sd {
	font-size:13px;
	color:#FFF3D6;
	line-height:1.4;
}
.sd a {
	color:#FFE14D;
}

/* The products, in columns at least 260 px wide, as many as fit — one on a phone held upright.
   Each card sits directly under the one above, so a tall card never leaves a gap beside a short
   one; reading order runs down each column, then on to the next. */
.n {
	columns:260px;
	column-gap:20px;
	padding:22px 16px 10px;
}

/* A product's card: white, a thick dark outline, and a hard shadow offset down and right.
   break-inside:avoid, with inline-block, keeps a card whole rather than split across columns. */
.p {
	background:#fff;
	border:3px solid #2B1B3D;
	border-radius:16px;
	overflow:hidden;
	box-shadow:5px 5px 0 #2B1B3D;
	break-inside:avoid;
	display:inline-block;
	width:100%;
	margin-bottom:20px;
}

/* The picture strip: items side by side, scrolling sideways only, snapping one item at a time.
   overflow-y:hidden stops it scrolling up and down, so dragging the page on a picture can't
   nudge the picture out of place. */
.c {
	display:flex;
	overflow-x:auto;
	overflow-y:hidden;
	scroll-snap-type:x mandatory;
	border-bottom:3px solid #2B1B3D;
}
.c>div {
	flex:0 0 100%;
	scroll-snap-align:start;
	height:160px;
	background:#E3F2EF;
}
/* Each picture or video fills its 160 px box, keeping 1 px clear above and below, and is scaled
   to fit whole ("contain") rather than cropped. display:block removes the few pixels a browser
   otherwise leaves under a picture for letters like "g". */
.c img,
.c video {
	display:block;
	box-sizing:border-box;
	width:100%;
	height:160px;
	padding:1px 0;
	object-fit:contain;
}

/* The arrows over a strip with more than one item: small white circles, like the buttons. */
.cw {
	position:relative;
}
/* A strip can take keyboard focus, so it can be scrolled with the arrow keys; this shows where focus is. */
.c:focus-visible {
	outline:3px solid #8E5BFF;
	outline-offset:-3px;
}
/* A product's name is a real heading, for screen reader users, but looks like the rest of its line. */
.t h2 {
	font:inherit;
	margin:0;
}
/* The link to the accessible version: a small white pill over the awning's top-left corner, so
   the header's layout doesn't move. It's the page's first link, so a screen reader or keyboard
   reaches it first. */
.acc {
	position:absolute;
	top:3px;
	left:8px;
	z-index:1;
	background:#fff;
	color:#2B1B3D;
	border:2px solid #2B1B3D;
	border-radius:999px;
	padding:1px 10px;
	font-size:12px;
	font-weight:800;
	text-decoration:none;
}
.acc:focus-visible {
	outline:3px solid #8E5BFF;
	outline-offset:2px;
}
.ar {
	position:absolute;
	top:50%;
	transform:translateY(-50%);
	width:34px;
	height:34px;
	padding:0 0 3px;
	border-radius:50%;
	border:2px solid #2B1B3D;
	background:#fff;
	color:#2B1B3D;
	font:800 24px/1 system-ui,sans-serif;
	display:flex;
	align-items:center;
	justify-content:center;
	box-shadow:2px 2px 0 #2B1B3D;
	cursor:pointer;
}
.ar.l {
	left:8px;
}
.ar.r {
	right:8px;
}
/* The script below hides an arrow at its end of the strip. */
.ar[hidden] {
	display:none;
}
.ar:focus-visible {
	outline:3px solid #8E5BFF;
	outline-offset:2px;
}

/* The card's text: the name and date on one line, then the description. */
.i {
	padding:14px;
}
.t {
	display:flex;
	justify-content:space-between;
	align-items:baseline;
	gap:8px;
	font-weight:800;
	font-size:17px;
}
.d {
	color:#6B5A7A;
	font-size:12px;
	font-weight:600;
	white-space:nowrap;
}
.pd {
	color:#4A3A5C;
	font-size:13px;
	line-height:1.45;
	margin-top:6px;
}
.pd a {
	color:#5B2FD6;
}
/* Lists in the owner's descriptions: indented, since the reset above removed their padding. */
.sd ol,
.sd ul,
.pd ol,
.pd ul {
	padding-left:1.4em;
	margin:4px 0;
}

/* The download buttons, sharing the card's width. A button looks pressed while touched: it
   moves onto its own shadow. */
.a {
	display:flex;
	gap:10px;
	margin-top:12px;
}
.b {
	flex:1;
	padding:10px 8px;
	border:3px solid #2B1B3D;
	border-radius:12px;
	text-align:center;
	text-decoration:none;
	font-size:14px;
	font-weight:800;
	box-shadow:3px 3px 0 #2B1B3D;
}
.b:active {
	transform:translate(3px,3px);
	box-shadow:none;
}
.b:focus-visible {
	outline:3px solid #8E5BFF;
	outline-offset:2px;
}
.b1 {
	background:var(--accent,#FF9E2C);
	color:var(--accent-ink,#2B1B3D);
}
.b2 {
	background:#fff;
	color:#5B2FD6;
}
/* Each download's size, under its label. */
small {
	display:block;
	font-weight:600;
	font-size:11px;
	margin-top:2px;
}
/* The APK's facts, under the buttons: its version, the Android it needs, and which phones it's for. */
.facts {
	color:#6B5A7A;
	font-size:11px;
	font-weight:600;
	margin-top:8px;
}
/* What's new in a product, under its description. */
.new {
	color:#4A3A5C;
	font-size:13px;
	margin-top:6px;
}
/* The owner's announcement: a card of its own, above the products. */
.news {
	margin:18px 16px 0;
	padding:10px 12px;
	background:#fff;
	border:3px solid #2B1B3D;
	border-radius:12px;
	box-shadow:3px 3px 0 #2B1B3D;
	font-weight:700;
	font-size:14px;
}
/* The foot of the page: how to contact the owner, and what made the store. */
.f {
	padding:0 16px 26px;
	text-align:center;
	color:#4A3A5C;
	font-size:12px;
}
.f a {
	color:#5B2FD6;
}
.f p {
	margin:4px 0;
}
</style>{% endblock %}
{# The owner's accent colour, if any, for the download button: checked by the plugin, so it's only
   ever a #rrggbb colour, with black or white text on it. #}
{% if accent is not empty %}<style>
:root {
	--accent:{{accent}};
	--accent-ink:{{accentInk}};
}
</style>{% endif %}
</head>
<body>
{# The accessible version, first on the page. It's in its own small region, so screen readers
   list it among the page's regions. #}
{% if accessibleHref is not empty %}<nav class=acc-nav aria-label="Page versions"><a class=acc href="{{accessibleHref}}">Accessible version</a></nav>{% endif %}
{# The awning is decoration only, so screen readers skip it. #}
<div class=aw aria-hidden=true></div>

{# The header. The octopus arrives as headerImage when the owner hasn't chosen an image. A video
   header plays by itself, silently and in a loop, like an animated picture. #}
{% block header %}<header>
  {% if headerImage.type=='video' %}<video src="{{headerImage.src}}"{% if headerImage.poster %} poster="{{headerImage.poster}}"{% endif %} controls preload="none" muted loop playsinline></video>{% else %}<img src="{{headerImage.src}}" alt="">{% endif %}
  <div class=ht>
    <h1>{{storeName}}</h1>
    {% if storeDescriptionHtml is not empty %}<div class=sd>{{storeDescriptionHtml|raw}}</div>{% endif %}
  </div>
</header>{% endblock %}

{# The owner's announcement, if any, above the products. #}
{% block announcement %}{% if announcement is not empty %}<p class=news role=region aria-label="Announcement">{{announcement}}</p>{% endif %}{% endblock %}

{# The products, one card each, in Manage Catalog's order. They're the page's main region, which
   screen readers can jump straight to. #}
<main class=n>
  {% for p in products %}{% block card %}<div class=p>

    {# The picture strip, if the product has any pictures, videos or sounds. It can take keyboard
       focus, so it can be scrolled with the arrow keys, and screen readers announce its label. #}
    {% if p.carousel is not empty %}
    <div class=cw>
      <div class=c tabindex=0 role=region aria-label="{{p.name}}: pictures">
        {% for m in p.carousel %}
        <div>
          {# Videos show their poster until played, and never preload: preloading would tie up
             the server's single thread. Each item is labelled with its product and position, such
             as "Termux, picture 1 of 3": the catalog has no descriptions of what pictures show. #}
          {% if m.type=='video' %}<video src="{{m.src}}" controls preload="none"{% if m.poster is not empty %} poster="{{m.poster}}"{% endif %} {% if m.width is not empty %}width="{{m.width}}" height="{{m.height}}" {% endif %}aria-label="{{m.alt}}">{% if m.captions is not empty %}<track kind="captions" src="{{m.captions}}" srclang="{{lang}}" label="Captions" default>{% endif %}</video>
          {% elseif m.type=='audio' %}<audio src="{{m.src}}" controls style="width:100%;margin-top:60px" aria-label="{{m.alt}}"></audio>
          {% else %}<img src="{{m.src}}" {% if m.width is not empty %}width="{{m.width}}" height="{{m.height}}" {% endif %}alt="{{m.alt}}">{% endif %}
        </div>
        {% endfor %}
      </div>
      {# Arrows only when there's more than one item. The left one starts hidden, at the first
         item; the script shows and hides both as the strip moves. #}
      {% if p.carousel|length > 1 %}
      <button type=button class="ar l" aria-label="Previous picture" hidden>&#8249;</button>
      <button type=button class="ar r" aria-label="Next picture">&#8250;</button>
      {% endif %}
    </div>
    {% endif %}

    {# The name, as a heading, with the date — in a <time> element, which screen readers and other
       tools understand — then the description, then whichever downloads the product offers, and
       what the plugin could read from the APK. Parts it couldn't read are simply left out. #}
    <div class=i>
      <div class=t><h2>{{p.name}}</h2><span class=d>{% if p.dateAddedIso is not empty %}<time datetime="{{p.dateAddedIso}}">{{p.dateAddedLabel}}</time>{% else %}{{p.dateAddedLabel}}{% endif %}</span></div>
      {% if p.descriptionHtml is not empty %}<div class=pd>{{p.descriptionHtml|raw}}</div>{% endif %}
      {% if p.whatsNew is not empty %}<p class=new><b>What's new:</b> {{p.whatsNew}}</p>{% endif %}
      {% block downloads %}<div class=a>
        {% if p.sourceZip is not empty %}<a class="b b2" href="{{p.sourceZip.src}}">Download source<small>{{p.sourceZip.sizeLabel}}</small></a>{% endif %}
        {% if p.apk is not empty %}<a class="b b1" href="{{p.apk.src}}">Download APK<small>{{p.apk.sizeLabel}}</small></a>{% endif %}
      </div>{% endblock %}
      {% if p.apk is not empty %}<p class=facts>{% if p.apk.versionName is not empty %}Version {{p.apk.versionName}} &middot; {% endif %}{% if p.apk.androidLabel is not empty %}{{p.apk.androidLabel}} &middot; {% endif %}For {{p.apk.abiLabel}}</p>{% endif %}
      {% if p.author is not empty or p.license is not empty or p.websiteHref is not empty %}<p class=facts>{% if p.author is not empty %}By {{p.author}}{% endif %}{% if p.license is not empty %}{% if p.author is not empty %} &middot; {% endif %}License: {{p.license}}{% endif %}{% if p.websiteHref is not empty %}{% if p.author is not empty or p.license is not empty %} &middot; {% endif %}<a href="{{p.websiteHref}}">Website</a>{% endif %}</p>{% endif %}
    </div>
  </div>{% endblock %}{% endfor %}
</main>

{# The foot: the owner's contact details, each a link only when it's a real address, and the version. #}
{% block footer %}<footer class=f>
  {% if contact is not empty %}<p class=contact>Questions?{% if contact.name is not empty %} Ask {{contact.name}}{% endif %}{% if contact.emailHref is not empty or contact.phoneHref is not empty or contact.urlHref is not empty %}:{% endif %}{% if contact.emailHref is not empty %} <a href="{{contact.emailHref}}">{{contact.email}}</a>{% endif %}{% if contact.phoneHref is not empty %}{% if contact.emailHref is not empty %} &middot;{% endif %} <a href="{{contact.phoneHref}}">{{contact.phone}}</a>{% endif %}{% if contact.urlHref is not empty %}{% if contact.emailHref is not empty or contact.phoneHref is not empty %} &middot;{% endif %} <a href="{{contact.urlHref}}">website</a>{% endif %}</p>{% endif %}
  <p class=made>Made with <a href="https://www.appdevforall.org/plugins" style="color:inherit">Share Your Apps{% if shareYourAppsVersion is not empty %} {{shareYourAppsVersion}}{% endif %}</a></p>
</footer>{% endblock %}

<script>
/* Each picture strip with more than one item gets arrows: hidden at its ends, and tapping one
   moves a picture. Without this script the page still works: strips can be swiped, and the
   right arrow still shows there's more. */
document.querySelectorAll(".cw").forEach(function(w){
  var c=w.querySelector(".c"),l=w.querySelector(".l"),r=w.querySelector(".r");
  if(!l||!r)return;                       /* one item: no arrows */
  function u(){                           /* hide an arrow within 4 px of its end */
    var end=c.scrollWidth-c.clientWidth;
    l.hidden=c.scrollLeft<=4;
    r.hidden=c.scrollLeft>=end-4
  }
  c.addEventListener("scroll",u,{passive:true});   /* as the strip is swiped */
  window.addEventListener("resize",u);             /* and when the phone is rotated */
  l.addEventListener("click",function(){c.scrollBy({left:-c.clientWidth,behavior:"smooth"})});
  r.addEventListener("click",function(){c.scrollBy({left:c.clientWidth,behavior:"smooth"})});
  u()
});
</script>
</body>
</html>
```

## C.2 The default template, as shipped (`default-catalog-template.peb`)

```
<!doctype html><html lang="{{lang}}"><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1"><title>{{storeName}}</title>{% block styles %}<style>*{box-sizing:border-box;margin:0;padding:0}body{background:#FFF3D6;color:#2B1B3D;font-family:ui-rounded,"SF Pro Rounded",system-ui,sans-serif}.aw{height:26px;background:radial-gradient(circle at 12px 14px,#FF4F3A 11.5px,transparent 12px) 0 0/48px 26px repeat-x,radial-gradient(circle at 36px 14px,#FFF3D6 11.5px,transparent 12px) 0 0/48px 26px repeat-x,repeating-linear-gradient(90deg,#FF4F3A 0 24px,#FFF3D6 24px 48px) 0 0/100% 14px no-repeat,#0B6E6E}header{background:#0B6E6E;color:#fff;padding:6px 16px 20px;display:flex;align-items:center;gap:14px}header img,header video{width:64px;height:64px;border-radius:16px;border:3px solid #2B1B3D;background:#fff;object-fit:cover;flex-shrink:0}header .ht{display:flex;flex-direction:column;gap:4px;min-width:0}h1{font-size:22px;font-weight:800;overflow-wrap:anywhere}.sd{font-size:13px;color:#FFF3D6;line-height:1.4}.sd a{color:#FFE14D}.n{columns:260px;column-gap:20px;padding:22px 16px 10px}.p{background:#fff;border:3px solid #2B1B3D;border-radius:16px;overflow:hidden;box-shadow:5px 5px 0 #2B1B3D;break-inside:avoid;display:inline-block;width:100%;margin-bottom:20px}.c{display:flex;overflow-x:auto;overflow-y:hidden;scroll-snap-type:x mandatory;border-bottom:3px solid #2B1B3D}.c>div{flex:0 0 100%;scroll-snap-align:start;height:160px;background:#E3F2EF}.c img,.c video{display:block;box-sizing:border-box;width:100%;height:160px;padding:1px 0;object-fit:contain}.cw{position:relative}.c:focus-visible{outline:3px solid #8E5BFF;outline-offset:-3px}.t h2{font:inherit;margin:0}.acc{position:absolute;top:3px;left:8px;z-index:1;background:#fff;color:#2B1B3D;border:2px solid #2B1B3D;border-radius:999px;padding:1px 10px;font-size:12px;font-weight:800;text-decoration:none}.acc:focus-visible{outline:3px solid #8E5BFF;outline-offset:2px}.ar{position:absolute;top:50%;transform:translateY(-50%);width:34px;height:34px;padding:0 0 3px;border-radius:50%;border:2px solid #2B1B3D;background:#fff;color:#2B1B3D;font:800 24px/1 system-ui,sans-serif;display:flex;align-items:center;justify-content:center;box-shadow:2px 2px 0 #2B1B3D;cursor:pointer}.ar.l{left:8px}.ar.r{right:8px}.ar[hidden]{display:none}.ar:focus-visible{outline:3px solid #8E5BFF;outline-offset:2px}.i{padding:14px}.t{display:flex;justify-content:space-between;align-items:baseline;gap:8px;font-weight:800;font-size:17px}.d{color:#6B5A7A;font-size:12px;font-weight:600;white-space:nowrap}.pd{color:#4A3A5C;font-size:13px;line-height:1.45;margin-top:6px}.pd a{color:#5B2FD6}.sd ol,.sd ul,.pd ol,.pd ul{padding-left:1.4em;margin:4px 0}.a{display:flex;gap:10px;margin-top:12px}.b{flex:1;padding:10px 8px;border:3px solid #2B1B3D;border-radius:12px;text-align:center;text-decoration:none;font-size:14px;font-weight:800;box-shadow:3px 3px 0 #2B1B3D}.b:active{transform:translate(3px,3px);box-shadow:none}.b:focus-visible{outline:3px solid #8E5BFF;outline-offset:2px}.b1{background:var(--accent,#FF9E2C);color:var(--accent-ink,#2B1B3D)}.b2{background:#fff;color:#5B2FD6}small{display:block;font-weight:600;font-size:11px;margin-top:2px}.facts{color:#6B5A7A;font-size:11px;font-weight:600;margin-top:8px}.new{color:#4A3A5C;font-size:13px;margin-top:6px}.news{margin:18px 16px 0;padding:10px 12px;background:#fff;border:3px solid #2B1B3D;border-radius:12px;box-shadow:3px 3px 0 #2B1B3D;font-weight:700;font-size:14px}.f{padding:0 16px 26px;text-align:center;color:#4A3A5C;font-size:12px}.f a{color:#5B2FD6}.f p{margin:4px 0}</style>{% endblock %}{% if accent is not empty %}<style>:root{--accent:{{accent}};--accent-ink:{{accentInk}}}</style>{% endif %}</head><body>{% if accessibleHref is not empty %}<nav class=acc-nav aria-label="Page versions"><a class=acc href="{{accessibleHref}}">Accessible version</a></nav>{% endif %}<div class=aw aria-hidden=true></div>{% block header %}<header>{% if headerImage.type=='video' %}<video src="{{headerImage.src}}"{% if headerImage.poster %} poster="{{headerImage.poster}}"{% endif %} controls preload="none" muted loop playsinline></video>{% else %}<img src="{{headerImage.src}}" alt="">{% endif %}<div class=ht><h1>{{storeName}}</h1>{% if storeDescriptionHtml is not empty %}<div class=sd>{{storeDescriptionHtml|raw}}</div>{% endif %}</div></header>{% endblock %}{% block announcement %}{% if announcement is not empty %}<p class=news role=region aria-label="Announcement">{{announcement}}</p>{% endif %}{% endblock %}<main class=n>{% for p in products %}{% block card %}<div class=p>{% if p.carousel is not empty %}<div class=cw><div class=c tabindex=0 role=region aria-label="{{p.name}}: pictures">{% for m in p.carousel %}<div>{% if m.type=='video' %}<video src="{{m.src}}" controls preload="none"{% if m.poster is not empty %} poster="{{m.poster}}"{% endif %} {% if m.width is not empty %}width="{{m.width}}" height="{{m.height}}" {% endif %}aria-label="{{m.alt}}">{% if m.captions is not empty %}<track kind="captions" src="{{m.captions}}" srclang="{{lang}}" label="Captions" default>{% endif %}</video>{% elseif m.type=='audio' %}<audio src="{{m.src}}" controls style="width:100%;margin-top:60px" aria-label="{{m.alt}}"></audio>{% else %}<img src="{{m.src}}" {% if m.width is not empty %}width="{{m.width}}" height="{{m.height}}" {% endif %}alt="{{m.alt}}">{% endif %}</div>{% endfor %}</div>{% if p.carousel|length > 1 %}<button type=button class="ar l" aria-label="Previous picture" hidden>&#8249;</button><button type=button class="ar r" aria-label="Next picture">&#8250;</button>{% endif %}</div>{% endif %}<div class=i><div class=t><h2>{{p.name}}</h2><span class=d>{% if p.dateAddedIso is not empty %}<time datetime="{{p.dateAddedIso}}">{{p.dateAddedLabel}}</time>{% else %}{{p.dateAddedLabel}}{% endif %}</span></div>{% if p.descriptionHtml is not empty %}<div class=pd>{{p.descriptionHtml|raw}}</div>{% endif %}{% if p.whatsNew is not empty %}<p class=new><b>What's new:</b> {{p.whatsNew}}</p>{% endif %}{% block downloads %}<div class=a>{% if p.sourceZip is not empty %}<a class="b b2" href="{{p.sourceZip.src}}">Download source<small>{{p.sourceZip.sizeLabel}}</small></a>{% endif %}{% if p.apk is not empty %}<a class="b b1" href="{{p.apk.src}}">Download APK<small>{{p.apk.sizeLabel}}</small></a>{% endif %}</div>{% endblock %}{% if p.apk is not empty %}<p class=facts>{% if p.apk.versionName is not empty %}Version {{p.apk.versionName}} &middot; {% endif %}{% if p.apk.androidLabel is not empty %}{{p.apk.androidLabel}} &middot; {% endif %}For {{p.apk.abiLabel}}</p>{% endif %}{% if p.author is not empty or p.license is not empty or p.websiteHref is not empty %}<p class=facts>{% if p.author is not empty %}By {{p.author}}{% endif %}{% if p.license is not empty %}{% if p.author is not empty %} &middot; {% endif %}License: {{p.license}}{% endif %}{% if p.websiteHref is not empty %}{% if p.author is not empty or p.license is not empty %} &middot; {% endif %}<a href="{{p.websiteHref}}">Website</a>{% endif %}</p>{% endif %}</div></div>{% endblock %}{% endfor %}</main>{% block footer %}<footer class=f>{% if contact is not empty %}<p class=contact>Questions?{% if contact.name is not empty %} Ask {{contact.name}}{% endif %}{% if contact.emailHref is not empty or contact.phoneHref is not empty or contact.urlHref is not empty %}:{% endif %}{% if contact.emailHref is not empty %} <a href="{{contact.emailHref}}">{{contact.email}}</a>{% endif %}{% if contact.phoneHref is not empty %}{% if contact.emailHref is not empty %} &middot;{% endif %} <a href="{{contact.phoneHref}}">{{contact.phone}}</a>{% endif %}{% if contact.urlHref is not empty %}{% if contact.emailHref is not empty or contact.phoneHref is not empty %} &middot;{% endif %} <a href="{{contact.urlHref}}">website</a>{% endif %}</p>{% endif %}<p class=made>Made with <a href="https://www.appdevforall.org/plugins" style="color:inherit">Share Your Apps{% if shareYourAppsVersion is not empty %} {{shareYourAppsVersion}}{% endif %}</a></p></footer>{% endblock %}<script>/* Each picture strip with more than one item gets arrows: hidden at its ends, and tapping one moves a picture. */document.querySelectorAll(".cw").forEach(function(w){var c=w.querySelector(".c"),l=w.querySelector(".l"),r=w.querySelector(".r");if(!l||!r)return;function u(){var end=c.scrollWidth-c.clientWidth;l.hidden=c.scrollLeft<=4;r.hidden=c.scrollLeft>=end-4}c.addEventListener("scroll",u,{passive:true});window.addEventListener("resize",u);l.addEventListener("click",function(){c.scrollBy({left:-c.clientWidth,behavior:"smooth"})});r.addEventListener("click",function(){c.scrollBy({left:c.clientWidth,behavior:"smooth"})});u()});</script></body></html>
```

## C.3 Neon Arcade (`theme-neon-arcade.peb`)

A dark synthwave arcade. The store's name is neon lettering that flickers now and then, over a striped sunset and a grid floor that runs toward the viewer. Products are cabinets whose glowing borders cycle through pink, cyan and yellow, with their pictures on a scanlined CRT screen and a count of pictures in its corner. The buttons are chunky and glowing. The colour cycling uses a CSS `@property` animation; browsers without it show a still border.

```
<!doctype html><html lang="{{lang}}"><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1"><title>{{storeName}}</title><style>
:root{--bg:#0b0221;--pink:#ff2bd6;--cyan:#26f7ff;--yellow:#ffe45e;--ink:#f3e8ff}
*{box-sizing:border-box}html{background:var(--bg)}
body{margin:0;color:var(--ink);font:15px/1.5 ui-monospace,"DejaVu Sans Mono",Menlo,Consolas,monospace;overflow-x:hidden;min-height:100vh}
.sun{position:fixed;left:50%;top:14vh;width:280px;height:280px;margin-left:-140px;border-radius:50%;z-index:-2;background:linear-gradient(var(--yellow),#ff8a3d 45%,var(--pink) 75%);-webkit-mask:linear-gradient(#000 0 52%,transparent 0),repeating-linear-gradient(#000 0 12px,transparent 0 18px);mask:linear-gradient(#000 0 52%,transparent 0),repeating-linear-gradient(#000 0 12px,transparent 0 18px)}
.floor{position:fixed;left:-60%;right:-60%;bottom:0;height:46vh;z-index:-1;background:linear-gradient(transparent 0 92%,rgba(255,43,214,.85) 0) 0 0/100% 44px,linear-gradient(90deg,transparent 0 95%,rgba(38,247,255,.6) 0) 0 0/64px 100%,linear-gradient(#20063f,var(--bg));transform:perspective(320px) rotateX(64deg);transform-origin:bottom;animation:run 1.1s linear infinite}
@keyframes run{to{background-position:0 44px,0 0,0 0}}
header{text-align:center;padding:38px 16px 18px}
header img,header video{width:88px;height:88px;object-fit:contain;filter:drop-shadow(0 0 14px var(--pink))}
h1{margin:12px 0 8px;font-size:32px;line-height:1.05;text-transform:uppercase;letter-spacing:.08em;color:#fff;text-shadow:0 0 4px #fff,0 0 12px var(--pink),0 0 26px var(--pink),0 0 44px var(--cyan);animation:flicker 6s infinite}
@keyframes flicker{0%,17%,21%,61%,64%,100%{opacity:1}19%,62%{opacity:.5}}
.sd,.start{max-width:34em;margin:0 auto;padding:8px 12px;border-radius:10px;background:rgba(11,2,33,.72);color:#e4d8ff;font-size:13px}.sd p{margin:.4em 0}.sd a,.pd a{color:var(--cyan)}
.start{display:inline-block;margin-top:12px;color:var(--yellow);font-size:12px;letter-spacing:.35em;animation:pulse 1.4s ease-in-out infinite}
@keyframes pulse{50%{text-shadow:0 0 10px var(--yellow),0 0 20px var(--pink)}}
main{display:grid;gap:26px;padding:8px 14px 50px;max-width:1120px;margin:0 auto;grid-template-columns:repeat(auto-fill,minmax(min(100%,300px),1fr));align-items:start}
@property --a{syntax:'<angle>';inherits:false;initial-value:0deg}
.cab{border-radius:18px;padding:3px;background:conic-gradient(from var(--a),var(--pink),var(--cyan),var(--yellow),var(--pink));animation:spin 7s linear infinite;box-shadow:0 0 24px rgba(255,43,214,.35)}
@keyframes spin{to{--a:360deg}}
.in{background:rgba(11,2,33,.94);border-radius:15px;padding:14px}
.top{display:flex;justify-content:space-between;gap:8px;font-size:11px;color:var(--yellow);letter-spacing:.18em;text-transform:uppercase}
h2{margin:4px 0 10px;font-size:19px;text-transform:uppercase;letter-spacing:.06em;color:#fff;text-shadow:0 0 10px var(--cyan)}
.scr{position:relative;border-radius:12px;overflow:hidden;background:#000;box-shadow:inset 0 0 34px rgba(38,247,255,.35),0 0 0 2px #2a0f55}
.scr::after{content:"";position:absolute;inset:0;pointer-events:none;background:repeating-linear-gradient(rgba(255,255,255,.07) 0 1px,transparent 1px 3px)}
.strip{display:flex;overflow-x:auto;overflow-y:hidden;scroll-snap-type:x mandatory}
.strip>*{flex:0 0 100%;scroll-snap-align:start;display:block;width:100%;height:190px;padding:1px 0;object-fit:contain}
.strip audio{height:40px;margin:74px 0}
.lives{position:absolute;right:8px;bottom:8px;z-index:1;font-size:10px;color:var(--yellow);background:rgba(0,0,0,.6);padding:1px 7px;border-radius:4px;letter-spacing:.15em}
.pd{margin:12px 0;color:#e2d6ff;font-size:13px}.pd p{margin:.4em 0}
.a{display:flex;gap:10px;flex-wrap:wrap}
.b{flex:1 1 130px;text-align:center;text-decoration:none;font-weight:800;letter-spacing:.08em;text-transform:uppercase;padding:11px 8px;border-radius:10px;font-size:13px}
.b:active{transform:translateY(2px)}
.b1{background:var(--accent,var(--pink));color:var(--accent-ink,#1a0033);box-shadow:0 4px 0 #8a0f74,0 0 18px var(--pink)}
.b2{color:var(--cyan);border:2px solid var(--cyan);box-shadow:inset 0 0 12px rgba(38,247,255,.45),0 0 10px rgba(38,247,255,.35)}
.b small{display:block;font-size:10px;opacity:.85;letter-spacing:.15em}
/* Full strength on the accent: accentInk only guarantees its contrast undimmed. */
.b1 small{opacity:1}
.acc{position:absolute;top:8px;left:10px;z-index:2;color:var(--cyan);background:rgba(11,2,33,.9);border:2px solid var(--cyan);border-radius:8px;padding:2px 10px;font-size:12px;letter-spacing:.1em;text-transform:uppercase;text-decoration:none;box-shadow:0 0 10px rgba(38,247,255,.45)}
:focus-visible{outline:3px solid var(--yellow);outline-offset:3px}
.facts{margin:10px 0 0;color:#d6c8ff;font-size:11px;letter-spacing:.06em}
.new{color:#e2d6ff;font-size:13px;margin:0 0 12px}
.news{max-width:34em;margin:0 auto 20px;padding:8px 12px;border:2px solid var(--yellow);border-radius:10px;background:rgba(11,2,33,.9);color:var(--yellow);text-align:center;font-size:13px}
footer p{margin:6px 0}.contact,.made{text-transform:none;letter-spacing:.04em}footer a{color:var(--cyan)}
footer{text-align:center;color:#a58fd6;font-size:11px;letter-spacing:.3em;padding:0 0 40px;text-transform:uppercase}
@media (prefers-reduced-motion:reduce){*{animation:none!important}}
</style>{% if accent is not empty %}<style>:root{--accent:{{accent}};--accent-ink:{{accentInk}}}</style>{% endif %}</head><body>{% if accessibleHref is not empty %}<nav class=acc-nav aria-label="Page versions"><a class=acc href="{{accessibleHref}}">Accessible version</a></nav>{% endif %}<div class=sun aria-hidden=true></div><div class=floor aria-hidden=true></div>
<header>{% if headerImage.type=='video' %}<video src="{{headerImage.src}}"{% if headerImage.poster %} poster="{{headerImage.poster}}"{% endif %} controls preload="none" muted loop playsinline></video>{% else %}<img src="{{headerImage.src}}" alt="">{% endif %}<h1>{{storeName}}</h1>{% if storeDescriptionHtml is not empty %}<div class=sd>{{storeDescriptionHtml|raw}}</div>{% endif %}<p class=start aria-hidden=true>&#9660; Select your app &#9660;</p></header>
{% if announcement is not empty %}<p class=news role=region aria-label="Announcement">{{announcement}}</p>{% endif %}<main>{% for p in products %}<section class=cab><div class=in><div class=top><span>No. {{p.ordinal}}</span><span>{% if p.dateAddedIso is not empty %}<time datetime="{{p.dateAddedIso}}">{{p.dateAddedLabel}}</time>{% else %}{{p.dateAddedLabel}}{% endif %}</span></div><h2>{{p.name}}</h2>{% if p.carousel is not empty %}<div class=scr><div class=strip tabindex=0 role=region aria-label="{{p.name}}: pictures">{% for m in p.carousel %}{% if m.type=='video' %}<video src="{{m.src}}" controls preload="none"{% if m.poster is not empty %} poster="{{m.poster}}"{% endif %} {% if m.width is not empty %}width="{{m.width}}" height="{{m.height}}" {% endif %}aria-label="{{m.alt}}">{% if m.captions is not empty %}<track kind="captions" src="{{m.captions}}" srclang="{{lang}}" label="Captions" default>{% endif %}</video>{% elseif m.type=='audio' %}<audio src="{{m.src}}" controls aria-label="{{m.alt}}"></audio>{% else %}<img src="{{m.src}}" {% if m.width is not empty %}width="{{m.width}}" height="{{m.height}}" {% endif %}alt="{{m.alt}}" loading="lazy">{% endif %}{% endfor %}</div>{% if p.carousel|length > 1 %}<span class=lives aria-hidden=true>&#9664; {{p.carousel|length}} &#9654;</span>{% endif %}</div>{% endif %}{% if p.descriptionHtml is not empty %}<div class=pd>{{p.descriptionHtml|raw}}</div>{% endif %}{% if p.whatsNew is not empty %}<p class=new><b>What's new:</b> {{p.whatsNew}}</p>{% endif %}<div class=a>{% if p.apk is not empty %}<a class="b b1" href="{{p.apk.src}}">Download APK<small>{{p.apk.sizeLabel}}</small></a>{% endif %}{% if p.sourceZip is not empty %}<a class="b b2" href="{{p.sourceZip.src}}">Download source<small>{{p.sourceZip.sizeLabel}}</small></a>{% endif %}</div>{% if p.apk is not empty %}<p class=facts>{% if p.apk.versionName is not empty %}Version {{p.apk.versionName}} &middot; {% endif %}{% if p.apk.androidLabel is not empty %}{{p.apk.androidLabel}} &middot; {% endif %}For {{p.apk.abiLabel}}</p>{% endif %}{% if p.author is not empty or p.license is not empty or p.websiteHref is not empty %}<p class=facts>{% if p.author is not empty %}By {{p.author}}{% endif %}{% if p.license is not empty %}{% if p.author is not empty %} &middot; {% endif %}License: {{p.license}}{% endif %}{% if p.websiteHref is not empty %}{% if p.author is not empty or p.license is not empty %} &middot; {% endif %}<a href="{{p.websiteHref}}">Website</a>{% endif %}</p>{% endif %}</div></section>{% endfor %}</main>
<footer><p>{{products|length}} apps on the floor</p>{% if contact is not empty %}<p class=contact>Questions?{% if contact.name is not empty %} Ask {{contact.name}}{% endif %}{% if contact.emailHref is not empty or contact.phoneHref is not empty or contact.urlHref is not empty %}:{% endif %}{% if contact.emailHref is not empty %} <a href="{{contact.emailHref}}">{{contact.email}}</a>{% endif %}{% if contact.phoneHref is not empty %}{% if contact.emailHref is not empty %} &middot;{% endif %} <a href="{{contact.phoneHref}}">{{contact.phone}}</a>{% endif %}{% if contact.urlHref is not empty %}{% if contact.emailHref is not empty or contact.phoneHref is not empty %} &middot;{% endif %} <a href="{{contact.urlHref}}">website</a>{% endif %}</p>{% endif %}<p class=made>Made with <a href="https://www.appdevforall.org/plugins" style="color:inherit">Share Your Apps{% if shareYourAppsVersion is not empty %} {{shareYourAppsVersion}}{% endif %}</a></p></footer></body></html>
```

## C.4 Morning Edition (`theme-morning-edition.peb`)

A newspaper. A ticker of product names scrolls across the top, above a masthead with a motto and a dateline. Products are articles in newspaper columns, each with a red drop cap, and pictures appear as grey photographs that take on colour when touched. The downloads are coupons, with dashed edges and scissors. On a phone set to dark mode, it becomes a dark *Night Edition*.

```
<!doctype html><html lang="{{lang}}"><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1"><title>{{storeName}}</title><style>
:root{--paper:#f4efe3;--ink:#1d1a16;--muted:#5f574c;--red:#a6231b;--hair:rgba(29,26,22,.28)}
@media (prefers-color-scheme:dark){:root{--paper:#17150f;--ink:#efe8da;--muted:#a89f90;--red:#ff7a6b;--hair:rgba(239,232,218,.25)}.mast img{filter:grayscale(1) invert(1)}}
*{box-sizing:border-box}
body{margin:0;background:var(--paper) radial-gradient(var(--hair) .5px,transparent .9px) 0 0/5px 5px;color:var(--ink);font:16px/1.55 Georgia,"Times New Roman","DejaVu Serif",serif}
.page{max-width:1120px;margin:0 auto;padding:14px 16px 30px}
.ticker{overflow:hidden;white-space:nowrap;border-block:1px solid var(--ink);font:700 11px/26px Arial,"DejaVu Sans",sans-serif;text-transform:uppercase;letter-spacing:.12em}
.ticker div{display:inline-block;padding-left:100%;animation:tick 40s linear infinite}
.ticker span::before{content:"\25C6";color:var(--red);margin:0 14px}
@keyframes tick{to{transform:translateX(-100%)}}
.mast{text-align:center;padding:16px 0 8px;border-bottom:5px double var(--ink)}
.mast img,.mast video{width:58px;height:58px;object-fit:contain;filter:grayscale(1) contrast(1.3)}
h1{margin:2px 0 0;font:900 clamp(36px,12vw,84px)/.92 "Times New Roman","DejaVu Serif",Georgia,serif;letter-spacing:-.03em}
.motto{font-style:italic;color:var(--muted);font-size:14px;margin:6px 0 0}
.dateline{display:flex;justify-content:space-between;gap:10px;border-bottom:1px solid var(--ink);padding:6px 0;font:11px Arial,"DejaVu Sans",sans-serif;text-transform:uppercase;letter-spacing:.12em}
.lede{font-size:18px;line-height:1.5;margin:16px 0 4px;padding-bottom:14px;border-bottom:1px solid var(--hair)}
.lede p{margin:.3em 0}.lede::first-letter,.pd::first-letter{float:left;font:900 3.3em/.78 Georgia,serif;padding:5px 3px 0 0;color:var(--red)}
.cols{columns:17em;column-gap:30px;column-rule:1px solid var(--hair);margin-top:18px}
article{break-inside:avoid;display:inline-block;width:100%;margin:0 0 22px;padding-bottom:18px;border-bottom:3px double var(--hair);animation:ink .9s both;animation-delay:calc(min(var(--i),6)*90ms)}
@keyframes ink{from{opacity:0;filter:blur(3px);transform:translateY(8px)}}
.kicker{font:700 11px Arial,"DejaVu Sans",sans-serif;color:var(--red);text-transform:uppercase;letter-spacing:.14em}
h2{font:800 27px/1.05 Georgia,"DejaVu Serif",serif;margin:4px 0 4px}
.by{font:italic 13px Georgia,serif;color:var(--muted)}
figure{margin:12px 0 8px}
.photos{display:flex;gap:8px;overflow-x:auto;overflow-y:hidden;scroll-snap-type:x mandatory}
.photos>*{flex:0 0 100%;scroll-snap-align:start;display:block;width:100%;height:200px;padding:1px 0;object-fit:contain;background:rgba(0,0,0,.06);filter:grayscale(1) contrast(1.15);transition:filter .7s}
.photos>*:hover,.photos>*:active,.photos>*:focus{filter:none}
.photos audio{height:40px;margin:80px 0}
figcaption{font:italic 12px Georgia,serif;color:var(--muted);border-bottom:1px solid var(--hair);padding:4px 0}
.pd{margin-top:8px}.pd p{margin:.4em 0}.pd a,.lede a{color:var(--ink);text-decoration-color:var(--red)}
.clip{display:flex;gap:10px;flex-wrap:wrap;margin-top:14px}
.coupon{flex:1 1 140px;position:relative;border:2px dashed var(--ink);padding:10px 8px;text-align:center;text-decoration:none;color:var(--ink);font:700 12px Arial,"DejaVu Sans",sans-serif;text-transform:uppercase;letter-spacing:.1em}
.coupon::before{content:"\2702"/"";position:absolute;top:-11px;left:10px;background:var(--paper);padding:0 4px;font-size:15px}
.coupon.apk{background:var(--accent,var(--ink));color:var(--accent-ink,var(--paper));border-style:solid}
.coupon.apk::before{color:var(--ink)}
.coupon small{display:block;font-weight:400;letter-spacing:.05em;text-transform:none}
.acc{display:inline-block;margin:0 0 8px;font:700 12px Arial,"DejaVu Sans",sans-serif;text-transform:uppercase;letter-spacing:.12em;color:var(--ink);text-decoration-color:var(--red)}
:focus-visible{outline:3px solid var(--red);outline-offset:3px}
.facts{font:italic 13px Georgia,"DejaVu Serif",serif;color:var(--muted);margin:10px 0 0}
.new{margin:.5em 0}
.news{border:2px solid var(--ink);padding:8px 12px;margin:16px 0 0;font:700 15px Georgia,"DejaVu Serif",serif}
.foot{text-align:center;font:13px Georgia,"DejaVu Serif",serif;color:var(--muted);margin:10px 0}.foot p{margin:4px 0}.foot a{color:var(--ink)}
.end{text-align:center;font:700 13px Arial,sans-serif;letter-spacing:.4em;color:var(--muted)}
@media (prefers-reduced-motion:reduce){*{animation:none!important}.ticker div{padding-left:0}}
</style>{% if accent is not empty %}<style>:root{--accent:{{accent}};--accent-ink:{{accentInk}}}</style>{% endif %}</head><body><div class=page>{% if accessibleHref is not empty %}<nav class=acc-nav aria-label="Page versions"><a class=acc href="{{accessibleHref}}">Accessible version</a></nav>{% endif %}
<div class=ticker aria-hidden=true><div>{% for p in products %}<span>{{p.name}}</span>{% endfor %}</div></div>
<header><div class=mast>{% if headerImage.type=='video' %}<video src="{{headerImage.src}}"{% if headerImage.poster %} poster="{{headerImage.poster}}"{% endif %} controls preload="none" muted loop playsinline></video>{% else %}<img src="{{headerImage.src}}" alt="">{% endif %}<h1>{{storeName}}</h1><p class=motto>All the apps that are fit to install</p></div>
<div class=dateline><span>Vol. I &middot; No. {{products|length}}</span>{% if products is not empty %}<span>{{products[0].dateAddedLabel}}</span>{% endif %}<span>Price: free</span></div>
{% if storeDescriptionHtml is not empty %}<div class=lede>{{storeDescriptionHtml|raw}}</div>{% endif %}</header>
{% if announcement is not empty %}<p class=news role=region aria-label="Announcement"><span class=kicker>Stop press</span> {{announcement}}</p>{% endif %}<main class=cols>{% for p in products %}<article style="--i:{{loop.index}}"><div class=kicker>No. {{p.ordinal}}</div><h2>{{p.name}}</h2><div class=by>Filed {% if p.dateAddedIso is not empty %}<time datetime="{{p.dateAddedIso}}">{{p.dateAddedLabel}}</time>{% else %}{{p.dateAddedLabel}}{% endif %}</div>{% if p.carousel is not empty %}<figure><div class=photos tabindex=0 role=region aria-label="{{p.name}}: pictures">{% for m in p.carousel %}{% if m.type=='video' %}<video src="{{m.src}}" controls preload="none"{% if m.poster is not empty %} poster="{{m.poster}}"{% endif %} {% if m.width is not empty %}width="{{m.width}}" height="{{m.height}}" {% endif %}aria-label="{{m.alt}}">{% if m.captions is not empty %}<track kind="captions" src="{{m.captions}}" srclang="{{lang}}" label="Captions" default>{% endif %}</video>{% elseif m.type=='audio' %}<audio src="{{m.src}}" controls aria-label="{{m.alt}}"></audio>{% else %}<img src="{{m.src}}" {% if m.width is not empty %}width="{{m.width}}" height="{{m.height}}" {% endif %}alt="{{m.alt}}" loading="lazy">{% endif %}{% endfor %}</div><figcaption>{% if p.carousel|length > 1 %}{{p.carousel|length}} pictures &mdash; swipe for more. {% endif %}Touch a picture for colour.</figcaption></figure>{% endif %}{% if p.descriptionHtml is not empty %}<div class=pd>{{p.descriptionHtml|raw}}</div>{% endif %}{% if p.whatsNew is not empty %}<p class=new><b>What's new:</b> {{p.whatsNew}}</p>{% endif %}<div class=clip>{% if p.apk is not empty %}<a class="coupon apk" href="{{p.apk.src}}">Download APK<small>{{p.apk.sizeLabel}}</small></a>{% endif %}{% if p.sourceZip is not empty %}<a class=coupon href="{{p.sourceZip.src}}">Download source<small>{{p.sourceZip.sizeLabel}}</small></a>{% endif %}</div>{% if p.apk is not empty %}<p class=facts>{% if p.apk.versionName is not empty %}Version {{p.apk.versionName}} &middot; {% endif %}{% if p.apk.androidLabel is not empty %}{{p.apk.androidLabel}} &middot; {% endif %}For {{p.apk.abiLabel}}</p>{% endif %}{% if p.author is not empty or p.license is not empty or p.websiteHref is not empty %}<p class=facts>{% if p.author is not empty %}By {{p.author}}{% endif %}{% if p.license is not empty %}{% if p.author is not empty %} &middot; {% endif %}License: {{p.license}}{% endif %}{% if p.websiteHref is not empty %}{% if p.author is not empty or p.license is not empty %} &middot; {% endif %}<a href="{{p.websiteHref}}">Website</a>{% endif %}</p>{% endif %}</article>{% endfor %}</main>
<footer class=foot>{% if contact is not empty %}<p class=contact>Questions?{% if contact.name is not empty %} Ask {{contact.name}}{% endif %}{% if contact.emailHref is not empty or contact.phoneHref is not empty or contact.urlHref is not empty %}:{% endif %}{% if contact.emailHref is not empty %} <a href="{{contact.emailHref}}">{{contact.email}}</a>{% endif %}{% if contact.phoneHref is not empty %}{% if contact.emailHref is not empty %} &middot;{% endif %} <a href="{{contact.phoneHref}}">{{contact.phone}}</a>{% endif %}{% if contact.urlHref is not empty %}{% if contact.emailHref is not empty or contact.phoneHref is not empty %} &middot;{% endif %} <a href="{{contact.urlHref}}">website</a>{% endif %}</p>{% endif %}<p class=made>Made with <a href="https://www.appdevforall.org/plugins" style="color:inherit">Share Your Apps{% if shareYourAppsVersion is not empty %} {{shareYourAppsVersion}}{% endif %}</a></p></footer>
<p class=end aria-hidden=true>&mdash; 30 &mdash;</p></div></body></html>
```

## C.5 Aurora Deck (`theme-aurora-deck.peb`)

Frosted glass over a slowly moving aurora. The store's name shimmers with a travelling gradient. Products are numbered glass cards in a deck that swipes sideways, each with its pictures in a mosaic, the first large and the rest in pairs. On screens 900 pixels wide or more, the deck becomes a grid. The glass uses `backdrop-filter`; without it, cards are still translucent, just not blurred.

```
<!doctype html><html lang="{{lang}}"><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1"><title>{{storeName}}</title><style>
:root{color-scheme:dark;--glass:rgba(255,255,255,.10);--edge:rgba(255,255,255,.26);--ink:#f5f7ff;--soft:#c5cff2;--link:#9ef0ff}
*{box-sizing:border-box}
body{margin:0;min-height:100vh;background:#070b1f;color:var(--ink);font:15px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,"DejaVu Sans",sans-serif;overflow-x:hidden}
.sky{position:fixed;inset:-25%;z-index:-1;filter:blur(70px) saturate(1.5)}
.sky i{position:absolute;width:60vmax;height:60vmax;border-radius:50%;opacity:.75;mix-blend-mode:screen;animation:drift 20s ease-in-out infinite alternate}
.sky i:nth-child(1){background:#00ffa3;left:0;top:5%}
.sky i:nth-child(2){background:#7a5cff;right:0;top:15%;animation-duration:24s;animation-delay:-8s}
.sky i:nth-child(3){background:#00b7ff;left:25%;bottom:0;animation-duration:28s;animation-delay:-14s}
.sky i:nth-child(4){background:#ff4fd8;right:15%;bottom:10%;animation-duration:32s;opacity:.5}
@keyframes drift{to{transform:translate(14vmax,-10vmax) scale(1.3) rotate(45deg)}}
.glass{background:var(--glass);border:1px solid var(--edge);-webkit-backdrop-filter:blur(20px) saturate(1.4);backdrop-filter:blur(20px) saturate(1.4)}
header{margin:20px 16px 14px;padding:20px;border-radius:28px;display:flex;gap:16px;align-items:center}header.glass{background:rgba(8,12,38,.66)}
header img,header video{flex:0 0 64px;width:64px;height:64px;border-radius:18px;object-fit:contain;background:rgba(255,255,255,.12)}
h1{margin:0;font-size:29px;line-height:1.05;font-weight:800;background:linear-gradient(90deg,#fff,#9ef0ff,#c9a8ff,#fff) 0 0/300% 100%;-webkit-background-clip:text;background-clip:text;color:transparent;animation:shine 9s linear infinite}
@keyframes shine{to{background-position:300% 0}}
.sd{color:#e8edff;font-size:13px;margin-top:6px}.sd p{margin:.3em 0}.sd a,.pd a{color:var(--link)}
.bar{display:flex;justify-content:space-between;margin:0 16px 8px;padding:6px 14px;border-radius:999px;background:rgba(8,12,38,.66);font-size:11px;color:#e8edff;letter-spacing:.16em;text-transform:uppercase}
.deck{display:grid;grid-auto-flow:column;grid-auto-columns:min(84%,420px);gap:16px;overflow-x:auto;scroll-snap-type:x mandatory;padding:8px 8% 34px;counter-reset:card;align-items:start}
@media (min-width:900px){.deck{grid-auto-flow:row;grid-template-columns:repeat(auto-fill,minmax(340px,1fr));overflow:visible;padding:8px 24px 40px}.swipe{display:none}}
.card{scroll-snap-align:center;counter-increment:card;position:relative;border-radius:30px;padding:16px;box-shadow:0 24px 50px -24px rgba(0,0,0,.7);background:linear-gradient(160deg,rgba(255,255,255,.18),rgba(255,255,255,.04))}
.t{display:flex;justify-content:space-between;align-items:flex-start;gap:10px}.num::before{content:counter(card,decimal-leading-zero);font:800 40px/1 system-ui,sans-serif;color:transparent;-webkit-text-stroke:1px rgba(255,255,255,.5)}
.mosaic{display:grid;grid-template-columns:1fr 1fr;gap:6px;border-radius:20px;overflow:hidden;margin:0 0 14px}
.mosaic>*{display:block;width:100%;height:104px;padding:1px 0;object-fit:cover;background:rgba(0,0,0,.28)}
.mosaic>:first-child{grid-column:1/-1;height:190px;object-fit:contain}
.mosaic>:last-child:nth-child(even){grid-column:1/-1}
.mosaic audio{grid-column:1/-1;height:44px}
h2{margin:0;font-size:22px;line-height:1.2}
.date{font-size:12px;color:var(--soft);margin-bottom:6px}
.pd{color:#e6eaff;font-size:14px}.pd p{margin:.4em 0}
.a{display:grid;grid-template-columns:1.5fr 1fr;gap:10px;margin-top:14px}.a>:only-child{grid-column:1/-1}
.b{display:flex;flex-direction:column;justify-content:center;min-height:50px;padding:8px 10px;border-radius:999px;text-align:center;text-decoration:none;font-weight:700;font-size:14px;white-space:nowrap;color:var(--ink);border:1px solid var(--edge);background:rgba(255,255,255,.08)}
.b:active{transform:scale(.97)}
.b1{background:var(--accent,linear-gradient(135deg,#00e6a8,#5b8cff 55%,#b45cff));border-color:transparent;color:var(--accent-ink,#061032);box-shadow:0 10px 26px -10px #5b8cff}
.b small{display:block;font-weight:500;font-size:11px;opacity:.85}
/* Full strength on the accent: accentInk only guarantees its contrast undimmed. */
.b1 small{opacity:1}
.acc{display:inline-block;margin:14px 16px 0;padding:6px 14px;border-radius:999px;color:var(--ink);background:rgba(8,12,38,.6);border:1px solid var(--edge);font-size:13px;font-weight:700;text-decoration:none}
:focus-visible{outline:3px solid #9ef0ff;outline-offset:3px}
.facts{color:#e6ecff;font-size:12px;margin:10px 6px 0}
.new{color:#e6eaff;font-size:13px;margin:.4em 0}
.news,.foot{margin:0 16px 12px;padding:10px 14px;border-radius:18px;background:rgba(8,12,38,.66);color:#fff}.news{font-weight:700;font-size:14px}.foot{margin-bottom:28px;font-size:12px;text-align:center}.foot p{margin:3px 0}.foot a{color:var(--link)}
@media (prefers-reduced-motion:reduce){*{animation:none!important}}
</style>{% if accent is not empty %}<style>:root{--accent:{{accent}};--accent-ink:{{accentInk}}}</style>{% endif %}</head><body><div class=sky aria-hidden=true><i></i><i></i><i></i><i></i></div>{% if accessibleHref is not empty %}<nav class=acc-nav aria-label="Page versions"><a class=acc href="{{accessibleHref}}">Accessible version</a></nav>{% endif %}
<header class=glass>{% if headerImage.type=='video' %}<video src="{{headerImage.src}}"{% if headerImage.poster %} poster="{{headerImage.poster}}"{% endif %} controls preload="none" muted loop playsinline></video>{% else %}<img src="{{headerImage.src}}" alt="">{% endif %}<div><h1>{{storeName}}</h1>{% if storeDescriptionHtml is not empty %}<div class=sd>{{storeDescriptionHtml|raw}}</div>{% endif %}</div></header>
{% if announcement is not empty %}<p class=news role=region aria-label="Announcement">{{announcement}}</p>{% endif %}<main aria-label="Apps"><div class=bar><span>{{products|length}} apps</span><span class=swipe>Swipe &rarr;</span></div>
<div class=deck>{% for p in products %}<section class="card glass">{% if p.carousel is not empty %}<div class=mosaic role=group aria-label="{{p.name}}: pictures">{% for m in p.carousel %}{% if m.type=='video' %}<video src="{{m.src}}" controls preload="none"{% if m.poster is not empty %} poster="{{m.poster}}"{% endif %} {% if m.width is not empty %}width="{{m.width}}" height="{{m.height}}" {% endif %}aria-label="{{m.alt}}">{% if m.captions is not empty %}<track kind="captions" src="{{m.captions}}" srclang="{{lang}}" label="Captions" default>{% endif %}</video>{% elseif m.type=='audio' %}<audio src="{{m.src}}" controls aria-label="{{m.alt}}"></audio>{% else %}<img src="{{m.src}}" {% if m.width is not empty %}width="{{m.width}}" height="{{m.height}}" {% endif %}alt="{{m.alt}}" loading="lazy">{% endif %}{% endfor %}</div>{% endif %}<div class=t><div><h2>{{p.name}}</h2><div class=date>{% if p.dateAddedIso is not empty %}<time datetime="{{p.dateAddedIso}}">{{p.dateAddedLabel}}</time>{% else %}{{p.dateAddedLabel}}{% endif %}</div></div><span class=num aria-hidden=true></span></div>{% if p.descriptionHtml is not empty %}<div class=pd>{{p.descriptionHtml|raw}}</div>{% endif %}{% if p.whatsNew is not empty %}<p class=new><b>What's new:</b> {{p.whatsNew}}</p>{% endif %}<div class=a>{% if p.apk is not empty %}<a class="b b1" href="{{p.apk.src}}">Download APK<small>{{p.apk.sizeLabel}}</small></a>{% endif %}{% if p.sourceZip is not empty %}<a class=b href="{{p.sourceZip.src}}">Source<small>{{p.sourceZip.sizeLabel}}</small></a>{% endif %}</div>{% if p.apk is not empty %}<p class=facts>{% if p.apk.versionName is not empty %}Version {{p.apk.versionName}} &middot; {% endif %}{% if p.apk.androidLabel is not empty %}{{p.apk.androidLabel}} &middot; {% endif %}For {{p.apk.abiLabel}}</p>{% endif %}{% if p.author is not empty or p.license is not empty or p.websiteHref is not empty %}<p class=facts>{% if p.author is not empty %}By {{p.author}}{% endif %}{% if p.license is not empty %}{% if p.author is not empty %} &middot; {% endif %}License: {{p.license}}{% endif %}{% if p.websiteHref is not empty %}{% if p.author is not empty or p.license is not empty %} &middot; {% endif %}<a href="{{p.websiteHref}}">Website</a>{% endif %}</p>{% endif %}</section>{% endfor %}</div></main><footer class=foot>{% if contact is not empty %}<p class=contact>Questions?{% if contact.name is not empty %} Ask {{contact.name}}{% endif %}{% if contact.emailHref is not empty or contact.phoneHref is not empty or contact.urlHref is not empty %}:{% endif %}{% if contact.emailHref is not empty %} <a href="{{contact.emailHref}}">{{contact.email}}</a>{% endif %}{% if contact.phoneHref is not empty %}{% if contact.emailHref is not empty %} &middot;{% endif %} <a href="{{contact.phoneHref}}">{{contact.phone}}</a>{% endif %}{% if contact.urlHref is not empty %}{% if contact.emailHref is not empty or contact.phoneHref is not empty %} &middot;{% endif %} <a href="{{contact.urlHref}}">website</a>{% endif %}</p>{% endif %}<p class=made>Made with <a href="https://www.appdevforall.org/plugins" style="color:inherit">Share Your Apps{% if shareYourAppsVersion is not empty %} {{shareYourAppsVersion}}{% endif %}</a></p></footer></body></html>
```

## C.6 Clear Access (`theme-clear-access.peb`)

For buyers who use a screen reader, or have low vision. It passes every WCAG 2.2 level A and AA check that axe-core makes, and its best-practice rules, with the demo catalog. The only item left for review is video captions, which the catalog can't supply (see *Known open items*).

- **Structure a screen reader can navigate:** a *Skip to the apps* link first; the store's name as the main heading and each app as a subheading; page regions for the header, the contents, the apps and the footer; a contents list linking to every app, and a link back from each.
- **Downloads that make sense out of context,** since screen reader users often list a page's links: *Download Termux APK, 265 B* and *Download Termux source code*. They come straight after each app's description.
- **Pictures in one column,** not a sideways strip, each labelled with its app and position, such as *Termux, picture 1 of 3*. Every picture and video has a fixed-height box, so pictures loading as the page scrolls never move it, and the contents links land where they should.
- **For low vision:** text at 20 px by default, in units that grow with the phone's text size; black on white, or white on black in dark mode; a thick focus outline; underlined links; buttons at least 56 px tall. With the phone's *increase contrast* setting, borders thicken; with Windows' high-contrast mode, system colours are used.
- **Nothing moves,** and there's no script.
- **Built into the plugin,** which publishes it as `accessible.html` beside every other template's page. There, it links back at its top left, with *Standard version*. Its first line is the comment `share-your-apps:accessible`, which tells the plugin that a store using it as its own template needs no second copy.

```
{# share-your-apps:accessible — marks this as the accessible template, so the plugin makes no second copy of it. #}
<!doctype html><html lang="{{lang}}"><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1"><title>{{storeName}}</title><style>
/* Clear Access: for people using screen readers, and for people with low vision.
   Sizes are in rem, so they grow with the phone's own text size. Nothing moves; there's no script. */
:root{--bg:#fff;--ink:#000;--soft:#1f1f1f;--line:#000;--link:#0033cc;--focus:#ffbf00;--btn:#000;--btn-ink:#fff}
@media (prefers-color-scheme:dark){:root{--bg:#000;--ink:#fff;--soft:#e8e8e8;--line:#fff;--link:#9fc3ff;--focus:#ffbf00;--btn:#fff;--btn-ink:#000}}
*{box-sizing:border-box}
html{font-size:125%;-webkit-text-size-adjust:100%}
body{margin:0;background:var(--bg);color:var(--ink);font:1rem/1.6 system-ui,-apple-system,"Segoe UI",Roboto,Arial,sans-serif}
.skip{position:absolute;left:.5rem;top:-10rem;z-index:2;background:var(--btn);color:var(--btn-ink);padding:.75rem 1rem;font-weight:700;border-radius:.4rem}
.skip:focus{top:.5rem}
a{color:var(--link);text-decoration:underline;text-underline-offset:.2em;text-decoration-thickness:.12em}
:focus-visible{outline:.25rem solid var(--focus);outline-offset:.2rem;box-shadow:0 0 0 .45rem var(--ink)}
.wrap{max-width:38rem;margin:0 auto;padding:1rem}
header{border-bottom:.25rem solid var(--line);padding-bottom:1rem}
.brand{display:flex;align-items:center;gap:1rem}
.brand img,.brand video{width:3.5rem;height:3.5rem;object-fit:contain;flex:none}
h1{font-size:1.8rem;line-height:1.2;margin:.25rem 0}
h2{font-size:1.4rem;line-height:1.25;margin:0 0 .25rem}
.sd p,.pd p{margin:.5rem 0}.sd ul,.sd ol,.pd ul,.pd ol{padding-left:1.4em}
nav{border-bottom:.25rem solid var(--line);padding:.5rem 0 1rem}
nav h2{font-size:1.2rem}
nav ol{margin:.25rem 0;padding-left:1.6em}
nav li{margin:.35rem 0}
article{border-bottom:.25rem solid var(--line);padding:1.25rem 0}
.meta{color:var(--soft);margin:0 0 .5rem}
.media{list-style:none;margin:.75rem 0;padding:0}
.media li{margin:0 0 .75rem}
/* A fixed height, so pictures loading as the page scrolls never shift it: the contents links and the skip link land where they should. */
.media img,.media video{display:block;width:100%;height:16rem;object-fit:contain;border:.12rem solid var(--line);background:var(--bg)}
.media audio{width:100%}
.downloads{display:flex;flex-direction:column;gap:.75rem;margin:1rem 0 .5rem}
.b{display:flex;align-items:center;justify-content:center;min-height:3.5rem;padding:.6rem 1rem;border:.2rem solid var(--line);border-radius:.5rem;font-weight:700;text-align:center;text-decoration:none;color:var(--ink);background:var(--bg)}
.b.apk{background:var(--btn);color:var(--btn-ink);border-color:var(--btn)}
.top{display:inline-block;margin-top:.5rem}
.facts{margin:.25rem 0 .5rem}
.new{margin:.5rem 0}
.news{border:.2rem solid var(--line);border-radius:.5rem;padding:.6rem .8rem;margin:1rem 0 0;font-weight:700}
footer p{margin:.35rem 0}
.alt{display:inline-block;margin-bottom:.5rem;font-weight:700}
footer{padding:1rem 0 2rem;color:var(--soft)}
@media (prefers-contrast:more){:root{--soft:var(--ink)}.b,.media img,.media video{border-width:.3rem}}
@media (forced-colors:active){.b.apk{background:ButtonText;color:ButtonFace;forced-color-adjust:none}:focus-visible{outline-color:Highlight}}
@media (prefers-reduced-motion:no-preference){html{scroll-behavior:smooth}}
</style></head><body>
<a class=skip href="#apps">Skip to the apps</a>
<div class=wrap>
{% if standardHref is not empty %}<nav aria-label="Page versions"><a class=alt href="{{standardHref}}">Standard version</a></nav>{% endif %}
<header><div class=brand>{% if headerImage.type=='video' %}<video src="{{headerImage.src}}" muted playsinline aria-hidden=true></video>{% else %}<img src="{{headerImage.src}}" alt="">{% endif %}<h1>{{storeName}}</h1></div>{% if storeDescriptionHtml is not empty %}<div class=sd>{{storeDescriptionHtml|raw}}</div>{% endif %}</header>
{% if announcement is not empty %}<p class=news role=region aria-label="Announcement">{{announcement}}</p>{% endif %}{% if products is not empty %}<nav aria-labelledby=contents><h2 id=contents>{{products|length}} {% if products|length == 1 %}app{% else %}apps{% endif %}</h2><ol>{% for p in products %}<li><a href="#{{p.slug}}">{{p.name}}</a></li>{% endfor %}</ol></nav>{% endif %}
<main id=apps tabindex=-1>{% for p in products %}<article id="{{p.slug}}" aria-labelledby="name-{{p.slug}}"><h2 id="name-{{p.slug}}">{{p.name}}</h2><p class=meta>Added {% if p.dateAddedIso is not empty %}<time datetime="{{p.dateAddedIso}}">{{p.dateAddedLabel}}</time>{% else %}{{p.dateAddedLabel}}{% endif %}</p>{% if p.descriptionHtml is not empty %}<div class=pd>{{p.descriptionHtml|raw}}</div>{% endif %}{% if p.whatsNew is not empty %}<p class=new><b>What's new:</b> {{p.whatsNew}}</p>{% endif %}<div class=downloads>{% if p.apk is not empty %}<a class="b apk" href="{{p.apk.src}}">Download {{p.name}} APK, {{p.apk.sizeLabel}}</a>{% endif %}{% if p.sourceZip is not empty %}<a class=b href="{{p.sourceZip.src}}">Download {{p.name}} source code, {{p.sourceZip.sizeLabel}}</a>{% endif %}</div>{% if p.apk is not empty %}<p class=facts>{% if p.apk.versionName is not empty %}Version {{p.apk.versionName}} &middot; {% endif %}{% if p.apk.androidLabel is not empty %}{{p.apk.androidLabel}} &middot; {% endif %}For {{p.apk.abiLabel}}</p>{% endif %}{% if p.author is not empty or p.license is not empty or p.websiteHref is not empty %}<p class=facts>{% if p.author is not empty %}By {{p.author}}{% endif %}{% if p.license is not empty %}{% if p.author is not empty %} &middot; {% endif %}License: {{p.license}}{% endif %}{% if p.websiteHref is not empty %}{% if p.author is not empty or p.license is not empty %} &middot; {% endif %}<a href="{{p.websiteHref}}">Website</a>{% endif %}</p>{% endif %}{% if p.carousel is not empty %}<ul class=media aria-label="{{p.name}}: {{p.carousel|length}} {% if p.carousel|length == 1 %}item{% else %}pictures and videos{% endif %}">{% for m in p.carousel %}<li>{% if m.type=='video' %}<video src="{{m.src}}" controls preload="none"{% if m.poster is not empty %} poster="{{m.poster}}"{% endif %} {% if m.width is not empty %}width="{{m.width}}" height="{{m.height}}" {% endif %}aria-label="{{m.alt}}">{% if m.captions is not empty %}<track kind="captions" src="{{m.captions}}" srclang="{{lang}}" label="Captions" default>{% endif %}</video>{% elseif m.type=='audio' %}<audio src="{{m.src}}" controls aria-label="{{m.alt}}"></audio>{% else %}<img src="{{m.src}}" {% if m.width is not empty %}width="{{m.width}}" height="{{m.height}}" {% endif %}alt="{{m.alt}}" loading="lazy">{% endif %}</li>{% endfor %}</ul>{% endif %}<a class=top href="#contents">Back to the list of apps</a></article>{% endfor %}</main>
<footer><p>{{storeName}} &middot; Updated <time datetime="{{publishedAt}}">{{publishedLabel}}</time></p>{% if contact is not empty %}<p class=contact>Questions?{% if contact.name is not empty %} Ask {{contact.name}}{% endif %}{% if contact.emailHref is not empty or contact.phoneHref is not empty or contact.urlHref is not empty %}:{% endif %}{% if contact.emailHref is not empty %} <a href="{{contact.emailHref}}">{{contact.email}}</a>{% endif %}{% if contact.phoneHref is not empty %}{% if contact.emailHref is not empty %} &middot;{% endif %} <a href="{{contact.phoneHref}}">{{contact.phone}}</a>{% endif %}{% if contact.urlHref is not empty %}{% if contact.emailHref is not empty or contact.phoneHref is not empty %} &middot;{% endif %} <a href="{{contact.urlHref}}">website</a>{% endif %}</p>{% endif %}<p class=made>Made with <a href="https://www.appdevforall.org/plugins" style="color:inherit">Share Your Apps{% if shareYourAppsVersion is not empty %} {{shareYourAppsVersion}}{% endif %}</a></p></footer>
</div></body></html>
```

---

# Appendix D — Extending templates

Owners can add their own reusable pieces and logic to store page templates in three phases, cheapest and safest first. D.1 and D.2 are built. D.3 is not built: it is a design, kept for the day owners need something D.1 and D.2 can't do.

A Pebble *extension* is Java code, not template text: its filters, tests, functions, operators, tags and attribute resolvers are classes implementing Pebble's interfaces, registered with the engine before any template is compiled — `new PebbleEngine.Builder().extension(new CustomExtension()).build()`. So owner-defined logic is either template text, which Pebble already supports as macros, or compiled code, which needs a way for the plugin to load it.

## D.1 Reusable pieces, written in Pebble itself — built

**As built.** Templates find each other through `TemplateLibrary`, the plugin's Pebble loader, for `{% extends %}`, `{% include %}`, `{% import %}` and `{% from … import %}`. The name `default` is the built-in default template; `accessible` is Clear Access; any other name is a file in `CodeOnTheGoProjects/ShareYourAppsTemplates/`, with `.peb` added if the name has none, relative to the library's top, never to the including template. A name that could leave the library — absolute, with an empty, `.` or `..` part, or a backslash — is refused, and so is a missing one, each stopping the publish with a message naming it. The default template has six blocks: `styles`, `header`, `announcement`, `card` — one product, inside the loop, so it sees `p` — `downloads`, inside `card`, and `footer`. They don't change its output at all: extending it without overriding anything gives exactly the default page, byte for byte, and `{{ parent() }}` inside a block keeps the original. The tests check each of these, through real publishes.


No code runs in this phase. The library folder, the default template's blocks and the macros an owner keeps in a library file are all built, as described above.

## D.2 A curated set of built-in extensions — built

**As built,** in `TemplateFunctions`, a Pebble extension registered with every render:

- **`t("key")`**, or `t("key", "fallback")`: text from the Extra JSON `strings` table — `{"strings": {"sw": {"download": "Pakua"}, "en": {"download": "Download"}}}` — by the store's language, then its main language (`sw` for `sw-TZ`), then English, then the fallback, then the key itself. Its text is escaped like any other.
- **`plural(n, "# app", "# apps")`**: the first form for 1, the second otherwise, with `#` replaced by the number. It follows English's rule, which suits many languages but not all.
- **`markdown`**, a filter: paragraphs, lists of `- ` lines, `**bold**`, `*italic*`, `` `code` ``, and `[links](…)` to `http`, `https` or `mailto` only. The source is escaped first, so no HTML in it survives, and a link's address ends at an escaped quote or angle bracket, so it can never carry one.
- **`qr("text")`**, or `qr("text", "label")`: a QR code as an inline SVG image, `role="img"` with its label for screen readers. It uses ZXing's core library, the server's own version, which the plugin now bundles.
- **`bytes`**, a filter: a byte count as the store's own size labels show it.
- **`slugify`**, a filter: the store's slug rule.

Pebble's own `abbreviate` filter already truncates text, so no `truncate` was needed.


These are real Pebble filters and functions, written and tested with the plugin and documented like the other values templates receive. Owners use them without writing code.

## D.3 Owners' own compiled extensions (not built)

For owners who write Kotlin, as many Code On The Go users do.

- **Building one:** a small Code On The Go project, from a new *Share Your Apps extension* project template, compiled against Pebble 4.1.1 — the version Code On The Go supplies — into a `.dex` file. A properties file inside names the extension class, its version, and the Pebble version it needs.
- **Adding one:** in Manage Catalog, under **Advanced**, an **Extensions** list. Adding an extension shows a warning: *This runs as code, with Code On The Go's permissions. Add only extensions you built or trust.* The catalog records each extension's path and SHA-256 hash; if the file changes, the plugin asks again before using it.
- **Loading it at publish time:** the plugin copies the file into its private folder and marks it read-only, since Android 14 refuses to load writable code files. It then loads it with a `DexClassLoader` whose parent is the plugin's own class loader. That parent is essential: the extension must share the plugin's Pebble classes, not bring a copy of its own. Each extension is registered with the engine that renders the store's page. The accessible page keeps its built-in template, and no extensions, so it stays predictable.
- **Containing failures:** each publish renders on its own thread, with a time limit — ten seconds, say — so a runaway extension can't hang it. Any error, including a class that won't load, stops the publish with a message naming the extension, and the store keeps serving its previous version, as after any failed publish.
- **The rules for extension authors:** filters returning HTML must return Pebble's `SafeString`, or their output is escaped. Extensions shouldn't use the network, since stores work offline — a rule the plugin can't enforce, because extensions run inside it. Operators, custom tags and attribute resolvers are allowed, but documented as advanced.
- **No code from templates, ever:** a `.peb` file stays plain text, so a template shared by someone else can never bring code with it.

## D.4 Recommendation and testing

**D.1 and D.2 were built first.** Together they cover most of what owners are likely to want — reusable pieces, partial overrides, translation and common formatting — without running anyone's code. D.3 is real engineering with lasting costs: a compatibility promise to extension authors, a security warning owners may click through, and failures that are harder to support. Build it only when owners need something D.1 and D.2 can't do. A scripting engine, such as JavaScript for templates, isn't worth adding: D.3 gives developers more, for less.

**Testing:**
- **D.1:** the loader's refusals, including `../` and absolute paths; and a template extending the default and replacing one block.
- **D.2:** each built-in, with known inputs and outputs.
- **D.3:** loading a sample extension from a real `.dex` on a phone; a changed file triggering the plugin to ask again; and a slow extension hitting the time limit.
- **All three:** axe-core's WCAG 2.2 AA checks on every page again, since blocks and extensions can change the markup.

---

# Appendix E — Sharing a store with another seller

Built. How an owner gives someone nearby a copy of their store, to serve from that person's own phone. Mockups of its screens, in the app's own look and wording as built, are in `store-sharing-screens/`: 1 to 3 on the owner's phone — Manage Server, the share screen and *Allow a copy?* — and on the new seller's, 4 Manage Server before importing, 4b *Can't import a store here*, 5 the import screen, 6 importing, 6b the finished import, 7 serving the copy, and 8 after it expires.

## E.1 What a copy is

- **A published store, served as it arrived.** It can't be changed: without Code On The Go and the plugin, the new seller has no Manage Catalog, and the copy is never rendered again. The server shows it as *Read-only*.
- **It can't be passed on** through Share Your Apps (see E.4).
- **It expires** on a date the owner chooses, when it's deleted from the seller's phone (see E.5).
- **The PIN belongs to serving, not to the store.** Each seller sets their own in the server, or leaves it blank; it's never part of a copy.
- **A phone serves one store at a time,** its own or one imported copy, and holds at most one copy. A phone serving **any** store — its own, or a copy — can't import one, so a copy is never replaced under a running server. Manage Server's *Import a store* panel says so **before any tap**: its button is disabled, and its caption reads *This phone is serving a store. To import one, stop serving it first, with Stop above, or import on another phone.* The import screen, which a QR link can open directly, says the same (screen 4b).

## E.2 The flow

On the owner's phone:
1. **Manage Server** has a panel, **Copies of this store**, with **Share this store…** (screen 1).
2. It shows a QR code, and the same as a typed code, such as `LS-7Q4K-9M2P`. The code works for one phone, for 10 minutes (screen 2).
3. When a phone asks, the owner sees its name and a four-digit **check code**, and chooses when the copy expires — in a week, in a month (the default), on a date, or never — then taps **Allow** or **Deny** (screen 3). Comparing the check code with the other screen confirms which nearby phone is asking.

On the new seller's phone:
4. The server's start screen offers **Import a store…** (screen 4). While any store is served, its button is disabled, and its caption says why and what to do; the import screen, reached from a QR link, says the same (screen 4b).
5. The seller scans the owner's code, or types it, and confirms their phone's name, which starts as the phone's own name, and can be changed (screen 5).
6. Importing shows its steps, the check code, the copy's expiry and the transfer's progress (screen 6). **Cancel** works throughout.
7. The imported store shows as *Read-only*, with its expiry, **Serve this store**, the seller's PIN, and **Remove this store** (screen 7).
8. After expiry, the server says the store has been deleted, and that the seller can ask its owner for a new copy (screen 8).

The owner's server keeps no record of the copies it has shared.

**How the owner's side is built.** The screens follow the server app's own look — grey, with white, bevelled panels — not the mockups' store colours. The share lives in the server process, beside the HTTP server, since that's where requests arrive. The share screen, in the main process, binds to the server process directly, without starting it if it isn't running, and asks what's happening twice a second: `MSG_SHARE_OPEN`, `MSG_SHARE_POLL`, `MSG_SHARE_ALLOW` with the copy's expiry, `MSG_SHARE_DENY` and `MSG_SHARE_CLOSE`, answered by `MSG_SHARE_STATE`. Leaving the screen closes the share; rotating the phone, or another app coming over it, doesn't. **Sharing waits for compression:** right after a publish, the server is still writing the gzip copies, which change the store's files, so the share screen says *Getting the store ready to share…* and shows the code once it's done. If a transfer fails partway, the owner's screen says so, and the seller can try again while it stays open. The screen stays on while it's open, since a server paused for a dark screen can't share. If the server stops, sharing stops with it, and the screen says so. **Share this store…** works only while the server is serving the phone's own store. Otherwise it's disabled, and the panel's caption says why: *Start serving your store to share it*, or, while a copy is served, *This phone is serving a copy of someone else's store. Copies can't be passed on.* The store's key is kept in the app's private storage, `files/share/`. The QR code holds a `shareyourapps-share://<address>:<port>/<code>` link, which a phone's camera app recognises. An expiry *on a date* lasts to the end of that day, on the owner's phone. A button beside the typed code copies it, for pasting into a message. It's marked sensitive on the clipboard, so Android 13 and later hide it from the clipboard preview, and sync services and keyboards generally don't pass it on. **For a phone without the app,** the share screen shows a second, smaller QR code: *No Share Your Apps Server on the other phone? Scan this first.* Below it (from server step 65), *Or open this link in the other phone's browser:*, then the download link as selectable text, with a **Copy** button. The link may take two lines (from server step 67): `http://192.168.50.100:6175/share/app` is too wide for one on a narrow phone even at the smallest size, and was cut off, its second line showing only as dots. The share code keeps one line. **The Share screen names both phones throughout** (from server step 66): *this phone* is always the one sharing its store, and *the other phone* the one getting a copy — never *it*, *its* or *the phone*, which read ambiguously. The share code stays marked sensitive on the clipboard; the download link isn't. It opens `/share/app` in that phone's browser, which downloads the owner's own installed APK — the genuine app, signed with the server's key — named `share-your-apps-server.apk`. Like the rest of `/share/`, it's only there while the share screen is open, so it's never offered to buyers. The seller still sees Android's install prompt, and on some phones more — such as allowing the browser to install apps, or turning off Samsung's Auto Blocker — as *Limitations* describes.

**How the seller's side is built.** Manage Server has an **Import a store** panel. On a phone serving its own store, it shows screen 4b's message instead, saying to use **Stop** above, since that screen is Manage Server. The import screen asks for the code and the phone's name, filled in from Android's device name. It also opens straight from the QR's link — a camera app offers to open `shareyourapps-share:` links, since the server registers them — with the owner's address and code already filled in, so no search is needed. A typed code is found by broadcast. The import runs in the background, in `ImportJob`, so the screen can rotate, or be left and reopened. It has four steps: finding the store, the owner allowing it, receiving, with a progress bar, and ready. **Cancel**, or Back, stops it at any point, leaving nothing. A copy is kept in the app's private storage, `files/imported/<storeId>/`, with its description beside it, `<storeId>.json`, for its name and expiry. Importing replaces the phone's one copy as E.6 says, asking first when it's older, or a different store. The import runs inside a small foreground service, `ImportService`, with its progress in a notification, so Android doesn't freeze or kill it once the app leaves the screen. If a transfer fails partway, the import screen offers **Try again**, using the same exchange, while the owner's share stays open. **Every request goes over Wi-Fi explicitly**, bound to the Wi-Fi network: a network without internet, such as the owner's hotspot, isn't Android's default, and requests would otherwise go to mobile data, and fail. A phone that isn't on Wi-Fi is told to join the owner's network first. If the import happened over the owner's own network — the Wi-Fi's gateway is the owner's phone — the last screen says so: serving there would depend on the owner's phone, so the seller should join their own Wi-Fi, or turn Wi-Fi off so the phone starts its own hotspot. On a phone serving a copy, messages never suggest publishing again. The mockups' camera view on screen 5 became a hint to scan with the phone's own camera, since a built-in scanner would be a lot of code for no gain.

**Serving a copy.** Manage Server shows a panel for the copy whenever the phone holds one (screen 7): its name, *Read-only*, and when it expires — nothing about where it came from, which the copy doesn't carry — then **Serve this store**, the seller's PIN, set or left blank, and **Remove this store**, after a confirmation. The import screen ends with **Serve it now**. Serving a copy is a second way for the server to start, `ACTION_SERVE_COPY`, passing the server process the copy's folder, its name, and the seller's PIN, without the plugin's token. The server process accepts the folder only if it's inside the app's own `files/imported/`, and skips the shared-storage checks a published store needs. Everything else — the Wi-Fi watch, the hotspot, pausing when the screen's off, the Serve screen and the log — works as for any store. The server remembers which kind it last served, so **Start**, unlocking and the first **Start** after a reboot bring back the right one — and on a phone with only a copy, **Start** serves the copy, since there's nothing else to serve. The Status panel says which **Start** will serve; serving a copy never overwrites the saved details of the phone's own store, so publishing from Code On The Go serves the owner's store, keeping the copy (E.6). While a copy is served, **Share this store…** is disabled, and the server process refuses to share one anyway. Importing is blocked while the phone serves any store, own or copy; to update a copy, a seller stops serving it first.

## E.3 The transfer, and why it can be trusted

- **The store's key.** Each owner's store has a key pair, created once and kept in the owner's server's private storage. It signs every copy, and it *is* the store's identity.
- **One copy, for one phone.** The seller's server asks the owner's server directly, over the local network, through a separate sharing address authorised by the share code — not the store's PIN, which is for buyers — sending its own public key. The owner's server encrypts the copy for that key alone, with authenticated encryption such as AES-GCM, so the copy is useless on any other phone, and any altered byte makes the transfer fail.
- **The exchange, and why the check code can't be forged.** (1) The seller's server sends the share code and a *commitment*: the hash of its public key. (2) The owner's server replies with its own one-time public key. (3) The seller's server reveals its key, which the owner's checks against the commitment. Both then show a four-digit check code computed from the two keys and the code. Without the commitment, someone in the middle of the network could try thousands of keys until the digits matched; with it, each side's key is fixed before it sees the other's, so their chance stays 1 in 10,000. Bluetooth pairing and ZRTP use the same idea.
- **The code for all of it** is in one file, `ShareCrypto.kt`, for the review: P-256 keys, ECDSA signatures, the store's identity, the commitment, the check code, ECDH with HKDF-SHA256, and AES-256-GCM in numbered 64 KB chunks, whose number and a *last* flag are authenticated too, so altering, reordering or cutting off a copy all fail.
- **The sharing address,** on the owner's server, answered before the PIN check, since sellers aren't buyers: `POST /share/request` (the code, the phone's name and the commitment), `POST /share/reveal` (the seller's key), `GET /share/status` (*waiting*, *allowed*, *denied* or *expired*), and `GET /share/copy`. It exists only while the owner's screen has a share; otherwise every path answers *not found*. While it exists, a seller can still hear *denied* or *expired*, and each route refuses what it should: a request once the share isn't open, a status for an unknown request, a copy before approval.
- **Waiting without blocking anyone.** The owner's main thread never waits on a person: the seller's server asks for the status every second instead. The share itself is guarded by one lock, held only for a moment each time.
- **The copy is made as it's sent,** encrypted in a sender thread like any other download, so it's never stored on the owner's phone, whatever its size. After one complete download, the share is done, and its code can't be used again.
- **Codes can't be guessed:** five wrong codes close the share, even to the right one. A code is also refused once it's been used, after 10 minutes, and while another phone is using it.
- **Finding the owner's server from a typed code:** the seller's server broadcasts *who has this code?* on UDP port 6176, as a hash of the code, so listening to the broadcast doesn't reveal it. Only the server showing that code answers, with its port. A QR code carries the address itself.
- **A protocol number.** Each side sends its sharing protocol's version, `ShareHost.PROTOCOL`, and a mismatch is refused — after the code is checked, so strangers can't probe versions — naming which phone needs updating: the seller's, from the owner's share screen's small QR code, or the owner's.
- **Every file stays inside the copy's folder.** Import refuses any path that's absolute, has an empty, `.` or `..` part, or a backslash, or whose canonical path leaves the temporary folder; and a copy listing a file twice. The folder's name is the store's identity, which import checks is derived from the store's key, so it's base32 only. These checks matter even though the copy is signed: a modified owner's app could send bad names.
- **A copy never includes temporary files,** such as the compressor's `.gz.tmp`, which vanish when renamed.
- **No separate checking pass.** The authenticated encryption, with the check code, already guarantees every file arrived intact and came from the owner. APKs' own signatures catch damage, but not an APK swapped for another; the transfer catches both, for every file. The one extra check is the copy's description file, whose signature is checked against the store's key when the transfer starts.
- **All or nothing.** Everything is written to a temporary folder, renamed into place only once the last file is saved, so a store is either complete or absent. A cancelled or interrupted import leaves nothing behind; the seller asks the owner again.
- **Space first.** The seller's server checks it has room before the transfer starts.
- **Plain HTTP between the phones.** Android 9 and later block an app's own plain-HTTP requests unless it opts in, so the server's manifest sets `android:usesCleartextTraffic="true"`. A narrower network security configuration can't help, since it only allows named hosts, not whatever private address the owner's phone has. Plain HTTP is safe here: the copy is protected by its own encryption, and the other requests carry nothing secret.
- **The technical choices:** P-256 keys with AES-GCM, both built into Android back to Android 9, the oldest version the server supports, so no cryptography library needs bundling; X25519 isn't built in on every version. The share code authorises the sharing address, because the store's PIN is for buyers. The check code has four digits, making a lucky match 1 in 10,000 — enough for a code compared by eye, once, within 10 minutes.
- **An independent review first.** This is a transfer protocol of our own design, combining key exchange, encryption and signatures. Before it's built, someone who reviews cryptographic protocols should check it.

## E.4 Why a copy can't be passed on — and the limits of that

- **Nothing is backed up, or moved to another phone:** the manifest sets `allowBackup="false"`, and data-extraction rules exclude everything from cloud backup and from device-to-device transfer, which Android 12 and later do even without backup. So a copy, the store's key and the logs of buyers' addresses stay on their phone.
- The server has no export for an imported store, and keeps it in its private storage, which other apps and file managers can't reach.
- Each copy is encrypted for one phone, so forwarding the files achieves nothing.
- Every copy is one the owner allowed, on their own screen.

**It isn't copy protection,** and the design shouldn't claim it is. Anything a store serves is ordinary web content, which anyone who can open the store can copy with any web-copying tool, and serve with other software. And Share Your Apps Server is open source, so a modified build could ignore every rule here.

## E.5 Expiry

- **The date is in the copy's description, signed with the store's key,** so the seller can't change it.
- **On or after that date,** the server stops serving the store, lets downloads already under way finish, then deletes it. It checks once a minute while serving, and whenever the app looks at the copy, so an unserved copy past its date is deleted the next time the app runs — it can never be served in between.
- **How it's built.** One rule, `CopyExpiry`, in the core, decides: a copy is due on its date, or at once if the clock was set back more than five minutes; then it's deleted, leaving a notice. While a copy is served, the server process checks once a minute. When the copy is due, the HTTP server stops taking connections (`stopAccepting`) — on Android, a closed socket keeps listening until a blocked `accept()` returns, so a connection arriving just after is closed at once, rather than served — downloads under way finish, for up to 10 minutes, and then the copy is deleted, and the main process told, so the notification says why the server stopped. The same rule runs whenever the app looks at the copy — Manage Server coming to the front, **Serve this store**, **Start** — so an expired copy is never shown as servable, nor served again. Importing a copy restarts the clock at the current time: the rule exists to stop a seller extending a copy they already have, and a clock once set too far ahead — wrong after a reboot, say, then corrected — mustn't condemn every copy imported later. The expiry clock keeps its latest time in `files/share/latest-time`, outside the imported folder, so it outlives copies.
- **Screen 8,** *A store has expired*, is a panel at the top of Manage Server, until the seller taps **OK**: *Your copy of Top Android apps has expired, and has been deleted from this phone. Buyers can no longer open it. To serve it again, ask its owner for a new copy.* Its notice is `files/imported/expired-notice.json`, holding only the store's name, which importing a new copy removes.
- **A clock set back looks exactly like an ordinary expiry,** to the seller and in the log: the same words on screen 8, no date, since a date would read wrongly for a copy that went early, the same log line, and nothing in the notice or the help about the clock. A message would only teach someone how the rule works.
- **Clocks.** Expiry uses Android's universal time, so time zones and daylight saving don't affect it. The server remembers the latest time it has seen. A clock set back by up to five minutes — an ordinary correction — is accepted. A larger jump backwards expires the store at once, and its content is deleted immediately — if it has a date. The check runs when Manage Server opens and while the copy is served, so a copy on a stopped server is deleted when the app is next opened. A copy that never expires has no date to extend, so the rule spares it. A clock can still be set back gradually, five minutes at a time, so this deters rather than prevents.

## E.6 Updating, replacing and recovering

- **A newer copy from the same store replaces the older one,** and takes the new copy's expiry, so the owner can extend a copy by sharing again.
- **An older copy than the one there asks first.**
- **A different store** — including the same owner's, after a new key — asks before replacing the seller's copy, since a phone holds at most one.
- **Publishing on a phone that holds a copy.** An owner can stop serving their own store, then import a copy. When they next publish from Code On The Go, their own store is served, not the copy, without asking. The copy is kept, not deleted: it still expires on its date, and if the owner stops serving their own store again, Manage server offers to serve the copy.
- **A lost key** — the owner's phone reset, say — needs no recovery: the owner reinstalls the server, which makes a new key, and so a new store identity. Existing copies keep working until they expire.

## E.7 Open decisions

None. Before building, the transfer protocol should have its independent review (E.3).

## E.8 The sound handshake

Built. A phone whose camera is broken can't scan the QR code, and typing a code is slow, so the sharing phone can also play the code as sound, and the other phone can listen for it. Sound carries only the handshake: the copy still goes over Wi-Fi, and a person still taps **Allow**.

- **The owner's phone.** The share screen has **Play sound** under the typed code. While it plays, the button reads **Stop sound**, and a line counts the attempts left: *Playing sound... (N attempts remaining)*. **Stop sound** stops it at once, and it stops by itself when a phone connects. If the media volume is muted or under a third of full, a message says so, since the other phone probably won't hear it.
- **The new seller's phone.** The import screen has **Listen**. The first time, Android asks to allow the microphone. Listening shows a countdown from 30 seconds, a **Microphone level** meter, which is empty when quiet and rises with any sound, and one of *Nothing heard yet.*, *Hearing something faint: hold the phones closer.* or *Hearing the tones.* A code heard starts the import by itself, with the phone's name and a check code, exactly as typing the code would. After 30 seconds without one, it says *Didn't hear the code. Hold the phones closer, ask the owner to turn up the media volume, and try again.* Tapping **Listen** three times quickly runs a self-test, in which the phone plays and listens to its own tones.
- **What the tones carry** (`SoundPayload`, about 46 bytes for the owner's own hotspot and 14 to 30 for other Wi-Fi): a version and flags (1 byte), the share code (5 bytes, 8 characters of 5 bits each), the owner's address and port when known (6 bytes), the network's name, and a CRC-16 (2 bytes). For the owner's **own hotspot** only, it also carries the hotspot's password, since a phone can't join without it. On other Wi-Fi it carries the network's name and **never** a password. The password is never logged: `toString()` leaves it out, and the log says *password received, not logged*.
- **How it sounds** (`SoundModem`). One burst is a rising sweep (2400 to 4800 Hz, 150 ms, the start signal), a 20 ms gap, a 3-byte header, then the data. Each byte is two tones, high 4 bits first, each one of 16 and held for 30 ms with soft edges so it doesn't click. Even and odd tones use separate, interleaved sets of 16 frequencies, so a room's echo of one tone can't pass for the next, and all 32 lie between 2400 and 4725 Hz, within one octave, so a distorting speaker's harmonics fall above them. A round is three bursts, one per *repeat*, each scrambled with its own key (the header isn't), so a value sent on a weak or noisy frequency in one repeat goes out on another in the next. A 100 ms pause separates bursts.
- **How long it plays** (`SoundModem.burstsFor`). As many bursts as fit in 30 seconds (`SoundModem.WINDOW_MS`), but at least four, so a listener that joins in the middle of a burst still hears three whole ones to combine. A short code gets dozens; a hotspot's name and password, whose bursts last about 7 seconds, get four. The attempts counter counts bursts from the speaker's own position (`AudioTrack.playbackHeadPosition`), so it follows what is actually heard. A listener listens for at most 30 seconds too.
- **Hearing** (`SoundDecoder`). The listener finds the sweep, reads the header, and combines up to three bursts symbol by symbol, so a damaged burst can be repaired by the others. A candidate is accepted only if its CRC passes.
- **After the code is heard.** The listening phone checks, for about 2 seconds, whether it can reach the owner's phone on its Wi-Fi. If it can, or the tones carry no hotspot password, the import starts. Otherwise, on Android 10 and later, Android asks to join the owner's own hotspot, and a person taps **Connect** within 30 seconds; the phone leaves that hotspot when the import ends. Android asks only the first time a phone joins a hotspot of that name, so to see the prompt again, change the name under **Wi-Fi Direct hotspot**. On Android 9, or with no tap, the import screen shows the hotspot's name and password to join by hand, then **Continue**.
- **The microphone.** Only the import screen (or MCP's `import_listen`, while MCP control's service holds the microphone type) listens, and it stops when the screen closes, so nothing listens off screen. If the permission is refused, the screen says to scan the owner's code or type it instead, and typing still works.
- **Logged.** `[sound]` lines in the access log: tones played, with how many bursts of how many; a code heard; joining the owner's hotspot, with its name and never its password; letting it go; and *can't listen* when the microphone isn't available.
- **Tests and QA.** `SoundModemTest`, `SoundPayloadTest`, `SoundLevelTest` and `SoundLogTest`; QA steps section M.
