import gzip
import re
import shutil
import subprocess
import tarfile
from pathlib import Path
from typing import NamedTuple

from addons import model

SOURCE = "https://github.com/appdevforall/plugin-examples"

# Named BUILDING.md, not README.md: 18 of the 23 addons ship their own README,
# and the one a reader wants first is the addon's, not this notice.
NOTES = """# Building {name}

Source for the {name} addon for Code on the Go.

- Origin: {origin}
- Author: {author}
- Comes from: {source}/tree/main/{directory}
- License: {license}. The full text is in LICENSE beside this file.
{prerequisites}
This folder is the project root. Open it in Code on the Go, or build it from a
desktop:

    ./gradlew assemblePlugin

The plugin file appears in `build/plugin/`.

A desktop build needs `local.properties` beside this file, with one line:

    sdk.dir=/path/to/your/Android/sdk

On a phone you do not need that file: Code on the Go puts `ANDROID_HOME` and
`ANDROID_SDK_ROOT` in the build environment.
{caveats}"""

# Two build-time requirements that only some addons carry, and that a reader
# otherwise meets as an unexplained failure: the manifest merger rejecting a
# placeholder the older on-device builder does not define, and an asset merge
# refusing to package an addon whose downloads were never fetched.
PROVENANCE_NOTE = """
Building this addon on a phone needs Code on the Go 26.37 or newer. Older
releases ship a plugin builder that does not define the build-provenance
placeholders this addon's manifest names, and the build stops in the manifest
merger.
"""

DOWNLOAD_NOTE = """
This addon downloads assets at build time. Fetch them first, as its own
invocation -- `assemblePlugin` does not run the task, and combining the two in
one command fails:

    ./gradlew downloadAssets
"""

# Stated before the build instructions (design §9.4, R46): a submodule is not
# copied, so a reader who needs it has to fetch the exact commit themselves.
SUBMODULE_NOTE = """
`{path}` is a git submodule and is not in this archive. If you
need it, clone it into place at the commit this source was published with:

    git clone {url} {path}
    git -C {path} checkout {commit}
"""

# Every shared jar is referenced as "../libs/x.jar" (or "../../libs/x.jar")
# from the addon's place in the repository. Flattening moves the addon to the
# archive root, so those references have to lose their parent hops.
PARENT_LIBS = re.compile(r"(?:\.\./)+libs/")

# Only these are rewritten. They are the only tracked files that reference the
# shared jars by path; READMEs and HTML documentation describe the repository
# and stay as they are.
REWRITTEN = ("build.gradle.kts", "settings.gradle.kts")

# The index mode git gives a submodule: a commit pointer, not a file.
GITLINK = "160000"


def jars_for(addon: Path) -> list[str]:
    text = ""
    for name in REWRITTEN:
        f = addon / name
        if f.exists():
            text += f.read_text()
    return sorted(set(re.findall(r"\.\./libs/([A-Za-z0-9._-]+\.jar)", text)))


class IndexEntry(NamedTuple):
    mode: str
    object_id: str
    path: str

    @property
    def is_submodule(self) -> bool:
        return self.mode == GITLINK


def _index(root: Path, addon: Path) -> list[IndexEntry]:
    """Every index entry under the addon, submodules included."""
    relative = addon.relative_to(root).as_posix()
    result = subprocess.run(
        ["git", "-C", str(root), "ls-files", "--stage", "-z", "--", relative],
        capture_output=True, text=True, check=True)
    entries = []
    for entry in result.stdout.split("\0"):
        if entry:
            info, path = entry.split("\t", 1)
            mode, object_id, _ = info.split(" ")
            entries.append(IndexEntry(mode, object_id, path))
    return entries


def submodule_urls(root: Path) -> dict[str, str]:
    """Submodule path -> URL, from .gitmodules (keyed by path, not name)."""
    result = subprocess.run(
        ["git", "-C", str(root), "config", "-f", ".gitmodules",
         "--get-regexp", r"^submodule\..*\.(path|url)$"],
        capture_output=True, text=True)
    # Exit 1 only means no .gitmodules or no match; anything else is a real error
    if result.returncode not in (0, 1):
        raise RuntimeError(f"reading .gitmodules failed: {result.stderr.strip()}")
    fields: dict[str, dict[str, str]] = {}
    for line in result.stdout.splitlines():
        key, value = line.split(" ", 1)
        name, field = key.removeprefix("submodule.").rsplit(".", 1)
        fields.setdefault(name, {})[field] = value
    return {f["path"]: f["url"] for f in fields.values() if "path" in f and "url" in f}


def _submodule_notes(root: Path, addon: Path, submodules: list[IndexEntry]) -> str:
    """The clone instructions for each submodule, or "" when there are none."""
    if not submodules:
        return ""
    inside = addon.relative_to(root).as_posix()
    urls = submodule_urls(root)
    notes = ""
    for entry in submodules:
        if entry.path not in urls:
            raise RuntimeError(
                f"{addon.name}: submodule {entry.path} has no url in .gitmodules")
        notes += SUBMODULE_NOTE.format(path=entry.path[len(inside) + 1:],
                                       url=urls[entry.path], commit=entry.object_id)
    return notes


def flatten(text: str) -> str:
    return PARENT_LIBS.sub("libs/", text)


def _claim(top: Path, relative: str, label: str) -> Path:
    """Reserve one path in the staging tree, or say who else wanted it.

    Flattening merges three sources into one directory -- the addon's own
    tracked files, the shared jars, and the repository's Gradle wrapper -- so a
    silent overwrite is possible in a way the two-level shape made impossible.
    """
    target = top / relative
    if target.exists():
        raise RuntimeError(f"{label}: {relative} is claimed twice")
    target.parent.mkdir(parents=True, exist_ok=True)
    return target


def _stage(root: Path, addon: Path, out: Path, meta: dict) -> Path:
    top = out / f"{model.slug(addon.name)}-src"
    if top.exists():
        shutil.rmtree(top)
    top.mkdir(parents=True)

    inside = addon.relative_to(root).as_posix()
    entries = _index(root, addon)
    # A submodule is one entry pointing at a commit, not a file, so there is
    # nothing to copy. AI-Agent-Local's llama.cpp is the case in point: its
    # build only needs the committed AAR (see its settings.gradle.kts).
    files = [entry.path for entry in entries if not entry.is_submodule]
    if not files:
        raise RuntimeError(f"{addon.name}: git tracks no file in this directory")
    # The archive root IS the project root: Code on the Go reads a folder as a
    # plugin project only when build.gradle.kts and libs/plugin-api.jar sit in
    # the folder it was given (isPluginProject in ProjectValidations.kt).
    for repo_path in files:
        relative = repo_path[len(inside) + 1:]
        target = _claim(top, relative, addon.name)
        if relative in REWRITTEN:
            target.write_text(flatten((root / repo_path).read_text()))
        else:
            shutil.copy2(root / repo_path, target)

    for jar in jars_for(addon):
        source = root / "libs" / jar
        if not source.exists():
            raise RuntimeError(f"{addon.name}: libs/{jar} is missing")
        shutil.copy2(source, _claim(top, f"libs/{jar}", addon.name))

    # Most addons track a wrapper of their own, which is already correct for a
    # root-level build. Only fall back to the repository's for the few that
    # rely on it.
    if not (top / "gradlew").exists():
        for name in ("gradlew", "gradlew.bat"):
            if (root / name).exists():
                shutil.copy2(root / name, _claim(top, name, addon.name))
    if not (top / "gradle" / "wrapper").exists():
        shutil.copytree(root / "gradle" / "wrapper", top / "gradle" / "wrapper")

    # AGPL source distribution: ship the licence text the notice refers to
    licence_file = root / "LICENSE"
    if licence_file.exists():
        shutil.copy2(licence_file, _claim(top, "LICENSE", addon.name))

    def mentions(relative: str, text: str) -> bool:
        f = addon / relative
        return f.exists() and text in f.read_text()

    caveats = ""
    if mentions("src/main/AndroidManifest.xml", "${pluginVcsRevision}"):
        caveats += PROVENANCE_NOTE
    if mentions("build.gradle.kts", "downloadAssets"):
        caveats += DOWNLOAD_NOTE

    prerequisites = _submodule_notes(
        root, addon, [entry for entry in entries if entry.is_submodule])

    author = meta.get("author") or {}
    _claim(top, "BUILDING.md", addon.name).write_text(NOTES.format(
        caveats=caveats, prerequisites=prerequisites,
        name=model.display_name(addon.name), directory=inside,
        origin="Community contribution" if meta.get("origin") == "community"
               else "App Dev for All",
        author=f"{author.get('name', 'App Dev for All')}"
               + (f" ({author['url']})" if author.get("url") else ""),
        source=SOURCE,
        license=meta.get("license", "AGPL-3.0-or-later")))
    return top


# Anything matching these must never reach a published tarball. Section 9.3
# names Sentry DSNs as the material at risk, and they do not live only in
# local.properties.
SECRET_NAMES = ("local.properties", ".env", "keystore.properties",
                "sentry.properties", "secrets.properties", "google-services.json")
SECRET_SUFFIXES = (".jks", ".keystore", ".p12", ".pem", ".key")


def is_inside(path: Path, root: Path) -> bool:
    """A string prefix test accepts /out/foo-src-evil for root /out/foo-src."""
    try:
        path.resolve().relative_to(root.resolve())
        return True
    except ValueError:
        return False


def verify(top: Path, inside: str, jars: list[str]) -> None:
    problems = []
    for jar in jars:
        if not (top / "libs" / jar).exists():
            problems.append(f"libs/{jar} is missing")
    if not (top / "gradlew").exists():
        problems.append("gradlew is missing")
    if not (top / "gradle" / "wrapper" / "gradle-wrapper.properties").exists():
        problems.append("the wrapper properties file is missing")
    for name in REWRITTEN:
        gradle_file = top / name
        if not gradle_file.exists():
            problems.append(f"{name} is missing from the archive root")
        # A reference that still climbs out of the archive root would only fail
        # once someone unpacked it and ran a build, which is exactly the
        # failure this shape exists to remove.
        elif "../" in gradle_file.read_text():
            problems.append(f"{name} still references a parent directory")
    for path in top.rglob("*"):
        rel = path.relative_to(top).as_posix()
        if path.name in SECRET_NAMES or path.suffix in SECRET_SUFFIXES:
            problems.append(f"{rel} could carry a secret")
        if path.is_dir() and path.name in (".gradle", "build"):
            problems.append(f"{rel}/ is build output")
        if not is_inside(path, top):
            problems.append(f"{rel} escapes the archive root")
    if problems:
        raise RuntimeError(f"{inside}: " + "; ".join(problems))


def build(root: Path, addon: Path, out: Path,
          meta: dict | None = None) -> Path | None:
    """Stage and archive an addon's source, or None when it has none to ship.

    A template returns None (ADFA-6252). Everything below exists so somebody can
    unpack the archive and build it: the shared jars are copied in, the Gradle
    wrapper is copied in, ../libs/ references are flattened, and BUILDING.md
    says to run assemblePlugin. A template has no project to open and no
    compiler to run, and its .cgt is plain text throughout, so the published
    download already is its source. The catalog omits sourceTarball for it and
    the gallery card drops the Source link.
    """
    from addons import discover
    if discover.is_template(addon):
        return None
    jars = jars_for(addon)
    if not jars:
        raise RuntimeError(f"{addon.name}: it references no shared jar")
    top = _stage(root, addon, out, meta or {})
    verify(top, addon.relative_to(root).as_posix(), jars)
    archive = out / f"{top.name}.tar.gz"
    # Reproducible: same source, same bytes, so a republish does not churn
    # every sourceTarball checksum in the catalog. mtime=0 in the gzip header,
    # and no builder identity or timestamps in the members.
    def normalise(info: tarfile.TarInfo) -> tarfile.TarInfo:
        info.uid = info.gid = 0
        info.uname = info.gname = ""
        info.mtime = 0
        return info

    with open(archive, "wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as gz:
            with tarfile.open(fileobj=gz, mode="w") as tar:
                for path in sorted(top.rglob("*")):
                    tar.add(path, arcname=str(Path(top.name) / path.relative_to(top)),
                            recursive=False, filter=normalise)
    shutil.rmtree(top)
    return archive
