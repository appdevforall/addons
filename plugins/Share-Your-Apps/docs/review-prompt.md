Please review the attached design document, `share-your-apps-design.md`, for **Share Your Apps**: a plugin for Code On The Go, an Android IDE that runs on phones, plus a companion Android app, **Local Store Server**. Together they let a phone's owner publish the apps they've built — APKs, source, or both — to a small web store that people nearby open in their phone's browser, over the same Wi-Fi network or the owner's phone's own hotspot.

## Where things stand

Everything in the document is built, except Appendix D, which is only a plan. It has been tested by hand on real phones, including a second phone as a buyer, and both projects have JUnit tests that pass on a phone. The main design is with testers. **Sharing a store with another seller**, Appendix E, is newer: it's built, and tested by hand between two phones, one on Android 16, but not yet with testers. So I'm not asking for a new design. I want to know **what's wrong with this one**: things that will break, mislead someone, or cause support trouble.

The document has the design itself, *Developer notes* near the end, and five appendices. Appendix A is the default page template. Appendix B is wireframes that came before the build; the list at its top says where the build differs. Where a wireframe and the design disagree, the design is right, so don't report those differences. Appendix C holds the other store page templates, D a plan for extending templates, and E sharing a store.

**The cryptography of sharing has its own review,** by someone who reviews cryptographic protocols, using `share-protocol-review.md`. Please don't duplicate it: the key exchange, the check code, key derivation, the encrypted stream and the copy's signature are covered there. Everything else about sharing is in scope here — its screens, its states and failure modes, its Android behaviour, and how it fits the rest of the design.

## Decisions not to re-argue

These were made deliberately. Mention one only if you find a concrete consequence the document doesn't already acknowledge.

- **Simplicity over robustness machinery.** The design was cut down on purpose from an elaborate first draft. Don't propose adding back backups, rollback schemes, sessions, or a reconciliation pass unless you can show a specific failure the current design mishandles.
- **The scope and background section's non-goals:** no payment, no accounts, no internet, no buyer tracking; buyers need only a browser.
- **The known limitations:** plain HTTP, so the PIN crosses the network unencrypted; no minimum PIN length; no limit on PIN guesses; published files in shared storage, readable by other apps on the phone; no start after the phone restarts; downloads cut off when a publish switches directories.

- **Sharing's decisions,** in Appendix E: a copy is read-only, and served exactly as published; it can't be passed on through Share Your Apps, which isn't copy protection, and says so; each seller sets their own PIN; a phone serves one store at a time, and holds at most one copy; a phone serving its own store can't import; publishing serves the owner's own store, keeping any copy; the owner keeps no record of copies, and a copy no record of where it came from; copies expire on the owner's date, a month by default; a clock set back more than five minutes expires a copy at once, silently, looking exactly like an ordinary expiry; importing restarts the clock; plain HTTP between the phones, since the copy has its own encryption; a phone without the app can download it from the owner's share screen.

## What to look for

- **Correctness.** Behaviour that's described wrongly, or that can't work as described.
- **Failure modes.** What happens when a publish is interrupted, storage fills up, Android kills a process, a permission is revoked or never granted, the hotspot disappears, the port is taken, or two things happen at once. Say whether the document handles each case.
- **Android specifics.** The apps support Android 9 through 16 (API 28–36). Check the design against platform rules that have changed across those versions, such as foreground-service types and background-start limits, storage access, Wi-Fi Direct and local-only hotspot behaviour, package installation, and permission changes. **Where you're not sure what Android actually does, say so, rather than stating it as fact.**
- **Security, within the stated model.** Anything that lets someone on the same network do more than the design intends. The obvious limits are already accepted; look for ones that aren't. Examples: reading files outside the store, forging authentication, or tampering with what's published.
- **Contradictions and ambiguity.** Sections that disagree with each other, or leave a question that a developer implementing or changing the design would have to guess at.
- **Sharing a store.** What happens when a share or an import is interrupted — by rotation, leaving the screen, the process being killed, the network changing, or either phone sleeping — or two things happen at once, such as publishing while a copy is served, or importing while one is. Whether the screens tell a seller what they need, and nothing they can't act on. Whether expiry really deletes a copy in every path, and can't be sidestepped within the app, such as by clearing its data, backing it up, or reinstalling.
- **Test gaps.** Important behaviour that neither the automated tests nor the acceptance checklist covers.
- **The developer notes.** Instructions that are wrong, incomplete, or likely to trip someone up.

## How to report

Give at most **15 findings**, most important first. For each:

1. **Severity:** *blocker* (it doesn't work, or it loses data or exposes something), *high*, *medium* or *low*.
2. **Where:** the section name.
3. **The problem,** in a sentence or two.
4. **A concrete scenario** showing it — the steps, or the situation in which it happens.
5. **The smallest fix,** in keeping with the design's simplicity.
6. **Confidence:** *certain*, *likely* or *unsure*, especially for claims about Android's behaviour.

After the findings, list any **questions** where the document is ambiguous and you couldn't tell what's intended.

Please don't rewrite the document, summarize it back, or list what looks fine. Plain prose within each finding is best, without nested bullet lists.
