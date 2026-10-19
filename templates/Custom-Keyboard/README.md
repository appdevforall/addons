# Custom Keyboard

A [Code on the Go](https://github.com/appdevforall/CodeOnTheGo) template that creates an Android
keyboard you can adapt to any language. It is for languages that the keyboards shipped with
Android do not cover.

The generated project is Kotlin. It builds and runs on the device with no extra plugins.

## What the project contains

| File | Role |
|---|---|
| `KeyboardService.kt` | The keyboard. Types into the focused field, handles shift, layers, enter and delete |
| `KeyboardView.kt` | Draws the keys and handles touch, long-press alternatives, key repeat and TalkBack |
| `KeyboardLayout.kt` | Reads the layout files |
| `MainActivity.kt` | Setup screen: turn the keyboard on, choose it, try it |
| `res/xml/method.xml` | Declares the app as a keyboard |
| `assets/layouts/*.json` | One file per language |

## Adding a language

Copy `assets/layouts/01-english.json` to a new file and edit it. The files load in name order;
the key beside the symbols key cycles through them, and holding it opens the system keyboard
picker.

```json
{
  "name": "English",
  "shortName": "EN",
  "locale": "en-US",
  "autoCapitalize": true,
  "layers": {
    "main":    [ ["q", "w", "e"], ["a", "s"], ... ],
    "shift":   [ ... ],
    "symbols": [ ... ]
  }
}
```

| Field | Meaning |
|---|---|
| `name` | Shown on the space bar |
| `shortName` | Shown on the language key of the layout before this one |
| `locale` | BCP 47 tag, such as `hi-IN`. Used for upper-casing |
| `autoCapitalize` | Shift at the start of a sentence, where the text field asks for it |
| `layers` | `main` is required. `shift` defaults to `main` in upper case. `symbols` is optional |

A layer is a list of rows, top to bottom, each listed left to right as the keys appear. A key
is a string, which types itself, or an object:

| Key field | Meaning |
|---|---|
| `output` | The text typed. It can be several code points: `"क्ष"` is क + ् + ष |
| `label` | What the key shows, if different from `output` |
| `popup` | Alternatives offered on long-press |
| `width` | Relative width; an ordinary key is 1 |
| `type` | `shift`, `backspace`, `enter`, `space`, `symbols`, `letters` or `language`. Omit for a character |

Rows of different total width are centred, so make the widest rows match. A layout file that
does not parse is skipped and the reason is logged: run a debug build and open App Logs, filtered
by `KeyboardLayout`.

## Design notes

- **A custom key view, not `android.inputmethodservice.KeyboardView`**, which is deprecated since
  API 29.
- **Text, not key codes.** Each key commits a string, so conjuncts, nukta forms and combining
  marks need no special handling. Delete removes one code point, as Indic keyboards expect.
- **Accessible.** Every key is exposed to TalkBack through `ExploreByTouchHelper`.
- **No prediction, dictionary or swipe typing.** Those are where large keyboards such as HeliBoard
  or FUTO spend most of their code. This template is a starting point.
- **Not direct-boot aware.** After a reboot the keyboard is unavailable until the device is
  unlocked, and Android says so when it is turned on. Supporting the lock screen needs
  `android:directBootAware` and device-protected storage for the saved language.
- **The font is a limit no keyboard can lift.** The keyboard can type any Unicode text, but other
  apps draw it with the system font. A script with no font on the device shows as boxes there.

## Build and test

```sh
uv run --directory tools/addons addons --root "$PWD" check
./scripts/build-templates.sh Custom-Keyboard
```

See [`../README.md`](../README.md), sections 10 to 12, to install it on a device.
