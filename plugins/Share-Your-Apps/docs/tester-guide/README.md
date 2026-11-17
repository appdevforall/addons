# Tester guide

`build_guide.py` builds `share-your-apps-tester-guide.pdf`, the one-page guide attached to ADFA-6207 for testers, in the store's look.

```
pip install reportlab
python3 build_guide.py
```

The PDF is written beside the script, or to the path given as its argument. It's a build output, so attach it to the ticket or release rather than committing it. The guide's text is in the script; after changing it, check the guide still fits on one page.
