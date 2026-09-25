import hashlib
import json
import os
import re
from datetime import datetime, timezone
from pathlib import Path

import jsonschema

from addons import discover, model

BASE = "https://addons.appdevforall.org"
REPO = "https://github.com/appdevforall/plugin-examples"
# overridden per run so a staging publish links the ref it was cut from
REF = os.environ.get("ADDONS_SOURCE_REF", "main")
TYPES = {"plugins": "plugin", "templates": "template",
         "snippets": "snippet", "code-actions": "code-action"}
VERSION = re.compile(r"^[0-9]+(\.[0-9]+)*$")


def slug_pattern(root: Path) -> re.Pattern:
    """The slug rule the published catalog enforces.

    Read rather than restated so a pull request check and the publish cannot
    disagree: a name the check accepts and the schema refuses fails inside
    jsonschema at the end of a publish, after every Gradle build has run.
    """
    schema = json.loads((root / "site" / "catalog.schema.json").read_text())
    return re.compile(schema["$defs"]["addon"]["properties"]["slug"]["pattern"])


def _file(path: Path, url: str) -> dict:
    data = path.read_bytes()
    return {"url": url, "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}


def artifact_suffix(addon: Path) -> str:
    """What this addon downloads as: a plugin compiles, a template zips."""
    return "cgt" if discover.is_template(addon) else "cgp"


def entry(root: Path, addon: Path, cgp: Path, archive: Path | None,
          base: str = BASE) -> dict:
    directory = addon.name
    slug = model.slug(directory)
    meta = model.metadata(addon)
    version = model.version(addon)
    if not VERSION.match(version):
        raise RuntimeError(f"{directory}: the version '{version}' is not a number")
    relative = addon.relative_to(root).as_posix()
    document = {
        "type": TYPES.get(addon.parent.name, "plugin"),
        "slug": slug,
        "addonId": model.addon_id(addon),
        "name": model.display_name(directory),
        "version": version,
        "summary": meta["summary"],
        "description": meta["description"],
        "origin": meta["origin"],
        "license": meta["license"],
        "tags": meta["tags"],
        "author": {"name": meta["author"]["name"], "url": meta["author"]["url"]},
        "minAppVersion": model.min_app_version(addon),
        "iconUrl": f"{base}/p/{slug}.png",
        "iconDarkUrl": f"{base}/p/{slug}-night.png",
        "pageUrl": f"{base}/p/{slug}.html",
        "sourceUrl": f"{REPO}/tree/{REF}/{relative}",
        "download": _file(cgp, f"{base}/dl/{slug}.{artifact_suffix(addon)}"),
    }
    # A template ships no source tarball: the .cgt is plain text throughout, so
    # the download already is the source, and sourceUrl above points at the
    # directory (ADFA-6252). sourceTarball is therefore optional from
    # schemaVersion 2 on, and consumers must tolerate its absence.
    if archive is not None:
        document["sourceTarball"] = _file(
            archive, f"{base}/src/{slug}-src.tar.gz")
    return document


def build(root: Path, dist: Path, base: str = BASE,
          only: list[str] | None = None) -> dict:
    entries = []
    for addon in discover.find_addons(root, only):
        slug = model.slug(addon.name)
        cgp = dist / f"{slug}.{artifact_suffix(addon)}"
        required = [cgp]
        archive = None
        if not discover.is_template(addon):
            archive = dist / f"{slug}-src.tar.gz"
            required.append(archive)
        for f in required:
            if not f.exists():
                raise RuntimeError(f"{addon.name}: {f.name} is missing from {dist}")
        entries.append(entry(root, addon, cgp, archive, base.rstrip('/')))

    document = {
        "schemaVersion": 2,
        "generated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "addons": entries,
    }
    schema = json.loads((root / "site" / "catalog.schema.json").read_text())
    jsonschema.validate(document, schema)
    return document
