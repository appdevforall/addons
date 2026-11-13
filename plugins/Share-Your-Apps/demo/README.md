# Demo store

A catalog of the ten most downloaded apps on F-Droid, with App Dev for All's Code on the Go and Knowledge to Go, for testing and demonstrating Share Your Apps. Code on the Go is listed twice, as its 32-bit and 64-bit versions, and Knowledge to Go three times, as its 32-bit, 64-bit and universal versions, each saying which phones it's for. **Every download is a placeholder**, a tiny file that says so, and the store's description says so too.

- `catalog.json` — the catalog: fifteen products, with pictures, descriptions and links.
- `Documents/ShareYourAppsDemo/` — the placeholder APKs and source folders the catalog points to.
- `Pictures/ShareYourAppsDemo/code-on-the-go/` — two videos for Code on the Go: a 15-second title clip with sound, made for this demo, and a Sketch to UI tutorial.
- `get-demo-pictures.sh` — fetches the other pictures onto the phone: each F-Droid app's icon and two screenshots, read fresh from its F-Droid page, and our apps' icons from their repositories. It never downloads an APK or source code.

## Setting it up

In Code On The Go's terminal, from this `demo` folder:

1. Copy the placeholders and videos to the top of shared storage:
   ```
   cp -r Documents Pictures /storage/emulated/0/
   ```
2. Fetch the pictures. It should end with `All pictures fetched.`:
   ```
   sh get-demo-pictures.sh
   ```
3. Install the catalog. The plugin reads its catalog when the Share Your Apps tab first opens, and writes it back when you leave the tab after a change, so do this **after a fresh start of Code On The Go, before opening the tab**:
   ```
   D=~/.cg/plugins/org.appdevforall.shareyourapps/data
   cp "$D/catalog.json" /storage/emulated/0/Download/catalog-backup.json
   cp catalog.json "$D/catalog.json"
   ```
   On older installations, `.cg` may be called `.androidide`.
4. Open the Share Your Apps tab. It should show **Top Android apps**, with fifteen products. Publish.

To go back to your own store, restore `Download/catalog-backup.json` the same way, after a fresh start.
