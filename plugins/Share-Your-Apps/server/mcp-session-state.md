# Session state: Local Store Server (Combined: MCP control + sound handshake)

Current state only. No history: the older versions were deleted on purpose.

## 1. How to resume (instructions for the agent)
- **Work folder:** /storage/emulated/0/CodeOnTheGoProjects/Local Store Server Combined (project A, the MCP server, merged
  with project B, the sound handshake, in step C). Source: app/src/main/java/org/appdevforall/localstore/server.
  Unit tests: app/src/test/java. Help: help/build_help_db.py, built by help/build_help_db.sh.
- **Other folders in CodeOnTheGoProjects:** "Local Store Server" = the original, the only fallback (Git isn't used).
  "Local Store Server MCP" (A) and "Local Store Server Sound" (B) = the pre-merge projects, to be deleted by the user.
  Every copy has the same app ID: installing one replaces another on the phone.
- **The user's standing rule: plan first.** Change nothing until the user approves. "Proceed" or "Continue" after a
  plan approves it and the agent's suggested decisions.
- **Reply style:** plain words, short sections, bold headings; say exactly what was written, read back, built or
  tested; never call something tested unless the user reported it; end with numbered "Please do this next" steps.
- **The agent can't:** run shell commands or Gradle; switch, open, copy or delete folders; play sounds; flash the
  screen; choose Standard Run or Quick Build.
- **The user runs:** Standard Run; `:app:testDebugUnitTest` from the terminal; help/build_help_db.sh; the shell
  scripts (plain `sh`, no bash; python3 and curl are installed).
- **Writes:**
  - Every write needs the user's approval in Code on the Go, which times out after 5 minutes. So write only as
    the first step after a user message, and start that reply with "⚠ APPROVAL PROMPT NOW".
  - One file per turn. Change existing files with edit_file, reading the exact file first.
  - Larger changes go in self-checking sh scripts: an END line checked first; every patch found exactly once before
    anything is written; backups and `undo`; read back with SHA-256. A 29,274-character script was written whole.
- **Traps:**
  - search_project also matches build/ files: ignore them, and always use full paths.
  - Quick Build fails at :app:generateDebugQuickBuildSources because ServerService uses android:process=":server".
    Use Standard Run. This is not a code error.
  - read_build_output shows the last Gradle run, including testDebugUnitTest from the terminal.
  - McpProtocolTest prints nothing: BUILD SUCCESSFUL is its pass signal.
  - help/build_help_db.sh fails unless there are exactly two `server.mcp…` tooltips (server.mcp, server.mcp_token).
  - McpProtocolTest checks that ShareControl sends only SHARE_OPEN, SHARE_POLL and SHARE_CLOSE. Never add others.

## 2. Current state
- **Step C (A + B merged) is done.** Verified by the user on one phone (a Galaxy Note 20):
  - Standard Run builds; `:app:testDebugUnitTest` passes, including the 4 sound tests.
  - The share screen's Play sound / Stop sound works, and View logs shows "[sound] played …".
  - The MCP switch message has the microphone paragraph; Android asks for the microphone; the line under the switch
    is right.
  - mcp-sound-tests-1phone.sh: 15 of 15 passed. The microphone works with the app off screen (it ended
    nothing_heard, not "heard only silence").
  - The help database rebuilds with OK (53 entries); the help shows on the phone.
- **Earlier, before the merge:** project A's MCP tests 67/67 (mcp-curl-tests.sh, one phone); project B's manual sound
  tests on one phone; project B's stale import result fix confirmed in B.
- **Last change:** SoundLog.played says "part of the first round" instead of "0 rounds" when stopped early with no
  error. Unit tests pass; the new line hasn't been seen on the phone yet.
- **Step C scripts:** step-c-1-copy.sh … step-c-6-tools.sh, backups in step-c-backup/1–6, checksums in
  step-c-checksums.txt. `sh step-c-N.sh undo` undoes one script; undo in reverse order (6 first). The SoundLog
  wording change was a direct edit, not in any backup. Delete the scripts, backups and checksums together once a
  two-phone sound import has worked.

## 3. Untested and open
1. A two-phone import by sound: import_listen hears a real code (listen part: checking, then import_started), and the
   import completes. Also the import screen's Listen in this combined build.
2. Android's Connect prompt when SoundImport joins the sharing phone's own hotspot for MCP.
3. The microphone right after a reboot, before the app is opened: the line under the switch should say
   "Microphone: works once the app is opened".
4. D5: the share screen's Play sound and share_play_sound playing at once.
5. The stale import result fix in this combined build (import, stop serving, Wi-Fi off, delete the copy, reopen
   Import a store: it should show the empty form).
6. Project A's second-phone tests 6–11 (section 8). Tests 7 and 8 need a third phone.
7. Project B's second-phone tests 1–5 (section 8). Which were done was never reported.
8. "On the owner's network" was once shown though both phones were on the same Wi-Fi. Re-read onOwnersNetwork /
   onOwnersNetworkNow (true when this phone's default route's gateway is the owner's address). Not investigated.
9. Reading QR codes from a picture on the import screen: the plan is in qr-from-picture-plan.md (probably still in the
   Sound folder: move it here before deleting that folder). Not approved; no code. Scope: every QR code the app
   shows; codes from other apps shown as text with Copy.
- **Optional, not asked for:**
  - a dark mode change while listening stops listening;
  - the import.listen tooltip doesn't mention the countdown or the meter;
  - it's not proven that McpService starts only once per resume.
- **Open questions from the code:** where the local store folder is; which store is served after an import.

## 4. Requirements and decisions
1. An MCP client may **control** the store but **not change** it.
2. A client can take the sharer or importer role with the least human effort.
3. **Only a person taps Allow** on the sharing phone. MCP can never approve a copy.
4. An MCP import runs by itself: a newer copy of the same store replaces it; a different store is replaced without
   asking; an older copy still asks a person (notification); every import notification has Cancel.
5. An import replaces the whole imported copy atomically. The local store is never replaced.
6. **Sound is for the handshake only;** data goes over Wi-Fi. BLE is a possible later option. No Nearby Connections.
7. The tones carry: on its own hotspot, SSID + password + code + address; on other Wi-Fi, the SSID if readable,
   **never the password**.
8. SSID + password ≈ 30 characters typically; length fields, no hard limit beyond SSID ≤ 32, password ≤ 63.
9. MCP is on-device only (127.0.0.1).
10. The MCP switch is on the server screen near Start / Stop / Help. Off by default.
11. MCP may start and stop the server.
12. People use Play sound (share screen) and Listen (import screen).
13. Turning the MCP switch on also asks for the microphone, after a message. The microphone stays tied to MCP control
    (the user chose this in step C).
14. MCP is on TCP 6176. The store's port can't be 6176. MCP reports a taken port; it doesn't move.
- **Step C decisions:**
  - D2: import_listen returns at once; import_status follows it.
  - D3: MCP is never told the hotspot password (the tones still carry it on the phone's own hotspot).
  - D4: the MCP sound flow is in its own file, SoundImport.kt; ImportActivity is unchanged from B.
  - D5: the share screen and share_play_sound have separate players. Playing both at once is an accepted risk.
  - D6 a: share_play_sound's "played" line reaches the server's log over its own short binding.
- **Kept from A on purpose:** Compressor.duration takes milliseconds (B's took nanoseconds), and A's AuthTest.

## 5. Design: where each part came from
- **From B unchanged:**
  - SoundPayload, SoundDsp, SoundModem, SoundDecoder, SoundIo, WifiJoin, ImportActivity;
  - SoundLog (plus the wording change);
  - the tests SoundPayloadTest, SoundModemTest, SoundLogTest, SoundLevelTest;
  - help/build_help_db.py (it had all of A's MCP help).
- **From A:**
  - ImportService, MainActivity, HostService, ServerService, Compressor, ShareControl;
  - StoreLog: status lines are timestamped; `statusLine` for `[tag] …` lines sent over MSG_LOG;
  - LogFilter: a status line = a timestamp, then a tag;
  - McpProtocol, McpHttp, McpTools, McpService;
  - the tests AuthTest, CoreTest, LogFilterTest, McpProtocolTest.
- **Merged:**
  - ImportJob:
    - `start(context, code, phoneName, address, network = null, byMcp = false): String?` is @Synchronized and
      returns null, or why it didn't start;
    - run() uses `joined ?: wifiNetwork`;
    - the stale result fix: Snapshot.storeId, clearFinished() (@Synchronized) and onOwnersNetworkNow().
  - ImportActivity: B's file, plus a public companion `defaultPhoneName(context)` (cut to 40) used by MCP.
  - ShareActivity: A's file (joins a share under way; ownShare; Cancel ends a joined share) plus B's Play sound.
  - Manifest: RECORD_AUDIO, FOREGROUND_SERVICE_MICROPHONE, ImportActivity configChanges; McpService type
    specialUse|microphone.
- **New:** SoundImport.kt, McpSoundTools.kt.

## 6. Design: sound (from B)
- **SoundPayload:** a version/flags byte, a 5-byte packed code, optional IPv4 + port, optional SSID and password with
  a length byte each, CRC-16/CCITT-FALSE. decode returns null unless the data is exactly a payload. toString hides
  the password.
- **Modem:**
  - 32 tones from 2400 to 4725 Hz, 75 Hz apart; even and odd symbols use interleaved sets;
  - a sweep from 2400 to 4800 Hz starts each burst; 3 repeats, each scrambled with its own XOR key;
  - 30 ms tones with 3 ms soft edges, 44.1 kHz, volume 0.7. No return channel.
- **SoundDecoder:** FFT correlation finds the sweep (THRESHOLD 0.35); Goertzel reads the symbols; it combines up to 3
  bursts; the CRC check decides.
- **SoundIo:**
  - Player: media sound on the speaker, repeats until stop() or 30 s.
  - Listener: 44.1 kHz, UNPROCESSED if supported, else VOICE_RECOGNITION; ends on a valid payload, stop() or 30 s;
    reports Level(signal, db, msLeft).
  - canListen(), volumeLow(). METER_FLOOR_DB = -60.
- **Import screen Listen:**
  - Three quick taps run a self-test. The microphone is asked on first use.
  - While listening it shows a countdown and a level meter.
  - Once a code is heard: WifiJoin.reachable → ImportJob.start; else on Android 10+ WifiJoin.join (a person taps
    Connect within 30 s); else the hotspot's name and password are shown for joining by hand.
- **WifiJoin:** reachable() (about 2 s); join() via WifiNetworkSpecifier with no INTERNET capability;
  holdUntilImportEnds(); release().
- **SoundLog:** "[sound] …" lines, never a password. On the sharing phone they go to the server's log via MSG_LOG; on
  the importing phone the main process writes them (nothing while serving); all go to logcat too (LocalStoreSound).

## 7. Design: MCP (A + step C)
- **Endpoint:**
  - 127.0.0.1:6176, POST /mcp, JSON-RPC 2.0, Streamable HTTP with plain JSON; GET → 405; DELETE ends a session.
  - It checks Host and Origin. A bearer token (Copy token / New token) is never logged.
  - Long operations return at once; status tools take wait_seconds ≤ 25. McpService runs in the main process.
- **McpService:**
  - A silent notification (channel "mcp_quiet") with Turn off. Started from MainActivity.onResume if the switch is on
    (nothing starts it at boot).
  - Foreground type specialUse, plus microphone when the microphone is allowed (Android 11+). If Android refuses the
    microphone type, it runs without it.
  - The line under the switch: "On, at http://127.0.0.1:6176/mcp", plus "Microphone not allowed: …" or
    "Microphone: works once the app is opened" when those apply.
  - Turning it off ends an MCP share and stops MCP tones and listening.
- **MainActivity:**
  - The switch message adds the microphone paragraph; Turn on then asks for RECORD_AUDIO; the answer restarts
    McpService.
  - Start refuses port 6176. POST_NOTIFICATIONS is asked only when missing.
- **Tools (12):**
  - server_status, store_info, server_start, server_stop;
  - share_start, share_status, share_stop;
  - import_start ({code | link}, phone_name), import_status, import_cancel;
  - share_play_sound, import_listen.
- **share_play_sound {stop}:**
  - Only while a share is waiting_for_phone. It plays the share's code (plus the hotspot name and password on the
    phone's own hotspot) for up to 30 s.
  - A watcher stops it when the share moves on or the server stops; already_playing if called again.
  - The answer never has the password. It logs "played …, for an MCP client".
- **import_listen {phone_name, stop}:**
  - Refused while serving, starting or paused. Returns at once.
  - import_status gets a "listen" part: listening (seconds_left, hearing), checking, joining, import_started,
    nothing_heard, person_needed, failed or stopped.
  - import_start is refused while listening or joining; import_cancel also stops listening.
- **SoundImport:**
  - Needs the microphone, else "person needed". Listens 30 s.
  - Once heard: reachable, or no password → ImportJob.start(byMcp = true). Android 9 → person_needed. Else
    WifiJoin.join (a person taps Connect) → an import on that network, let go when it ends; no tap → person_needed
    (the person uses Listen on the import screen).
  - Only silence (≤ -60 dB) for 30 s → person_needed (the microphone may be blocked off screen).
- **import_status:** on_owners_network is checked now, not at import end; copy_on_phone; wait_seconds also ends when
  the listen phase changes.
- **Share for MCP:**
  - ShareControl binds ServerService (flag 0) and sends only open/poll/close.
  - A heads-up notification, "<phone> asks for a copy of <store>", opens ShareActivity, which joins the share.
  - Leaving the screen doesn't close an MCP share; Cancel does.
- **Import for MCP:**
  - The older-copy question waits 2 minutes, via notification channel "import_question".
  - The notification title is "Importing <store>". Retry is only by a person (Try again).
- **Help:** mcp.html (sections on, limits, share, import, sound, token, connect, problems), tooltips server.mcp
  (mentions sound) and server.mcp_token; sharing.html#sound, importing.html#listen; share.sound, import.listen;
  "[sound]" in log.html.

## 8. Tests and scripts
- **Unit tests** (`:app:testDebugUnitTest`): AuthTest, CoreTest, LogFilterTest, McpProtocolTest and the 4 sound tests.
- **Token in a terminal** (each phone has its own), pasted after the first line, which hides it:
  `stty -echo; read -r MCP_TOKEN; stty echo; export MCP_TOKEN`
- **The scripts:**
  - mcp-call.sh TOOL [JSON]: a curl MCP client, one tool per run (`tools` lists them).
  - mcp-sound-tests-1phone.sh: step C's one-phone tests (15). It leaves the server stopped.
  - mcp-curl-tests.sh: project A's one-phone tests (67).
  - mcp-tests-bundle.sh unpacks mcp-lib.sh, mcp-import-side.sh (tests 6, 9mcp, 9person, 11) and mcp-share-side.sh
    (test 10), for project A's second-phone tests.
- **Project A second-phone tests:**
  6. A real MCP import: the check code matches the other phone.
  7. A different store's copy is replaced without asking.
  8. The older-copy notification: answered, and no answer in 2 minutes keeps the copy.
  9. Cancel in the import notification, for a person's import and an MCP import.
  10. The Allow notification for an MCP share.
  11. server_start and import_start with the app off screen: works, or reports "person needed".
- **Project B second-phone tests:**
  1. Sound in a quiet and a noisy room, at several distances.
  2. Joins: already on the owner's Wi-Fi; the owner's hotspot with Connect tapped; no tap within 30 s; Android 9
     by hand; Try again on a joined hotspot; letting go after the import.
  3. Dark mode during "Connect?" with no tap: the screen shows the hotspot's name and password, with the code filled in.
  4. Phones without dual Wi-Fi lose their own Wi-Fi while joined.
  5. Cancel in the import notification.

## 9. Findings from the code
- minSdk 28, targetSdk/compileSdk 36, Java 17. Only ZXing as a dependency; org.json is used for tests only.
- Store HttpServer on TCP 6175 (127.0.0.1 and the external address). Share discovery on UDP 6176: "LSD1 " + a hash
  of the code to every broadcast address; the server replies "LSH1 <httpPort>".
- Sharing runs in the server process. ShareActivity binds ServerService (flag 0) and polls every 500 ms. A person's
  share closes when the screen is left; the screen stays on.
- Share codes: LS-XXXX-XXXX, 8 Crockford base32 characters = 5 bytes; normalize() forgives case, spaces, dashes, the
  LS prefix, O→0, I/L→1. Link `localstore-share://<IPv4>:<port>/<code>`; it carries no Wi-Fi details.
- Imported copies: files/imported/<storeId>/ + <storeId>.json, at most one per phone; swapped atomically with
  rollback (SharePackage). A copy is never replaced under a running server. The local store is never touched.
- Hotspot: Wi-Fi Direct keeps a fixed name and password (Android 10+); the local-only fallback gets new ones each
  time and needs Location on Android 9–12. Hotspot.info is non-null only on the app's own hotspot.

## 10. Android requirements, and when a person is needed
- Permissions: RECORD_AUDIO, FOREGROUND_SERVICE_MICROPHONE, FOREGROUND_SERVICE_SPECIAL_USE,
  FOREGROUND_SERVICE_DATA_SYNC, CHANGE_NETWORK_STATE, CHANGE_WIFI_STATE, POST_NOTIFICATIONS. No activity starts from
  the background: notifications open the screens.
- A person is needed for:
  - turning the MCP switch on, and the first microphone permission;
  - Allow (always);
  - Connect when joining the sharing phone's hotspot;
  - a network not joined (the password is never given to MCP);
  - an older copy replacing a newer one;
  - a background start Android refuses.

<!-- END OF mcp-session-state.md -->
