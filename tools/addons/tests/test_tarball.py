import subprocess
import tarfile
from pathlib import Path

import pytest

from addons import tarball

PREDICATE = "com.itsaky.androidide.plugins.build"


def make_repo(tmp_path: Path) -> Path:
    subprocess.run(["git", "init", "-q", str(tmp_path)], check=True)
    (tmp_path / "libs").mkdir()
    for jar in ("plugin-api.jar", "gradle-plugin.jar", "common.jar"):
        (tmp_path / "libs" / jar).write_bytes(b"jar")
    (tmp_path / "gradlew").write_text("#!/bin/sh\n")
    (tmp_path / "gradle" / "wrapper").mkdir(parents=True)
    (tmp_path / "gradle" / "wrapper" / "gradle-wrapper.properties").write_text("x")

    addon = tmp_path / "plugins" / "Keystore-Generator"
    (addon / "src").mkdir(parents=True)
    (addon / "build.gradle.kts").write_text(
        PREDICATE + '\ncompileOnly(files("../../libs/plugin-api.jar"))\n')
    (addon / "settings.gradle.kts").write_text(
        'classpath(files("../../libs/gradle-plugin.jar"))\n')
    (addon / "src" / "Main.kt").write_text("fun main() {}")
    (addon / "README.md").write_text("# Keystore Generator\n\nWhat it does.\n")
    (addon / "local.properties").write_text("sdk.dir=/Users/someone/Android")
    (tmp_path / ".gitignore").write_text("local.properties\n")
    commit(tmp_path)
    return addon


def commit(root: Path) -> None:
    subprocess.run(["git", "-C", str(root), "add", "-A"], check=True)
    subprocess.run(["git", "-C", str(root), "-c", "user.email=t@t",
                    "-c", "user.name=t", "commit", "-qm", "x"], check=True)


def names_in(archive: Path) -> set[str]:
    with tarfile.open(archive) as tar:
        return set(tar.getnames())


def text_in(archive: Path, name: str) -> str:
    with tarfile.open(archive) as tar:
        return tar.extractfile(name).read().decode()


def build(tmp_path: Path, addon: Path, meta: dict | None = None) -> Path:
    out = tmp_path / "dist"
    out.mkdir(exist_ok=True)
    return tarball.build(tmp_path, addon, out, meta)


def test_the_archive_root_is_the_project_root(tmp_path):
    """Code on the Go accepts a folder only when build.gradle.kts and
    libs/plugin-api.jar sit in that same folder (isPluginProject)."""
    names = names_in(build(tmp_path, make_repo(tmp_path)))
    top = "keystore-generator-src"
    assert f"{top}/build.gradle.kts" in names
    assert f"{top}/settings.gradle.kts" in names
    assert f"{top}/libs/plugin-api.jar" in names
    assert f"{top}/src/Main.kt" in names
    # no trace of the repository path remains
    assert not any(n.startswith(f"{top}/plugins/") for n in names)


def test_archive_has_a_flat_self_contained_shape(tmp_path):
    archive = build(tmp_path, make_repo(tmp_path))
    assert archive.name == "keystore-generator-src.tar.gz"
    names = names_in(archive)
    top = "keystore-generator-src"
    assert f"{top}/libs/plugin-api.jar" in names
    assert f"{top}/libs/gradle-plugin.jar" in names
    assert f"{top}/libs/common.jar" not in names
    assert f"{top}/gradlew" in names
    assert f"{top}/gradle/wrapper/gradle-wrapper.properties" in names
    assert f"{top}/BUILDING.md" in names


def test_build_files_lose_their_parent_paths(tmp_path):
    """A ../../libs reference points outside a flattened archive."""
    archive = build(tmp_path, make_repo(tmp_path))
    top = "keystore-generator-src"
    build_file = text_in(archive, f"{top}/build.gradle.kts")
    settings = text_in(archive, f"{top}/settings.gradle.kts")
    assert 'files("libs/plugin-api.jar")' in build_file
    assert 'files("libs/gradle-plugin.jar")' in settings
    assert "../" not in build_file and "../" not in settings


def test_the_addons_own_readme_survives(tmp_path):
    """18 of 23 addons ship a README.md; the generated notes must not eat it."""
    archive = build(tmp_path, make_repo(tmp_path))
    top = "keystore-generator-src"
    assert "What it does." in text_in(archive, f"{top}/README.md")


def test_the_addons_own_wrapper_wins_over_the_root_one(tmp_path):
    addon = make_repo(tmp_path)
    (addon / "gradlew").write_text("#!/bin/sh\n# the addon's own\n")
    (addon / "gradle" / "wrapper").mkdir(parents=True)
    (addon / "gradle" / "wrapper" / "gradle-wrapper.properties").write_text("own")
    commit(tmp_path)
    archive = build(tmp_path, addon)
    top = "keystore-generator-src"
    assert "the addon's own" in text_in(archive, f"{top}/gradlew")
    assert text_in(archive, f"{top}/gradle/wrapper/gradle-wrapper.properties") == "own"


def test_the_addons_own_jar_sits_beside_the_shared_ones(tmp_path):
    addon = make_repo(tmp_path)
    (addon / "libs").mkdir()
    (addon / "libs" / "shared.jar").write_bytes(b"own")
    commit(tmp_path)
    names = names_in(build(tmp_path, addon))
    top = "keystore-generator-src"
    assert f"{top}/libs/shared.jar" in names
    assert f"{top}/libs/plugin-api.jar" in names


def test_a_jar_name_collision_stops_the_build(tmp_path):
    """Flattening merges the addon's libs/ with the shared one."""
    addon = make_repo(tmp_path)
    (addon / "libs").mkdir()
    (addon / "libs" / "plugin-api.jar").write_bytes(b"a different jar")
    commit(tmp_path)
    with pytest.raises(RuntimeError, match="libs/plugin-api.jar"):
        build(tmp_path, addon)


def test_finds_only_the_jars_the_addon_uses(tmp_path):
    addon = make_repo(tmp_path)
    assert tarball.jars_for(addon) == ["gradle-plugin.jar", "plugin-api.jar"]


def test_local_properties_never_reaches_the_archive(tmp_path):
    archive = build(tmp_path, make_repo(tmp_path))
    assert not any("local.properties" in n for n in names_in(archive))


def test_a_missing_jar_stops_the_build(tmp_path):
    addon = make_repo(tmp_path)
    (tmp_path / "libs" / "plugin-api.jar").unlink()
    with pytest.raises(RuntimeError, match="plugin-api.jar"):
        build(tmp_path, addon)


def test_verify_rejects_other_credential_files(tmp_path):
    addon = make_repo(tmp_path)
    (addon / "sentry.properties").write_text("dsn=https://secret@sentry.io/1")
    commit(tmp_path)
    with pytest.raises(RuntimeError, match="sentry.properties"):
        build(tmp_path, addon)


def staged_project(top: Path) -> None:
    (top / "libs").mkdir(parents=True)
    (top / "libs" / "plugin-api.jar").write_bytes(b"j")
    (top / "gradlew").write_text("x")
    (top / "gradle" / "wrapper").mkdir(parents=True)
    (top / "gradle" / "wrapper" / "gradle-wrapper.properties").write_text("x")
    for n in ("build.gradle.kts", "settings.gradle.kts"):
        (top / n).write_text('files("libs/plugin-api.jar")\n')


def test_verify_rejects_build_output(tmp_path):
    top = tmp_path / "staged"
    staged_project(top)
    (top / "build").mkdir()
    (top / "build" / "out.jar").write_bytes(b"x")
    with pytest.raises(RuntimeError, match="build/"):
        tarball.verify(top, "plugins/Keystore-Generator", ["plugin-api.jar"])


def test_verify_rejects_a_surviving_parent_path(tmp_path):
    """The last line of defence: a rewrite that missed a reference."""
    top = tmp_path / "staged"
    staged_project(top)
    (top / "build.gradle.kts").write_text('files("../../libs/plugin-api.jar")\n')
    with pytest.raises(RuntimeError, match="build.gradle.kts"):
        tarball.verify(top, "plugins/Keystore-Generator", ["plugin-api.jar"])


def test_a_sibling_directory_is_not_inside_the_archive(tmp_path):
    # "/out/foo-src-evil" starts with "/out/foo-src" but is not inside it
    root = tmp_path / "foo-src"
    (root / "libs").mkdir(parents=True)
    assert not tarball.is_inside(tmp_path / "foo-src-evil" / "x", root)
    assert tarball.is_inside(root / "libs" / "a.jar", root)


META = {"summary": "s", "description": "d", "tags": ["t"],
        "origin": "community", "license": "AGPL-3.0-or-later",
        "author": {"name": "Aman Khan", "url": "https://github.com/aman-khan-786"}}


def test_build_notes_credit_the_author_and_name_the_source(tmp_path):
    addon = make_repo(tmp_path)
    (tmp_path / "LICENSE").write_text("GNU AFFERO GENERAL PUBLIC LICENSE\n")
    archive = build(tmp_path, addon, META)
    top = "keystore-generator-src"
    notes = text_in(archive, f"{top}/BUILDING.md")
    assert f"{top}/LICENSE" in names_in(archive)   # AGPL text must ship
    assert "Aman Khan" in notes
    assert "github.com/aman-khan-786" in notes
    assert "community" in notes.lower()
    assert "plugin-examples" in notes              # where it came from
    assert "plugins/Keystore-Generator" in notes   # which directory it was
    assert "./gradlew assemblePlugin" in notes     # buildable as unpacked


def test_tarballs_are_reproducible(tmp_path):
    """Otherwise every publish churns all 23 sourceTarball checksums."""
    import hashlib
    addon = make_repo(tmp_path)
    first = hashlib.sha256(build(tmp_path, addon, META).read_bytes()).hexdigest()
    second = hashlib.sha256(build(tmp_path, addon, META).read_bytes()).hexdigest()
    assert first == second


def test_no_builder_identity_leaks_into_the_archive(tmp_path):
    addon = make_repo(tmp_path)
    with tarfile.open(build(tmp_path, addon, META)) as tar:
        for m in tar.getmembers():
            assert m.uname == "" and m.gname == "" and m.uid == 0 and m.mtime == 0


def test_a_template_ships_no_tarball(tmp_path):
    """build() returns None instead of raising (ADFA-6252).

    Without the early exit, jars_for() raises "references no shared jar" on the
    first line, because a template references none — so this guards the failure
    that would block every template publish.
    """
    subprocess.run(["git", "init", "-q", str(tmp_path)], check=True)
    addon = tmp_path / "templates" / "Flutter-Templates"
    (addon / "FlutterBasic" / "template").mkdir(parents=True)
    (addon / "templates.json").write_text('{"templates": [{"path": "FlutterBasic"}]}')
    (addon / "FlutterBasic" / "pubspec.yaml.peb").write_text("name: x\n")
    out = tmp_path / "out"
    out.mkdir()

    assert tarball.build(tmp_path, addon, out) is None
    assert list(out.iterdir()) == []
