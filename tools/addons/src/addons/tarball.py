import gzip
import re
import shutil
import subprocess
import tarfile
from pathlib import Path

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

# Every shared jar is referenced as "../libs/x.jar" (or "../../libs/x.jar")
# from the addon's place in the repository. Flattening moves the addon to the
# archive root, so those references have to lose their parent hops.
PARENT_LIBS = re.compile(r"(?:\.\./)+libs/")

# Only these are rewritten. They are the only tracked files that reference the
# shared jars by path; READMEs and HTML documentation describe the repository
# and stay as they are.
REWRITTEN = ("build.gradle.kts", "settings.gradle.kts")


def jars_for(addon: Path) -> list[str]:
    text = ""
    for name in REWRITTEN:
        f = addon / name
        if f.exists():
            text += f.read_text()
    return sorted(set(re.findall(r"\.\./libs/([A-Za-z0-9._-]+\.jar)", text)))


def tracked_files(root: Path, addon: Path) -> list[str]:
    relative = addon.relative_to(root).as_posix()
    result = subprocess.run(
        ["git", "-C", str(root), "ls-files", "--", relative],
        capture_output=True, text=True, check=True)
    return [line for line in result.stdout.splitlines() if line]


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
    files = tracked_files(root, addon)
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

    author = meta.get("author") or {}
    _claim(top, "BUILDING.md", addon.name).write_text(NOTES.format(
        caveats=caveats,
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
          meta: dict | None = None) -> Path:
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
