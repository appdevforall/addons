# Share Your Apps — sharing a store: a review packet

This is for an independent review of how **Local Store Server** shares a copy of a store with another seller's phone. It's self-contained: the protocol, the threat model, where the code is, what the tests cover, and the questions we most want answered. The wider design, including the reasons behind each decision, is Appendix E of `share-your-apps-design.md`.

## What it's for

An owner runs a small store of Android apps from their phone, served over local Wi-Fi to buyers' browsers. With the owner's permission, a second seller nearby can take a **copy** of that store, and serve it from their own phone. The copy:

- is served **exactly as published**, and can't be changed;
- **can't be passed on** through Share Your Apps to a third phone;
- **expires** on a date the owner chose, and is then deleted from the seller's phone.

Both phones run the same app, Local Store Server, signed with the same key. The two phones are near each other, on the same Wi-Fi network or hotspot. The owner approves each copy on their own screen, after comparing a four-digit check code with the seller's screen.

## Threat model

**Defended against:**

- **Someone else on the same network,** who can watch, block, alter, replay and inject traffic between the phones, answer broadcasts, and connect to either phone. They must not be able to obtain a copy, read the copy in transit, alter it undetectably, get a copy accepted as coming from the owner, or extend a copy's expiry.
- **A phone guessing share codes,** or asking without the owner's approval.
- **A seller forwarding the copy's files** to another phone: they must be useless there.
- **A seller editing the copy's expiry,** or moving the clock back to keep a copy.
- **A copy that's damaged, cut off or malicious,** such as one naming files outside the copy's folder.

**Not defended against, by design:**

- **Copying what a store serves.** Anything a store serves is ordinary web content, which anyone who can open it can copy and serve with other software.
- **A modified app.** Local Store Server is open source (AGPL), so a modified build can ignore any rule, such as expiry.
- **A rooted or compromised phone,** at either end.
- **Moving the clock back gradually,** five minutes at a time, to delay expiry. The rule deters; it can't prevent.
- **An owner who approves a request without comparing the check code.**

## The protocol

All numbers are big-endian. `||` is concatenation. Public keys are X.509 SubjectPublicKeyInfo DER, as `PublicKey.getEncoded()` returns. Strings are UTF-8. Base32 is RFC 4648, upper case, unpadded.

### Keys

- **The store key:** an ECDSA P-256 key pair, made once on the owner's phone, and stored in the app's private storage as files: `files/share/store-key.p8` (PKCS#8 DER) and `store-key.pub`. Not in Android Keystore. It signs every copy's description, and is the store's identity.
- **The store identity:** `base32(SHA-256(storePublicKey)[0..10])`, 16 characters.
- **One-time exchange keys:** ECDH P-256. The owner makes a new pair for each request; the seller makes a new pair for each import.

### Share codes

- **Made:** 8 random bytes from `SecureRandom`, each taken modulo 32 into Crockford's base32 alphabet, `0123456789ABCDEFGHJKMNPQRSTVWXYZ`. 256 is a multiple of 32, so there's no bias: 40 bits. Shown as `LS-XXXX-XXXX`.
- **Read:** case-insensitive; spaces, dashes and the `LS` prefix ignored; `O` read as `0`, `I` and `L` as `1`.
- **Limits:** a code lasts 10 minutes, serves one phone, and closes after 5 wrong codes, even to the right one.

### Finding the owner's phone

When the seller types a code, rather than scanning a QR code, their phone broadcasts over UDP to port 6176:

```
"LSD1 " || hex(SHA-256("localstore-discover v1" || code)[0..16])
```

where `code` is the 8 normalized characters. Only a phone showing that code answers, with `"LSH1 " || httpPort`, and the seller connects to the sender's address. A QR code holds the address itself: `localstore-share://<IPv4>:<port>/LS-XXXX-XXXX`.

### The exchange

Plain HTTP on the owner's store port, under `/share/`, which exists only while the owner's share screen is open.

1. **Seller → owner:** `POST /share/request`, JSON: `code`; `name`, the seller's phone name, up to 40 characters; and `commitment = SHA-256("localstore-commit v1" || sellerPublic)`, base64.
   The owner checks the code, then replies with `request`, 15 random bytes in base32, and `ownerKey`, the owner's one-time public key, base64. A second phone using the same code, once one is under way, is refused.
2. **Seller → owner:** `POST /share/reveal`: `request` and `sellerKey`, the seller's one-time public key. The owner checks it against the commitment; a mismatch closes the share.
3. **Both** compute the **check code**: the first 4 bytes of `SHA-256("localstore-check v1" || ownerPublic || sellerPublic || code)`, as an unsigned integer, modulo 10000, as four digits. The owner's screen shows it, with the seller's phone name, beside **Allow** and **Deny**; the seller's screen shows it too.
4. **Both** derive the **session key**: `HKDF-SHA256(ikm = ECDH(own private, their public), salt = code, info = "localstore-share v1" || ownerPublic || sellerPublic, length = 32)`, an AES-256 key.
5. **Seller:** polls `GET /share/status?request=…` each second, until *allowed*, *denied* or *expired*. Once allowed, the reply also carries the copy's expiry.
6. **Seller:** `GET /share/copy?request=…`. The owner streams the copy, encrypted as it's written. After one complete download, the share is done, and the code is dead. **A failed download may be retried while the share is allowed,** each attempt with the same session key and a fresh nonce prefix.

The commitment is there so neither side's key can be chosen after seeing the other's: without it, someone in the middle could try keys until the four digits matched.

### The encrypted stream

```
"LSC1" || prefix (8 random bytes) || chunk*
chunk = length (4 bytes) || AES-256-GCM(plaintext up to 64 KiB)
```

- **Nonce:** `prefix || chunkNumber (4 bytes)`, 12 bytes, chunks numbered from 0.
- **Associated data:** `chunkNumber (4 bytes) || last (1 byte, 1 for the final chunk)`.
- **Tag:** 128 bits.
- **The final chunk** is marked *last*, and may be empty. The reader rejects a stream that ends without it, or has data after it. A chunk that fails as an ordinary chunk is tried as the last.

### The copy

Inside the encrypted stream:

```
descriptionLength (4 bytes, at most 4 MiB) || description (JSON)
|| signatureLength (4 bytes, at most 256) || signature
|| every file's bytes, in the description's order
```

- **The description:** `format` (1), `storeId`, `storeName`, `storeKey` (the store's public key, base64), `created`, `expires` (ISO-8601, or null for never), and `files`, each a `path` and `size`.
- **The signature:** ECDSA P-256 with SHA-256, DER, over the description's exact bytes, by the store key.
- **The seller checks:** the format; that `storeId` is the identity of `storeKey`; the signature, with `storeKey`; that every path is relative, with no empty, `.` or `..` parts and no backslashes, and resolves inside the destination; sizes; enough free space; no data beyond the files. It writes to a temporary folder, renamed into place only once the last file is saved.
- **Replacing a copy:** a copy with the same `storeId` and a later `created` replaces the old one silently; an older one, or a different store's, asks the seller first.

### Expiry

- **The date** is in the signed description. The seller's server deletes the copy on that date, checking once a minute while serving, and whenever the app looks at the copy.
- **The clock rule:** the server keeps the latest time the phone has shown, in `files/share/latest-time`. A clock more than five minutes behind it expires the copy at once. Importing a copy restarts it at the current time, so a clock once set too far ahead can't condemn fresh copies.
- **Deliberately silent:** a copy deleted for the clock looks exactly like one that simply expired, on screen and in the log.

### Also on the sharing address

`GET /share/app`, while a share is open, sends the owner's own installed APK, so a phone without the app can install it. Its authenticity rests on Android's signature check at install: a phone that already has the app accepts only an update signed with the same key.

### Transport

Everything is plain HTTP. The app sets `android:usesCleartextTraffic="true"`, since Android 9 and later block an app's plain HTTP otherwise, and the owner's phone has no fixed address to allow by name. So on the network, anyone can read the share code, the seller's phone name, the store's name, both one-time public keys, the request identifier, the status replies, and the copy's size. Only the copy itself is encrypted.

## The questions we most want answered

1. **The exchange as a whole.** Is the commitment, then the reveal, then a four-digit check code compared by eye, a sound short-authentication-string protocol here? Is anything missing, such as binding the seller's phone name, or the request identifier, into the check code?
2. **Four digits.** With the commitment, is a 1-in-10,000 match enough, given that the share closes after one request, and a code after 5 wrong guesses?
3. **The key derivation.** Are the HKDF inputs right: the share code as salt, and both public keys in `info`? Is one key for one direction sound?
4. **Retried downloads.** Several streams may share a session key, told apart by a random 64-bit nonce prefix each. Is that acceptable, or should each attempt derive its own key?
5. **The stream construction.** Numbered chunks, a *last* flag in the associated data, and trying the last flag on failure: any weakness, such as truncation at a chunk boundary, or a reordering that survives?
6. **The store's identity.** The description carries its own key, so a first import trusts whatever key arrives over the check-code-authenticated channel. Later copies must match its identity. Is that trust on first use enough? What if a seller imports a copy of a *different* store with the same name?
7. **The store key at rest,** as plain files in the app's private storage, not Android Keystore. Is that acceptable for this threat model?
8. **The discovery broadcast** carries a hash of a 40-bit code, which an attacker could brute-force offline — though the code also crosses the network in plain text a moment later. Does discovery weaken anything? What can an attacker do by answering broadcasts?
9. **Plain HTTP.** Is there anything an eavesdropper learns, or can do, beyond the list above? Is the request identifier's secrecy relied on anywhere it shouldn't be?
10. **Expiry.** Beyond the gradual clock change we accept, is there an easier way to keep a copy, within an unmodified app? For example, backing up and restoring app data, clearing it, or reinstalling.
11. **The app download,** `/share/app`: any risk in offering it over plain HTTP, given Android's signature check at install?
12. **Anything else** that lets someone on the network do more than the design intends.

## Where it is in the code

In Local Store Server's `app/src/main/java/org/appdevforall/shareyourapps/server/`:

| File | What's there |
|---|---|
| `ShareCrypto.kt` | Every cryptographic operation: keys, signatures, the store identity, the commitment, the check code, the session key and HKDF, and the encrypted stream (`SealedOutputStream`, `SealedInputStream`). |
| `SharePackage.kt` | The copy: its description, signing, writing, and checking and importing it all or nothing. |
| `ShareRules.kt` | Share codes, the expiry clock, the store key file, the QR link, and applying expiry (`CopyExpiry`). |
| `ShareHost.kt` | The owner's side: one share, its states, and its limits. |
| `ShareClient.kt` | The seller's side of the exchange, and the download. |
| `ShareDiscovery.kt` | The broadcast and its answer. |
| `HttpServer.kt` | The sharing address, `shareRoute` and `shareApp`, and `stopAccepting`. |
| `ServerService.kt`, `ImportJob.kt`, `HostService.kt`, `MainActivity.kt` | Where the Android app uses all of the above. |

## What the tests cover

The server's JUnit tests, in `app/src/test/java/ShareTest.kt`, include:

- **Cryptography:** signatures, with a wrong key and altered data refused; the store identity; the commitment, and a substitute key failing it; both phones' check codes agreeing, and a different key changing them; both sides deriving the same session key, and a third party not; HKDF against RFC 5869's first test case.
- **The stream:** round trips from empty to 300 KB, across chunk boundaries; a flipped byte, a cut-off stream — including on a chunk boundary — extra data after the end, swapped chunks, and a wrong key, all failing.
- **The copy:** arriving identical, replacing an older one; refusing an altered description, another key claiming the store's identity, six kinds of unsafe path even when signed by the store, too little space, and a cut-off copy, each leaving nothing behind.
- **A whole share between two servers:** through a store with a PIN; downloads before approval refused; a second phone refused; denial; a key not matching its commitment; an expired code; five wrong guesses; discovery, right and wrong; the app download only while a share is open; cancelling before and during a download.
- **Expiry:** by date, the clock rule, the restart at import, the notice saying nothing about why, and downloads under way finishing while new connections are refused.

They pass on a phone, as well as on a desktop JVM. A whole share has also been tested by hand between two phones, one on Android 16.

## How to report

For each finding: its **severity** (*serious*, *moderate* or *minor*); **where** it is; the **problem**; a concrete **attack or failure**; the **smallest fix**; and your **confidence**. Answers to the questions above are most welcome, even where the answer is *that's fine*.
