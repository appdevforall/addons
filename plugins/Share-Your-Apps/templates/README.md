# Store page templates

Pebble templates for the store page. The plugin ships its own default; these are alternatives, and an annotated copy of the default to learn from.

- `default-annotated.peb` — the default template, laid out and commented to explain how it works. It renders the same page as the default the plugin ships, so it's also a good starting point for your own.
- `theme-neon-arcade.peb` — a dark synthwave arcade: neon lettering, a sunset over a moving grid, glowing cabinets and CRT screens.
- `theme-morning-edition.peb` — a newspaper: a masthead, articles in columns with drop caps, photos that take colour when touched, and coupons for downloads. Dark mode makes it a *Night Edition*.
- `theme-aurora-deck.peb` — frosted glass cards over a moving aurora, in a deck that swipes sideways.
- `theme-clear-access.peb` — for buyers who use a screen reader or have low vision: a clear heading structure, a contents list, large high-contrast text, and descriptive download links. It passes axe-core's WCAG 2.2 AA checks.

`preview.png` shows the top of the first three alternatives on a phone.

**To use one:** copy it to your phone's Download folder. In the Share Your Apps tab's Manage Catalog, under **Advanced**, tap **Choose a template file** and pick it, then publish. To go back, tap **Load the default template**, or clear the template field, and publish again.

Every publish also makes an accessible version of the store page from Clear Access, and each of the others links to it from its top left as *Accessible version*; your own templates can show the same link, from `accessibleHref`. All of them also show what the plugin reads from each APK — its version, the Android it needs, and which phones it's for — and the documented Extra JSON keys: the store's `announcement`, `contact` and `accent`, and each product's `whatsNew`, `license`, `author` and `website`. `default-annotated.peb` lists everything a template receives. All of them pass axe-core's WCAG 2.2 AA checks, work offline, and stop animating for anyone whose phone asks for reduced motion. *Developer notes → Writing a template* lists the Pebble rules to know.

## Extending the default, and a template library

You needn't copy a whole template to change one part of it. A template can **extend** the default, and replace only the blocks it wants: `styles`, `header`, `announcement`, `card`, `downloads` or `footer`. For example, to change just each product's download buttons:

```
{% extends "default" %}
{% block downloads %}<a class="b b1" href="{{ p.apk.src }}">Get it</a>{% endblock %}
```

Inside a block, `{{ parent() }}` keeps the original, so you can add to a part rather than replace it — such as extra styles: `{% block styles %}{{ parent() }}<style>h1{color:teal}</style>{% endblock %}`.

Templates of your own can also share pieces, kept in the **template library**, the folder `CodeOnTheGoProjects/ShareYourAppsTemplates/`:

- **`{% include "footer-note" %}`** inserts `footer-note.peb` from the library. Folders work too: `"parts/note"`.
- **`{% import "macros" %}`**, or `{% from "macros" import badge %}`, makes the macros in `macros.peb` available — your own reusable functions, written in Pebble:
  ```
  {% macro badge(text) %}<b class=badge>{{ text }}</b>{% endmacro %}
  ```

Names are always relative to the library's top, and can't leave it. A template that names one that isn't there doesn't publish, and the message names it. `default-annotated.peb` shows where each block is.

## Built-in functions and filters

Templates can also use these, without any code of your own:

- **`{{ t("download", "Download") }}`** — text in the store's language, from a table in the store's Extra JSON: `{"strings": {"sw": {"download": "Pakua"}}}`. Without a match in the store's language, it tries English, then the fallback you give, then the key itself.
- **`{{ plural(apps|length, "# app", "# apps") }}`** — *1 app*, *3 apps*: `#` becomes the number.
- **`{{ p.whatsNew|markdown }}`** — a little Markdown, turned into HTML: paragraphs, `- ` lists, `**bold**`, `*italic*`, `` `code` `` and `[links](https://…)`. Any HTML in the text is shown as text, never run.
- **`{{ qr("http://192.168.49.1:6175/", "Scan to open the store") }}`** — a QR code, as an image, with a label for screen readers.
- **`{{ p.apk.sizeBytes|bytes }}`** — a size, such as *5.0 MB*. **`{{ p.name|slugify }}`** — text as a URL-safe name.

Pebble's own filters work too, such as `abbreviate(40)` to shorten text, `upper`, `date` and `default`.

## Picture descriptions and captions

In Manage Catalog, **Describe**, beside each picture or video, lets you say what it shows, for buyers who use a screen reader, and add captions to a video: a WebVTT file, ending `.vtt`. In a template, each item of `p.carousel` has:

- **`m.alt`** — the description, or, without one, which picture it is: *Termux, picture 1 of 3*. Use it for `alt` on pictures and `aria-label` on videos and sounds.
- **`m.description`** — the description alone, or nothing, if you want to show it as a caption under the picture.
- **`m.captions`** — for a video with captions, the link to its captions file:
  `{% if m.captions is not empty %}<track kind="captions" src="{{ m.captions }}" srclang="{{ lang }}" label="Captions" default>{% endif %}`

Every built-in template uses all three.

