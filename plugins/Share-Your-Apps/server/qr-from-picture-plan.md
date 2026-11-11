# Plan: read QR codes from a picture on the import screen (project B)

Status: PLANNED, NOT approved, no code written. Saved for later (user: "We will take it later").
The user's standing rule applies: plan first; build nothing until the user approves.

## Request
"On the import screen, support QR recognition from an image already on the phone", for all QR codes
the app shows, on all screens (user: "All qr codes means all codes on all screens").

## What was found (read-only)
- ImportActivity.kt (read in full, 522 lines): the only way a share QR reaches the import screen today is
  the camera app opening the localstore-share://… link (onCreate parses intent.data with ShareLink.parse
  and fills linkAddress / linkCode). Nothing reads a picture.
- QR codes the app shows:
  | Screen | Code | Content |
  |---|---|---|
  | Share screen (ShareActivity.kt:366) | Share code | localstore-share://<address>:<port>/<code> |
  | Serve screen, "1. Join this Wi-Fi network" (ServeActivity.kt:149) | Wi-Fi code | QrCodes.wifi(ssid, password): the app's own hotspot, the phone's Mobile hotspot, or a home Wi-Fi network (the last two typed by the owner) |
  | Serve screen, "2. Open the store" | Store code | NOT read yet; drawn by the qr(content) helper (ServeActivity.kt:256); content set in lines 1–119; probably the store's web address |
- Gaps to close before writing:
  1. The QR search stopped at 100 matches (mostly build/ files); search app/src only to confirm no other codes.
  2. Read ServeActivity.kt lines 1–119 for the store code's content.

## Plan
- Button "Read QR code from a picture" below Listen on the import screen. Opens Android's own image
  chooser (no new permission, no manifest change).
- Reading, off the main thread: scale the picture to about 1600 px on the longest side; find every QR code
  (ZXing multiple-code reader); try harder if needed; also read light-on-dark codes.
- What each code does:
  - Share link: fill in the code and the owner's address; the person taps Continue.
  - A bare share code (LS-XXXX-XXXX): fill in the code; the person taps Continue.
  - Wi-Fi code (any of the three networks, or another app's Wi-Fi code): join that network.
    - Android 10+: the system "Connect?" prompt; the network is kept for the import started with Continue
      and let go when the import ends or the screen is left.
    - Android 9, WEP, hidden networks, or no tap within 30 s: show the name and password to join by hand.
    - A Wi-Fi code holds no share code: "Joined. Now scan or type the share code."
  - Store code: explain that it opens the store in a browser and can't import it; ask the owner to tap
    "Share this store…". Final wording after reading what the code holds.
  - Several codes in one picture (e.g. a whole Serve screenshot): join Wi-Fi first, then fill in the code;
    list the rest.
  - Codes from other apps: shown as text with Copy, never acted on.
  - No code found: "No QR code found in that picture."
  - Log: a "[qr]" line in the app's log; never a password, never the full text of other apps' codes.
- Files:
  - New QrImage.kt: finds the codes in a picture.
  - New QrContent.kt: plain Kotlin; sorts each text into share link, share code, Wi-Fi (escapes, type,
    hidden flag), store code, other.
  - Changed ImportActivity.kt: button, chooser, handling, list of other codes.
  - Probably changed WifiJoin.kt: join any WPA or open network, not only the owner's hotspot; keep a network
    joined BEFORE Continue (today it lets go whenever no import is running).
  - Changed help/build_help_db.py: importing.html section and an import.qr_image tooltip.
  - New app/src/test/java/QrContentTest.kt: draw each app code with QrCodes, read it back, check the sorting;
    Wi-Fi codes with odd characters, open and hidden networks; other apps' codes never acted on.
- Read first: ServeActivity.kt lines 1–119; app/build.gradle (does the ZXing library include decoding?);
  WifiJoin.kt; QrCodes.kt; QrTest.kt; a QR search limited to app/src.

## Decisions (agent's suggestions, waiting for the user)
1. Scope: every code the app shows on any screen; other apps' codes shown as text. Suggest: yes.
2. Wi-Fi codes for a home network or Mobile hotspot are joined like the app's hotspot (used only by this
   app for the import, then let go). Suggest: yes.
3. A share code from a picture fills in the form; the person taps Continue (as with the camera). Suggest: yes.
4. Other apps' codes: Copy only, no Open. Suggest: yes.
5. QR codes only, no other barcode types. Suggest: yes.
6. The store code is explained, not used for importing (confirm after reading its content). Suggest: yes.

## Testing (when built)
Screenshots on the phone: the share screen; the Serve screen with each kind of Wi-Fi code (app hotspot,
Mobile hotspot, home Wi-Fi); the "Open the store" code; a QR code from another app; one picture with
several codes.
