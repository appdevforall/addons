import re
from pathlib import Path

DEFAULT_VERSION = "1.0.0"
RELEASE_VERSION = re.compile(r"^[0-9]{2}\.[0-9]{2}$")
LEGACY_MIN_VERSION = "1.0.0"
VERSION_SHAPE = re.compile(r"^[0-9]+(\.[0-9]+)*$")

SMALL_WORDS = {"a", "an", "and", "as", "at", "but", "by", "for",
               "in", "of", "on", "or", "the", "to", "up"}


def display_name(directory: str) -> str:
    return directory.replace("-", " ")


def slug(directory: str) -> str:
    return directory.lower()


def directory_is_valid(directory: str) -> bool:
    if "_" in directory or " " in directory:
        return False
    parts = directory.split("-")
    for index, part in enumerate(parts):
        if not part:
            return False
        if index > 0 and part.lower() in SMALL_WORDS:
            if part != part.lower():
                return False
        elif not part[0].isupper():
            return False
    return True


def manifest_value(addon: Path, key: str) -> str:
    f = addon / "src" / "main" / "AndroidManifest.xml"
    if not f.exists():
        return ""
    text = re.sub(r"<!--.*?-->", " ", f.read_text(), flags=re.S)
    text = " ".join(text.split())
    # match the whole element: attribute order is not guaranteed, and a
    # missed match surfaces much later as an opaque schema failure
    for element in re.findall(r"<meta-data\b[^>]*/?>", text):
        if re.search(r'android:name="%s"' % re.escape(key), element):
            value = re.search(r'android:value="([^"]*)"', element)
            return value.group(1) if value else ""
    return ""


def template_block(addon: Path) -> dict:
    """The `template` object in a template addon's addon.json.

    A template has no AndroidManifest.xml, so the three values a plugin keeps
    there live here instead (ADFA-6252). Returns {} for a plugin, so each
    accessor below can ask without knowing the addon kind.
    """
    from addons import discover
    if not discover.is_template(addon):
        return {}
    try:
        return metadata(addon).get("template") or {}
    except RuntimeError:
        return {}          # addon.json missing or invalid; check.py reports it


def addon_id(addon: Path) -> str:
    """The id the catalog publishes as `addonId`.

    `plugin.id` has no meaning for a template, which declares `template.id`.
    """
    block = template_block(addon)
    if block:
        return block.get("id", "")
    return manifest_value(addon, "plugin.id")


# The catalog field was renamed to addonId at schemaVersion 2; this alias keeps
# the older call sites working while they are updated.
plugin_id = addon_id


def version(addon: Path) -> str:
    """The version a user sees for this addon.

    A literal in the manifest is not substituted, so it ships verbatim and
    wins. Otherwise the builder replaces ${pluginVersion} with versionName
    plus a build-timestamp suffix; the catalog reports the semantic part,
    since the suffix changes on every build and carries no meaning.

    A template declares it in addon.json: there is no manifest and no build.
    """
    block = template_block(addon)
    if block:
        return block.get("version", DEFAULT_VERSION)
    declared = manifest_value(addon, "plugin.version")
    if declared and not declared.startswith("${"):
        return declared
    build = addon / "build.gradle.kts"
    if build.exists():
        text = build.read_text()
        # the gradle plugin resolves the placeholder from the extension
        # first, then versionName, then its own default
        for pattern in (r'pluginVersion\s*=\s*"([^"]+)"',
                        r'versionName\s*=\s*"([^"]+)"'):
            found = re.search(pattern, text)
            if found:
                return found.group(1)
    return DEFAULT_VERSION


def min_app_version(addon: Path) -> str:
    """The lowest Code on the Go release this addon declares.

    The app's release version is YY.ww. Thirteen addons still carry the
    legacy "1.0.0" placeholder, which states no real minimum, so it is
    reported as no minimum rather than invented.

    A template declares it as template.minAppVersion in addon.json.
    """
    block = template_block(addon)
    if block:
        declared = block.get("minAppVersion", "")
        return "" if declared == LEGACY_MIN_VERSION else declared
    declared = manifest_value(addon, "plugin.min_ide_version")
    if not declared or declared == LEGACY_MIN_VERSION:
        return ""          # the legacy placeholder states no real minimum
    return declared


def metadata(addon: Path) -> dict:
    """addon.json, with a named error rather than a bare traceback (R16/R47)."""
    import json
    f = addon / "addon.json"
    if not f.exists():
        raise RuntimeError(f"{addon.name}: addon.json is missing")
    try:
        return json.loads(f.read_text())
    except json.JSONDecodeError as error:
        raise RuntimeError(f"{addon.name}: addon.json is not valid JSON: {error}")
