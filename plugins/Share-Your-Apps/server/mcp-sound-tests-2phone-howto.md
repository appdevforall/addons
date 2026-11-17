# How to run the two-phone MCP sound tests (mcp-sound-tests-2phone.sh)

The script tests step C's MCP sound tools on two phones: one phone shares its store and plays its code as tones, the
other listens, hears the code and imports the store. It uses mcp-call.sh (a curl MCP client) for every call.

## How it works
- You run the same script on both phones, with a different role:
  - **Sharing phone:** `sh mcp-sound-tests-2phone.sh share`
  - **Importing phone:** `sh mcp-sound-tests-2phone.sh import`
- The script tells you when to press Enter on each phone. Start listening on the importing phone first, then press
  Enter on the sharing phone straight away to play the tones.
- If nothing is heard, either side offers to try again, up to 3 times.
- Each phone saves its results beside the script, in `mcp-sound-tests-2phone-share-result.txt` or
  `mcp-sound-tests-2phone-import-result.txt`. Each run replaces that phone's file.
- At the end, the sharing phone is left **serving**. The importing phone is left **stopped**, with the imported copy
  on it. Any copy the importing phone already had may be replaced.
- Instead of running the share side, the sharing phone's person may open **Share this store…** and tap **Play sound**;
  then run only the import side.

## What it tests
**Sharing side (S1–S8):**
1. The server starts.
2. A share opens and waits for a phone.
3. `share_play_sound` plays.
4. A phone connects.
5. The tones stop by themselves once it connects.
6. The "asks for a copy" notification opens the Allow screen.
7. The copy is sent.
8. View logs has the "played" and "allowed" lines.

**Importing side (I1–I10):**
1. The server stops.
2. `import_listen` listens.
3. The code is heard.
4. The Connect prompt appears (hotspot run only).
5. The import starts by itself.
6. The check code matches the sharing phone's.
7. The import finishes.
8. `copy_on_phone` is true.
9. `on_owners_network` matches what Wi-Fi settings show (this checks open item 8 in mcp-session-state.md).
10. View logs has the "listening" and "heard" lines.

## Run it twice
- **a.** Both phones on the same ordinary Wi-Fi.
- **b.** The sharing phone on its own hotspot: Wi-Fi on but not joined to any network. The importing phone on another
  network. Android should ask the importing phone to **Connect**; tap it within 30 s (Android 10 and later).

## Limits
- **S5 can pass for the wrong reason:** if the tones had already run their full 30 s before the phone connected, it
  still passes.
- **I4 depends on your answer:** the script can see that the app tried to join the hotspot, but only you can confirm
  the Connect prompt appeared.
- **Long waits are normal:** I6 and I7 can wait up to about 12 minutes, and S7 up to about 10 minutes, while someone
  taps Allow.

## Steps
1. **Get the combined build onto the second phone.** A Standard Run only installs on the phone running Code on the
   Go. One way: on that phone's share screen, use the small "No Share Your Apps Server on the other phone?" QR code,
   which downloads the build from it. Android may refuse to install over an older build signed differently. If so,
   uninstall the old app on the second phone first. That deletes its data, including its MCP token and any imported
   copy.
2. **On the second phone:** turn MCP control on and allow the microphone (the line under the switch should have no
   "Microphone" warning). Install curl and python3 in Termux: `pkg install curl python`. Copy both `mcp-call.sh` and
   `mcp-sound-tests-2phone.sh` into one folder.
3. **On each phone:** export that phone's own token (from Copy token, under its MCP control switch), pasted after the
   first line, which hides it:
   ```
   stty -echo; read -r MCP_TOKEN; stty echo; export MCP_TOKEN
   ```
4. Run the share side first and the import side second, and follow the prompts. Turn the media volume up and hold
   the phones close.
5. Paste both result files to the agent, for run a, and for run b if you can.

## Housekeeping (from the same session)
- Swap in the new state file, if not done yet, from the Combined folder:
  ```
  cp mcp-session-state.md mcp-session-state.prev.md
  mv mcp-session-state.new.md mcp-session-state.md
  ```
- Then delete what's no longer needed (`rm -rf` can't be undone: check the names first):
  ```
  rm -rf ProjectA project-b-reference
  rm -f HANDOFF.md mcp-session-state.md.old make-combined.sh mcp-sound-tests-1phone-result.txt
  ```
- Move `qr-from-picture-plan.md` out of the "Share Your Apps Server Sound" folder before deleting that folder.
- Keep `step-c-backup/`, the step C scripts and the original "Share Your Apps Server" folder until a two-phone import by
  sound has worked.

<!-- END OF mcp-sound-tests-2phone-howto.md -->
