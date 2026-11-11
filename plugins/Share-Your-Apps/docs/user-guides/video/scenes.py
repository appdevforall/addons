# Each scene: (screen ID or 'card', the callout to zoom to — or None for the whole screen, part, title, narration).
LONG = [
 ('card', None, '', 'Share Your Apps', "Share Your Apps lets you share the Android apps you build in Code On The Go with people nearby. No internet, no app store, and no accounts."),
 ('card', None, '', 'Two parts', "It has two parts. The Share Your Apps plugin, where you describe your apps and publish them. And the Local Store Server app, which serves them over Wi-Fi, or your phone's hotspot."),
 ('card', None, 'Part one', 'Creating your store', "First, creating your store."),
 ('P01', None, 'Creating your store', 'Install the plugin', "Install the plugin from Code On The Go's Addons Manager."),
 ('P02', 1, 'Creating your store', 'Name your store', "In the Share Your Apps tab, give your store a name. A description, a header picture and a PIN are optional."),
 ('P04', 2, 'Creating your store', 'Add your apps', "Add a product for each app. Each card shows what buyers can download."),
 ('P05', 3, 'Creating your store', 'Choose the APK', "Choose the app's project, and the APK that buyers will install."),
 ('P07', 4, 'Creating your store', 'Add pictures', "Add screenshots and videos, so buyers can see what they're getting."),
 ('P08', 2, 'Creating your store', 'Describe them', "Describe each picture, for buyers who use a screen reader."),
 ('P11', (1, 2), 'Creating your store', 'Publish', "Then tap Publish, and confirm."),
 ('P12', 1, 'Creating your store', 'Allow, once', "The first time, Android asks you to allow a few things. Each one is needed just once."),
 ('P15', 2, 'Creating your store', 'Change the look', "Choose a template to change the store's look, or write your own."),
 ('P13', 1, 'Creating your store', 'Done', "When it's done, every file has been checked. And the store is already being served."),
 ('card', None, 'Part two', 'Serving your store', "Next, letting people in."),
 ('S01', 1, 'Serving your store', 'Local Store Server', "Local Store Server shows what it's serving, and where."),
 ('S04', 1, 'Serving your store', 'The codes', "Tap Show QR codes for buyers. On a hotspot, the first code joins its network, and the second opens the store."),
 ('S06', 1, 'Serving your store', 'No Wi-Fi? No problem', "With no Wi-Fi around, the server makes a hotspot of its own, with a name and password you choose."),
 ('B01', 2, 'Serving your store', "The buyer's view", "Buyers see your store in their browser."),
 ('B02', 1, 'Serving your store', 'Pictures and downloads', "Each app has its pictures, its description, and its downloads. One tap, and it's on their phone."),
 ('B03', 2, 'Serving your store', 'Accessible, too', "And every store has an accessible version, plain and in large text."),
 ('S07', 1, 'Serving your store', 'When to run', "Keep serving when the screen is off, or pause to save battery."),
 ('card', None, 'Part three', 'Sharing with another seller', "Finally, sharing your store with another phone."),
 ('S08', 1, 'Sharing your store', 'Share this store', "Someone nearby can serve a copy of your store from their own phone. Tap Share this store."),
 ('S09', 1, 'Sharing your store', 'Two codes', "Their phone scans the large code. The small one lets a phone without the app download it first."),
 ('I03', 1, 'Sharing your store', 'Import a store', "On their phone, they tap Import a store, and scan the code, or type it."),
 ('I04', (1, 2), 'Sharing your store', 'Four digits', "Both phones show the same four digits, so you know you're sharing with the phone in front of you."),
 ('S10', (1, 3, 4), 'Sharing your store', 'Allow a copy', "You choose when their copy expires, and tap Allow."),
 ('I05', 2, 'Sharing your store', 'Imported', "The copy arrives, and they tap Serve it now."),
 ('I07', 2, 'Sharing your store', 'Their own PIN', "They can set their own PIN. But they can't change the store, or pass it on."),
 ('I08', 1, 'Sharing your store', 'Their buyers', "They let people in the same way, with codes of their own."),
 ('I09', 1, 'Sharing your store', 'When it expires', "And on the date you chose, the copy is deleted from their phone."),
 ('card', None, '', 'Share Your Apps', "Share Your Apps, from App Dev for All. Share what you build, with the people around you."),
]
SHORT = [
 ('card', None, '', 'Share Your Apps', "Share Your Apps: share the apps you build, with people nearby."),
 ('P11', (1, 2), 'Publish', 'From Code On The Go', "Publish your apps, right from Code On The Go."),
 ('S04', 1, 'Let people in', 'Scan to join', "Buyers scan one code to join your phone's hotspot, and another to open the store."),
 ('B01', 2, 'No internet needed', 'Your store', "No internet, and no accounts. Just your store, in their browser."),
 ('B02', 3, 'One tap', 'Download', "One tap, and the app is on their phone."),
 ('card', None, '', 'Share Your Apps', "Share Your Apps, from App Dev for All."),
]
WPS = 2.4      # words a second: 144 a minute
PAUSE = 1.1    # seconds of silence after each line
DUR = {}       # scene lengths set from a recorded narration, by each line's text; otherwise, from its words
def duration(text, minimum=3.2):
    if text in DUR: return DUR[text]
    return round(max(len(text.split()) / WPS + PAUSE, minimum), 2)

LEAD = {'LONG': 0.4, 'SHORT': 0.3}; GAP = {'LONG': 1.5, 'SHORT': 1.0}
def timed(which, segs):
    """Sets each scene's length from its recorded line: a lead-in, the line, and a pause. Returns each line's start in the video."""
    S = globals()[which]; t = 0; starts = []
    for (a, b), sc in zip(segs, S):
        d = round(max(LEAD[which] + (b - a) + GAP[which], 3.0), 2); DUR[sc[4]] = d
        starts.append(round(t + LEAD[which], 3)); t += d
    return starts, round(t, 2)
