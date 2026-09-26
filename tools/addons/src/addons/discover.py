from pathlib import Path

PREDICATE = "com.itsaky.androidide.plugins.build"

# The four addon areas, plus the repository root for addons not yet migrated.
# catalog.TYPES maps the same four; keep them in step.
AREAS = ("plugins", "templates", "snippets", "code-actions")


def read_skip(root: Path, only_never_build: bool = False) -> set[str]:
    """Names excluded from the gallery.

    A leading "!" means the module does not build at all, so it is excluded
    from compile coverage too. Everything else is merely held back from
    publishing and must still compile.
    """
    f = root / "tools" / "addons" / "skip.txt"
    if not f.exists():
        return set()
    names = set()
    for line in f.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        name = line.split()[0]
        never_build = name.startswith("!")
        # every entry is held out of the gallery; only "!" entries are also
        # held out of compile coverage
        if never_build or not only_never_build:
            names.add(name.lstrip("!"))
    return names


def is_template(addon: Path) -> bool:
    """A template addon is a directory under templates/ holding templates.json.

    Deliberately not the plugin rule. A template has no Gradle build, nothing
    to compile and no .cgp — it zips to a .cgt (ADFA-6252). Keying on
    templates.json rather than on the area alone means templates/ can also hold
    documentation without every markdown file becoming an addon.
    """
    return addon.parent.name == "templates" and (addon / "templates.json").is_file()


def find_templates(root: Path, skip: set[str]) -> list[Path]:
    return [f.parent for f in root.glob("templates/*/templates.json")
            if f.parent.name not in skip]


def find_addons(root: Path, only: list[str] | None = None,
                include_skipped: bool = False,
                plugins_only: bool = False) -> list[Path]:
    """Every addon, or only the ones with a Gradle build.

    plugins_only exists for callers that run Gradle over the result --
    scripts/update-libs.sh does, and a template has no build.gradle.kts for
    assemblePlugin to act on. Keeping the rule here rather than in a shell
    filter means there is one definition of what a template is.
    """
    skip = (read_skip(root, only_never_build=True) if include_skipped
            else read_skip(root))
    found = []
    # The root glob still has work to do: cotg-ndk and
    # pebble-custom-function-template-installer live beside libs/ and tools/,
    # not under an area. ADFA-6167 moved the five AI addons under plugins/;
    # these two are what is left. Drop it once they move too.
    patterns = ["*/build.gradle.kts"] + [f"{a}/*/build.gradle.kts" for a in AREAS]
    for pattern in patterns:
        for f in root.glob(pattern):
            if PREDICATE in f.read_text(errors="ignore") and f.parent.name not in skip:
                found.append(f.parent)
    if not plugins_only:
        found.extend(find_templates(root, skip))
    found = sorted(found, key=lambda p: p.name)
    if only is None:
        return found

    # accept either the repo-relative path or the bare directory name
    wanted, chosen = list(only), []
    for name in only:
        match = next((p for p in found
                      if p.name == name
                      or p.relative_to(root).as_posix() == name), None)
        if match is None:
            raise RuntimeError(f"{name}: not a known addon")
        chosen.append(match)
    return sorted(set(chosen), key=lambda p: p.name)
