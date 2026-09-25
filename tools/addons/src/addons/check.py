import json
import re
from pathlib import Path

import jsonschema

from addons import catalog, discover, model

SCHEMA = json.loads((Path(__file__).parent / "addon.schema.json").read_text())


# Global Constraint 6: the product name is written in full in anything a user
# reads. These pages are published, so the rule reaches them.
SHORTHAND = ("CodeOnTheGo", "CoGo", "CotG")
NOT_PROSE = re.compile(
    r"<code[^>]*>.*?</code>|<pre[^>]*>.*?</pre>|href=\"[^\"]*\"|src=\"[^\"]*\"",
    re.S | re.I)


def check_template(path: Path, directory: str) -> list[str]:
    """The structural rules a .cgt bundle source must satisfy (ADFA-6252).

    These are the failures the IDE handles in silence. A path in templates.json
    that does not exist, or a template directory with no template.json, is
    skipped at load with no error shown to the user, so the template simply
    never appears. Catch both here.
    """
    problems = []
    index = path / "templates.json"
    try:
        data = json.loads(index.read_text())
    except json.JSONDecodeError as error:
        return [f"{directory}: templates.json is not valid JSON: {error}"]

    listed = data.get("templates")
    if not isinstance(listed, list) or not listed:
        return [f"{directory}: templates.json lists no templates"]

    for item in listed:
        name = item.get("path") if isinstance(item, dict) else None
        if not name:
            problems.append(f"{directory}: a templates.json entry has no 'path'")
            continue
        # keep the archive flat and inside itself: these become zip entry names
        if "/" in name or name.startswith("."):
            problems.append(
                f"{directory}: the template path '{name}' must be a plain "
                f"directory name")
            continue
        folder = path / name
        if not folder.is_dir():
            problems.append(
                f"{directory}: templates.json names '{name}', which is not a "
                f"directory")
            continue
        meta = folder / "template" / "template.json"
        if not meta.exists():
            problems.append(f"{directory}: {name}/template/template.json is missing")
            continue
        # JSON5, not JSON: core.cgt uses unquoted keys and the IDE parses it
        # leniently, so only report what no lenient parser could read.
        text = meta.read_text()
        for key in ("name", "description"):
            if f'"{key}"' not in text and f"{key}:" not in text:
                problems.append(
                    f"{directory}: {name}/template/template.json declares no {key}")

    # Every directory that is present but unlisted is dead weight in the
    # repository and never reaches the archive, so say so.
    listed_names = {item.get("path") for item in listed if isinstance(item, dict)}
    for child in sorted(path.iterdir()):
        if (child.is_dir() and not child.name.startswith(".")
                and child.name not in listed_names):
            problems.append(
                f"{directory}: the directory '{child.name}' is not listed in "
                f"templates.json, so it is not packaged")

    if not model.template_block(path):
        problems.append(
            f"{directory}: addon.json declares no 'template' object with id, "
            f"version and minAppVersion")
    return problems


def check_names(root: Path) -> list[str]:
    problems = []
    # read once: the same contract governs every addon in the run
    slug_shape = catalog.slug_pattern(root)
    for path in discover.find_addons(root):
        directory = path.name
        name = model.display_name(directory)
        addon_slug = model.slug(directory)

        if not model.directory_is_valid(directory):
            problems.append(f"{directory}: the directory name breaks the naming rule")

        # directory_is_valid only rules on separators and capitals, so a name
        # like Code.Together passes it and then makes a slug the catalog
        # refuses. Fail here, not at the end of a publish.
        if not slug_shape.match(addon_slug):
            problems.append(
                f"{directory}: it makes the slug '{addon_slug}', which the "
                f"catalog schema refuses ({slug_shape.pattern}). Use ASCII "
                f"letters and digits, separated by single hyphens")

        template = discover.is_template(path)

        if template:
            problems += check_template(path, directory)
        else:
            build = (path / "build.gradle.kts").read_text()
            found = re.search(r'pluginName\s*=\s*"([^"]*)"', build)
            if found is None:
                problems.append(f"{directory}: build.gradle.kts declares no pluginName")
            elif found.group(1) != addon_slug:
                problems.append(
                    f"{directory}: pluginName is '{found.group(1)}', expected '{addon_slug}'")

            plugin_name = model.manifest_value(path, "plugin.name")
            if not plugin_name:
                problems.append(f"{directory}: the manifest declares no plugin.name")
            elif plugin_name != name:
                problems.append(
                    f"{directory}: plugin.name is '{plugin_name}', expected '{name}'")

        # the app keys every install on this; an empty one orphans them all
        if not model.addon_id(path):
            problems.append(
                f"{directory}: no addon id — a template declares template.id in "
                f"addon.json, a plugin declares plugin.id in its manifest")

        declared = (model.template_block(path).get("minAppVersion", "") if template
                    else model.manifest_value(path, "plugin.min_ide_version"))
        field = "template.minAppVersion" if template else "plugin.min_ide_version"
        if (declared and declared != model.LEGACY_MIN_VERSION
                and not model.RELEASE_VERSION.match(declared)):
            problems.append(
                f"{directory}: {field} is '{declared}', "
                f"expected a YY.ww release like 26.29")

        if not model.VERSION_SHAPE.match(model.version(path)):
            problems.append(
                f"{directory}: the version '{model.version(path)}' is not a "
                f"dotted number")

        # A template has no Android module, so its icons sit at the addon root.
        icon_dir = path if template else path / "src" / "main" / "assets"
        shown = "" if template else "src/main/assets/"
        for icon in ("icon_day.png", "icon_night.png"):
            if not (icon_dir / icon).exists():
                problems.append(f"{directory}: {shown}{icon} is missing")

        page = path / f"{addon_slug}.html"
        if not page.exists():
            problems.append(f"{directory}: the page must be named {addon_slug}.html")
        else:
            html = page.read_text()
            if f"<title>{name}</title>" not in html:
                problems.append(f"{directory}: the page title must be '{name}'")
            # the h1 is what a visitor actually reads, and a rename that only
            # touches <title> leaves the old product name on the page
            if re.search(r"<script\b", html, re.I):
                problems.append(
                    f"{directory}: the page contains a <script>; these pages "
                    f"are published to the site origin")
            handler = re.search(r"\son[a-z]+\s*=", html, re.I)
            if handler:
                problems.append(
                    f"{directory}: the page has an inline event handler "
                    f"({handler.group(0).strip()}); not allowed on a published page")

            prose = NOT_PROSE.sub(" ", html)
            for bad in SHORTHAND:
                if re.search(r"\b" + bad + r"\b", prose):
                    problems.append(
                        f"{directory}: the page says '{bad}'; write "
                        f"'Code on the Go' in full")
            heading = re.search(r"<h1[^>]*>(.*?)</h1>", html, re.S | re.I)
            if heading:
                text = " ".join(re.sub(r"<[^>]+>", "", heading.group(1)).split())
                if name.lower() not in text.lower():
                    problems.append(
                        f"{directory}: the page h1 reads '{text}', expected '{name}'")
    return problems


def check_metadata(root: Path) -> list[str]:
    problems = []
    for path in discover.find_addons(root):
        f = path / "addon.json"
        if not f.exists():
            problems.append(f"{path.name}: addon.json is missing")
            continue
        try:
            data = json.loads(f.read_text())
        except json.JSONDecodeError as error:
            problems.append(f"{path.name}: addon.json is not valid JSON: {error}")
            continue
        try:
            jsonschema.validate(data, SCHEMA)
        except jsonschema.ValidationError as error:
            problems.append(f"{path.name}: addon.json is invalid: {error.message}")
    return problems


def run(root: Path) -> list[str]:
    return check_names(root) + check_metadata(root)
