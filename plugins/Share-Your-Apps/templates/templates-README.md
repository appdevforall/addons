# Store page templates

Pebble templates for the store page. The plugin ships its own default; these are alternatives, and an annotated copy of the default to learn from.

- `default-annotated.peb` — the default template, laid out and commented to explain how it works. It renders the same page as the default the plugin ships, so it's also a good starting point for your own.
- `theme-neon-arcade.peb` — a dark synthwave arcade: neon lettering, a sunset over a moving grid, glowing cabinets and CRT screens.
- `theme-morning-edition.peb` — a newspaper: a masthead, articles in columns with drop caps, photos that take colour when touched, and coupons for downloads. Dark mode makes it a *Night Edition*.
- `theme-aurora-deck.peb` — frosted glass cards over a moving aurora, in a deck that swipes sideways.
- `theme-clear-access.peb` — for buyers who use a screen reader or have low vision: a clear heading structure, a contents list, large high-contrast text, and descriptive download links. It passes axe-core's WCAG 2.2 AA checks.

`preview.png` shows the top of the first three alternatives on a phone.

**To use one:** copy it to your phone's Download folder. In the Share Your Apps tab's Manage Catalog, under **Advanced**, tap **Choose a template file** and pick it, then publish. To go back, tap **Load the default template**, or clear the template field, and publish again.

All of them work offline, and stop animating for anyone whose phone asks for reduced motion. *Developer notes → Writing a template* lists the Pebble rules to know.
