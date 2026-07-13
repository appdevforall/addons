package org.appdevforall.maps.domain

/**
 * Builds the MapLibre GL style JSON for the bbox-picker preview map.
 *
 * Pure string construction — no Android, no IO, no plugin context — so the (long, fiddly)
 * style document is unit-testable on its own and lives outside the Fragment. The Fragment
 * resolves the tiles URL + extracts fonts, then asks this builder for the style text.
 */
object IiabStyleBuilder {

    /** Style shown before tiles are available — a plain water-blue background. */
    private const val BACKGROUND_ONLY_STYLE =
        """{"version":8,"layers":[{"id":"background","type":"background","paint":{"background-color":"#e8f4f8"}}]}"""

    /**
     * Build the OpenMapTiles style for [pmtilesHttpUrl] (the in-process pmtiles HTTP URL,
     * without the `pmtiles://` prefix). Returns the background-only style when it's null.
     *
     * @param pmtilesHttpUrl in-process pmtiles HTTP URL, or null when tiles aren't available yet.
     * @param fontsRoot absolute filesystem path of the extracted glyph PBFs, or null — when
     *   null, labels render as tofu (MapLibre logs a benign style error) but geometry still draws.
     */
    fun buildStyle(pmtilesHttpUrl: String?, fontsRoot: String?): String {
        if (pmtilesHttpUrl == null) return BACKGROUND_ONLY_STYLE
        val pmtilesUrl = "pmtiles://$pmtilesHttpUrl"
        // Glyphs URL: file:// into the per-app cache dir, populated off-thread before the style
        // is built. MapLibre substitutes {fontstack} (URL-encoded) and {range} at fetch time;
        // the file source decodes the URL so literal-space directory names on disk resolve.
        val glyphsUrl = if (fontsRoot != null) {
            "file://$fontsRoot/{fontstack}/{range}.pbf"
        } else {
            // Fonts unavailable → labels render as tofu, but polygons/lines still render. Pick an
            // asset:// URL the host can't fulfil; MapLibre logs a benign Style error and proceeds.
            "asset://fonts/{fontstack}/{range}.pbf"
        }
        return """
{
  "version": 8,
  "name": "IIAB OpenMapTiles",
  "glyphs": "$glyphsUrl",
  "sources": {
    "openmaptiles": {
      "type": "vector",
      "url": "$pmtilesUrl",
      "attribution": "© OpenStreetMap contributors"
    }
  },
  "layers": [
    {
      "id": "background",
      "type": "background",
      "paint": { "background-color": "#e8f4f8" }
    },
    {
      "id": "landcover",
      "type": "fill",
      "source": "openmaptiles",
      "source-layer": "landcover",
      "paint": { "fill-color": "#d8e8c8", "fill-opacity": 0.7 }
    },
    {
      "id": "park",
      "type": "fill",
      "source": "openmaptiles",
      "source-layer": "park",
      "paint": { "fill-color": "#c8dba0", "fill-opacity": 0.5 }
    },
    {
      "id": "water",
      "type": "fill",
      "source": "openmaptiles",
      "source-layer": "water",
      "paint": { "fill-color": "#a8c5d3" }
    },
    {
      "id": "waterway",
      "type": "line",
      "source": "openmaptiles",
      "source-layer": "waterway",
      "paint": { "line-color": "#a8c5d3", "line-width": 1 }
    },
    {
      "id": "boundary-country",
      "type": "line",
      "source": "openmaptiles",
      "source-layer": "boundary",
      "filter": ["all", ["==", ["get", "admin_level"], 2], ["!=", ["get", "maritime"], 1]],
      "paint": {
        "line-color": "#6c6a76",
        "line-width": ["interpolate", ["linear"], ["zoom"], 2, 0.6, 6, 1.2, 12, 2.0],
        "line-opacity": 0.8
      }
    },
    {
      "id": "boundary-state",
      "type": "line",
      "source": "openmaptiles",
      "source-layer": "boundary",
      "filter": ["all", ["==", ["get", "admin_level"], 4], ["!=", ["get", "maritime"], 1]],
      "minzoom": 4,
      "paint": {
        "line-color": "#9a98a4",
        "line-width": ["interpolate", ["linear"], ["zoom"], 4, 0.4, 12, 1.2],
        "line-dasharray": [2, 2],
        "line-opacity": 0.7
      }
    },
    {
      "id": "transportation",
      "type": "line",
      "source": "openmaptiles",
      "source-layer": "transportation",
      "paint": { "line-color": "#ffffff", "line-width": ["interpolate", ["linear"], ["zoom"], 6, 0.5, 14, 2] }
    },
    {
      "id": "building",
      "type": "fill",
      "source": "openmaptiles",
      "source-layer": "building",
      "minzoom": 13,
      "paint": { "fill-color": "#d4c9b0", "fill-opacity": 0.7 }
    },
    {
      "id": "place-city-marker",
      "type": "circle",
      "source": "openmaptiles",
      "source-layer": "place",
      "filter": ["in", ["get", "class"], ["literal", ["city", "town"]]],
      "minzoom": 3,
      "paint": {
        "circle-radius": ["interpolate", ["linear"], ["zoom"], 3, 1.5, 8, 3, 12, 5],
        "circle-color": "#5a5862",
        "circle-stroke-color": "#ffffff",
        "circle-stroke-width": 1
      }
    },
    {
      "id": "place-city-label",
      "type": "symbol",
      "source": "openmaptiles",
      "source-layer": "place",
      "filter": ["in", ["get", "class"], ["literal", ["city", "town"]]],
      "minzoom": 4,
      "layout": {
        "text-field": ["coalesce", ["get", "name:latin"], ["get", "name"]],
        "text-font": ["Noto Sans Regular"],
        "text-size": ["interpolate", ["linear"], ["zoom"], 4, 10, 8, 13, 12, 16],
        "text-anchor": "top",
        "text-offset": [0, 0.6],
        "text-max-width": 8
      },
      "paint": {
        "text-color": "#2a2832",
        "text-halo-color": "#ffffff",
        "text-halo-width": 1.5
      }
    },
    {
      "id": "place-country-label",
      "type": "symbol",
      "source": "openmaptiles",
      "source-layer": "place",
      "filter": ["==", ["get", "class"], "country"],
      "minzoom": 2,
      "maxzoom": 6,
      "layout": {
        "text-field": ["coalesce", ["get", "name:latin"], ["get", "name"]],
        "text-font": ["Noto Sans Italic"],
        "text-size": ["interpolate", ["linear"], ["zoom"], 2, 9, 5, 14],
        "text-transform": "uppercase",
        "text-letter-spacing": 0.1,
        "text-max-width": 7
      },
      "paint": {
        "text-color": "#5a5862",
        "text-halo-color": "#ffffff",
        "text-halo-width": 1.5
      }
    }
  ]
}
        """.trimIndent()
    }
}
