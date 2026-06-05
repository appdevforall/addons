# PMTiles region slicer

This package extracts the tiles for a single user-chosen region out of a large
remote [PMTiles](https://github.com/protomaps/PMTiles) v3 archive — **without
downloading the whole archive** — and writes a small, self-contained PMTiles
file containing just that region.

## Why this exists

Code on the Go targets low-end Android phones on limited or no internet. The
upstream OpenStreetMap vector tiles (and Natural Earth basemap) ship
as **multi-gigabyte global PMTiles archives**. Downloading a whole planet file
to put a city on a phone is a non-starter for the mission's bandwidth budget.

PMTiles is designed for exactly this: it's a single file whose internal
directory lets a client compute the byte ranges of just the tiles it wants and
fetch them with **HTTP range requests**. So instead of `GET planet.pmtiles`
(GBs), the slicer does many small `Range:` reads of only the bbox's tiles
(typically tens of MB), then repackages them into a fresh PMTiles archive the
generated app can read offline.

This is the single source of truth for "which tiles cover a bbox" — both the
bbox picker's **live size estimate** and the actual **download** call the same
code, so the estimate matches the download.

## How a slice works

1. **Read the header** (`PmtilesHeader`) — a 127-byte fixed block giving the
   directory + tile-data offsets and the zoom range.
2. **Walk the directory** (`PmtilesDirectory` + `Varint`) — the directory is a
   varint-encoded index of `tile_id → (offset, length)` entries, Hilbert-ordered.
3. **Map the bbox to tile IDs** (`Hilbert`) — PMTiles orders tiles along a
   Hilbert curve; the slicer converts the bbox's `(z, x, y)` tiles to the
   contiguous-ish ID ranges to look up, so it touches only directory leaves that
   can overlap the region (not the whole index).
4. **Collect intersecting tiles** (`TileEntry`) — the entries whose tiles fall
   inside the bbox, with their absolute byte offsets in the source file.
5. **Fetch only those bytes** (`RangeFetcher` / `HttpRangeByteCache`) — HTTP
   range reads against the source URL (LAN IIAB or internet), with header +
   directory reads cached so the estimate and the download don't re-fetch them.
6. **Write a new archive** (`PmtilesV3` + `PmtilesRegionSlicer`) — a valid
   PMTiles v3 file (header + rebuilt directory + packed tile data) containing
   only the region. The generated app serves it via a loopback HTTP server
   (`pmtiles://http://127.0.0.1:…`).

The **size estimate** runs steps 1–4 only (no tile-byte download) and sums the
entry lengths; results are memoized in `SliceEstimateCache` keyed by
`(sourceUrl, bbox, zoom range)` so dragging the bbox doesn't re-slice each frame.

## What's implemented

| File | Responsibility |
|---|---|
| `PmtilesRegionSlicer.kt` | Orchestrator: bbox → intersecting tiles → fetch → write region archive. Also `tilesInRegion` for the estimate. |
| `PmtilesHeader.kt` | Parse/serialize the 127-byte PMTiles v3 header. |
| `PmtilesDirectory.kt` | Parse/serialize the varint directory blobs (`tile_id → offset/length`). |
| `Hilbert.kt` | `(z, x, y) ↔ tile_id` Hilbert-curve conversion per the v3 spec. |
| `Varint.kt` | Protobuf-style unsigned varint encode/decode used by the directory. |
| `TileEntry.kt` | One region-intersecting tile: id, absolute byte offset, length, run length. |
| `PmtilesV3.kt` | Format constants + small helpers; spec reference. |
| `RangeFetcher.kt` | `read a byte range` abstraction (`HttpRangeFetcher`) + `HttpRangeByteCache` for header/directory reads. |
| `SliceEstimateCache.kt` | Process-wide memoization of slice estimates for the bbox picker. |

## Key design decisions

- **Tight Hilbert range pre-filter + zoom auto-cap** (2026-05-26): the estimate
  was slow (≥60 s) for large bboxes. Two fixes: walk tight perimeter Hilbert
  ranges per zoom (validated against our own `Hilbert` via the davidmoten
  hilbert-curve lib's `DavidmotenHilbertCompatTest`), and auto-cap the max zoom
  so total cells ≤ 100 k (a whole-world bbox drops to ~z8). Zoom is the lever
  that scales both slicer work and download size, so capping it makes any bbox
  downloadable at *some* detail level — replacing the old "region too large"
  rejection. A 1 GB hard byte cap remains the final guardrail.
- **Slice, don't bitmap.** We use Hilbert range decomposition rather than
  porting `go-pmtiles`'s bitmap approach — same correctness for our
  leaf-overlap query, ~5× less code, KB instead of MB of peak memory.
