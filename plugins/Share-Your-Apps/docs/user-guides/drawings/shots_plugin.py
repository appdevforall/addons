from kit import *
SHOTS = []
def shot(id, section, title, html, key, how, real=False):
    SHOTS.append(dict(id=id, guide='plugin', section=section, title=title, html=html, key=key, how=how, real=real))

PIN_NOTE = "Up to 6 digits. Buyers enter it before reaching the store. It isn't real security."
shot('P01', 'Installing the plugin', "Code On The Go's Addons Manager, installing the plugin",
    android_dialog('Install plugin?', 'Share Your Apps 1.0.0<br>org.appdevforall.shareyourapps<br><br>Shares the apps you build with people nearby, over Wi-Fi or a hotspot.',
                   [('Cancel', None), ('Install', 1)], note="Code On The Go draws this: replace with a real screenshot."),
    [(1, 'Install the plugin.')],
    'In Code On The Go, open the Addons Manager, and choose the Share Your Apps .cgp file. Capture the screen that asks to install it.', real=True)
shot('P02', 'Your store', 'The Store section',
    plugin_screen(section('Store'),
        co(1, label('Store name') + field('Top Android apps')),
        co(2, label('Store description (HTML, optional)') + field('<p>Apps made on this phone, free to try.</p>', mono=True, lines=2)),
        co(3, label('Header image') + row(f'<span style="flex:1;font:13px Roboto;color:{GREY}">Pictures/header.png</span>', btn('Choose', grow=False), btn('Default', grow=False))),
        co(4, row(f'<span style="font:600 15px Roboto;color:{WHITE}">PIN (optional)</span>', field('1234', width=110), gap=14) + hint(PIN_NOTE)),
        show_bottom=False),
    [(1, "The store's name, at the top of its page."), (2, 'An optional description, in HTML.'), (3, '<b>Choose</b> a header picture or video, or <b>Default</b> for the built-in one.'), (4, 'An optional PIN, of up to 6 digits.')],
    'Open the Share Your Apps tab, and scroll to the top. Fill in a name, a description and a PIN, so each field shows something.')
shot('P03', 'Store description', 'A description with an HTML problem',
    plugin_screen(section('Store'), label('Store description (HTML, optional)'),
        field('<p>Apps made on this phone, <b>free to try.</p>', mono=True, lines=2),
        co(1, hint('&lt;b&gt; is opened but never closed.', RED)), show_bottom=False),
    [(1, "The warning: here, a bold tag that's never closed. It won't stop a publish, but may spoil the page.")],
    'In the store description, type <p>Hello <b>world</p>, leaving the <b> unclosed. Capture the red warning under the field.')
shot('P04', 'Products', 'The product list',
    plugin_screen(row(f'<span style="flex:1;font:500 14px Roboto;letter-spacing:.6px;color:{SECTION}">PRODUCTS (3)</span>', co(1, btn('+ Add product', grow=False), inline=True)),
        co(2, card(product_head('Todo App', 'Source ✓  APK ✓ 4.2 MB'))),
        card(product_head('Weather Now', 'APK ✓ 6.8 MB')),
        co(3, card(product_head('Great Scott 2', 'draft', in_store=False))), show_bottom=False),
    [(1, 'Add a product.'), (2, "A product in the store, with what buyers can download. Tap it to open it."), (3, "<i>draft</i>: nothing to download, so it's left out.")],
    'In the Share Your Apps tab, scroll to Products, with at least two products, one of them a draft: no APK chosen, and Publish the source unticked.')
shot('P05', 'Project and APK', "A product's name, project and APK",
    plugin_screen(card(product_head('Todo App', 'Source ✓  APK ✓ 4.2 MB'),
        co(1, label('Name') + field('Todo App')),
        co(2, label('Project') + row(f'<span style="flex:1;font:13px Roboto;color:{GREY}">TodoApp</span>', btn('Choose', grow=False))),
        co(3, label('APK') + row(f'<span style="flex:1;font:13px Roboto;color:{GREY}">…/release/app-release.apk · 4.2 MB</span>', btn('Choose', grow=False))),
        co(4, check('Publish the APK', True) + check('Publish the source', True))), show_bottom=False),
    [(1, "The name buyers see."), (2, "The Code On The Go project it comes from."), (3, 'The APK buyers download.'), (4, 'What buyers can download: the app, its source, or both.')],
    'Open a product, and scroll to its top: its name, project, APK and the two checkboxes.')
shot('P06', 'Project and APK', 'Choosing an APK, with an unsigned build',
    dark_dialog('Choose an APK',
        co(1, f'<div style="padding:10px 0;border-bottom:1px solid {BEDGE}"><p style="margin:0;font:15px Roboto;color:{WHITE}">release · app-release.apk</p>{hint("4.2 MB · built Sep 27, 2026 4:12 PM")}</div>'),
        co(2, f'<div style="padding:10px 0;border-bottom:1px solid {BEDGE}"><p style="margin:0;font:15px Roboto;color:{WHITE}">release · app-release-unsigned.apk</p>{hint("4.1 MB · built Sep 27, 2026 3:58 PM")}{hint("⚠ Unsigned: buyers can&#39;t install it", RED)}</div>'),
        f'<div style="padding:10px 0"><p style="margin:0;font:15px Roboto;color:{WHITE}">debug · app-debug.apk</p>{hint("5.0 MB · built Sep 26, 2026 11:20 AM")}</div>',
        buttons=('Cancel',)),
    [(1, 'A signed build: choose this kind.'), (2, "An unsigned build, marked: buyers can't install it.")],
    'In a product, tap Choose beside APK, for a project that has both a signed and an unsigned build.')
shot('P07', 'Pictures and video', 'Pictures and video',
    plugin_screen(card(label('Pictures and video (3)'),
        co(1, row(f'<span style="flex:1;font:14px Roboto;color:{WHITE}">chat.png · described</span>', btn('Describe', grow=False), btn('Remove', grow=False))),
        row(f'<span style="flex:1;font:14px Roboto;color:{WHITE}">settings.png</span>', co(2, btn('Describe', grow=False), inline=True), co(3, btn('Remove', grow=False), inline=True)),
        row(f'<span style="flex:1;font:14px Roboto;color:{WHITE}">demo.mp4 · described · captions</span>', btn('Describe', grow=False), btn('Remove', grow=False)),
        co(4, btn('+ Add media'))), show_bottom=False),
    [(1, '<i>described</i>: it has a description.'), (2, "<b>Describe</b>: say what it shows, and, for a video, add captions."), (3, '<b>Remove</b> it from the product.'), (4, 'Add pictures, videos or sounds.')],
    "Open a product with two pictures and a video, one picture described and the video with captions.")
shot('P08', 'Pictures and video', 'Describing a picture',
    dark_dialog('Describe <i>chat.png</i>',
        co(1, hint("What this item shows, for visually-impaired people. Say what matters for the user choosing the app, not what kind of item it is.")),
        co(2, f'<div style="margin-top:10px;border-bottom:2px solid {PUB};padding:8px 2px;font:15px Roboto;color:{WHITE}">The chat screen, with two messages</div>'),
        buttons=('Cancel', 'Save')),
    [(1, 'What to write.'), (2, 'The description, which screen readers read aloud in place of the picture.')],
    'Tap Describe beside a picture, and type a description, before tapping Save.')
shot('P09', 'Pictures and video', 'Captions for a video',
    dark_dialog('Describe <i>demo.mp4</i>',
        hint("What this item shows, for visually-impaired people. Say what matters for the user choosing the app, not what kind of item it is."),
        f'<div style="margin-top:10px;border-bottom:2px solid {PUB};padding:8px 2px;font:15px Roboto;color:{WHITE}">Adding a task, then ticking it off</div>',
        co(1, hint('Captions: demo.vtt')),
        row(co(2, btn('Choose captions file'), grow=True), co(3, btn('No captions'), grow=True)),
        buttons=('Cancel', 'Save')),
    [(1, 'The captions file chosen.'), (2, 'Choose a WebVTT captions file, ending .vtt.'), (3, 'Remove the captions.')],
    'Tap Describe beside a video, then Choose captions file, and pick a .vtt file.')
shot('P10', 'Order, and removing a product', "A product's description, order and removal",
    plugin_screen(card(co(1, label('Description (HTML, optional)') + field('<p>A to-do list that works offline.</p>', mono=True, lines=2)),
        co(2, label('Extra JSON (optional)') + field('{\n  "whatsNew": "Faster sync",\n  "license": "MIT"\n}', mono=True, lines=4)),
        row(co(3, btn('Move up'), grow=True), btn('Move down'), co(4, btn('Remove'), grow=True))), show_bottom=False),
    [(1, 'What buyers read about it, in HTML.'), (2, 'Optional details: whatsNew, license, author and website.'), (3, '<b>Move up</b> and <b>Move down</b> set the order buyers see.'), (4, '<b>Remove</b> the product, after asking.')],
    "Open a product, and scroll to its end: description, Extra JSON, and the three buttons.")
shot('P11', 'Publishing', 'Confirming a publish',
    dark_dialog('Publish Top Android apps?',
        co(1, f'<p style="margin:0 0 10px;font:15px/1.45 Roboto;color:{GREY}">It will have 12 products.</p>'),
        co(2, f'<p style="margin:0 0 10px;font:15px/1.45 Roboto;color:{GREY}">Left out, with nothing to download: Great Scott 2.</p>'),
        f'<p style="margin:0;font:15px/1.45 Roboto;color:{GREY}">Buyers keep the current version until the new one is ready.</p>',
        buttons=('Cancel', 'Publish')),
    [(1, 'How many products the store will have.'), (2, "Any left out, and why.")],
    'Tap Publish, and capture the dialog before choosing.')
shot('P12', 'Publishing', "Android asking to allow installing apps",
    android_dialog('Allow Code On The Go to install unknown apps?', 'Your phone and personal data are more vulnerable to attack by unknown apps.', [('Cancel', None), ('Settings', 1)]),
    [(1, 'Open the setting, and allow Code On The Go to install apps. Needed once, for Share Your Apps Server.')],
    'On a phone where Code On The Go has never installed an app, publish for the first time, and capture Android\'s prompt.', real=True)
shot('P13', 'What the messages mean', 'A finished publish',
    plugin_screen(section('Advanced'), label('Extra JSON (optional)'), field('{\n  "announcement": "New this week"\n}', mono=True, lines=3),
        status='Sep 27, 4:21:05 PM · Published Store_48213576 — 31 files verified.\nLeft out: Great Scott 2 has no APK chosen, and Publish the source isn\'t ticked.\nNote: "F-Droid" APK isn\'t signed, so buyers can\'t install it: f-droid.apk.', status_co=1),
    [(1, "The result: <i>Published … files verified</i>, then any left out, and any notes.")],
    'Publish, and capture the bottom of the tab when it finishes, with at least one product left out.')
shot('P14', 'What the messages mean', "A publish that couldn't finish",
    plugin_screen(section('Advanced'), label('Extra JSON (optional)'), field('{ }', mono=True, lines=1),
        status='Sep 27, 4:25:40 PM · Couldn\'t publish: "Todo App" APK not found: TodoApp/app/build/outputs/apk/release/app-release.apk. Nothing was changed; the store is still serving the previous version.', error=True, status_co=1),
    [(1, "What went wrong. Nothing changed: buyers keep the previous store.")],
    'Move a product\'s chosen APK away, then publish, and capture the red message.')
shot('P15', 'Changing the store\'s look', 'Templates and Extra JSON',
    plugin_screen(section('Advanced'),
        co(1, label('Template override (Pebble, optional)') + field('{% extends "default" %}\n{% block announcement %}<p class=news>Hello</p>{% endblock %}', mono=True, lines=3)),
        row(co(2, btn('Choose a template file'), grow=True), co(3, btn('Load the default template'), grow=True)),
        hint("When this isn't empty, it replaces the default template."),
        co(4, label('Extra JSON (optional)') + field('{\n  "announcement": "New this week",\n  "accent": "#1E88E5"\n}', mono=True, lines=4)),
        hint('Passed into the template as extra.'), show_bottom=False),
    [(1, 'Your own template, or empty for the built-in one.'), (2, 'Choose a template file, such as an alternative.'), (3, 'Start from the built-in template, or go back to it.'), (4, "Settings for the whole store: an announcement, a colour, the page's language.")],
    'In the Share Your Apps tab, scroll to Advanced, with a template and Extra JSON filled in.')
shot('P16', 'Getting help', 'Long-press help, in the Share Your Apps tab',
    plugin_screen(section('Store'), f'<div style="position:relative">{label("PIN (optional)")}</div>',
        co(1, f'<div style="background:#2B2E36;border:1px solid {BEDGE};border-radius:12px;padding:12px 14px;margin:4px 0 10px">'
              f'<p style="margin:0 0 6px;font:14px/1.45 Roboto;color:{WHITE}">Optional: buyers type this to open the store.</p>'
              + co(2, f'<p style="margin:0;font:14px Roboto;color:{BLUE};text-decoration:underline">See more</p>', inline=True) + '</div>'),
        show_bottom=False),
    [(1, 'A short explanation, after long-pressing a heading.'), (2, '<b>See more</b>, for detail and a help page.')],
    "Long-press the PIN (optional) heading. Code On The Go draws this: capture its real look.", real=True)
