import json
from pathlib import Path

import pytest

from addons import catalog

PREDICATE = "com.itsaky.androidide.plugins.build"

METADATA = {
    "summary": "Creates and manages app signing keystores on the device.",
    "description": "A longer paragraph.",
    "tags": ["signing", "release"],
    "origin": "appdevforall",
    "license": "AGPL-3.0-or-later",
    "author": {"name": "App Dev For All", "url": "https://www.appdevforall.org"},
}

MANIFEST = """<manifest><application>
    <meta-data
        android:name="plugin.id"
        android:value="com.appdevforall.keygen.plugin" />
    <meta-data
        android:name="plugin.version"
        android:value="${pluginVersion}" />
</application></manifest>"""


def make(tmp_path: Path, build_extra: str = "") -> Path:
    addon = tmp_path / "plugins" / "Keystore-Generator"
    (addon / "src" / "main").mkdir(parents=True)
    (addon / "build.gradle.kts").write_text(PREDICATE + "\n" + build_extra)
    (addon / "src" / "main" / "AndroidManifest.xml").write_text(MANIFEST)
    (addon / "addon.json").write_text(json.dumps(METADATA))
    dist = tmp_path / "dist"
    dist.mkdir()
    (dist / "keystore-generator.cgp").write_bytes(b"cgp")
    (dist / "keystore-generator-src.tar.gz").write_bytes(b"tar")
    (tmp_path / "site").mkdir()
    (tmp_path / "site" / "catalog.schema.json").write_text(
        Path(__file__).parents[3].joinpath("site/catalog.schema.json").read_text())
    return dist


def test_builds_a_valid_entry(tmp_path):
    dist = make(tmp_path)
    result = catalog.build(tmp_path, dist)
    assert result["schemaVersion"] == 2
    entry = result["addons"][0]
    assert entry["type"] == "plugin"
    assert entry["slug"] == "keystore-generator"
    assert entry["name"] == "Keystore Generator"
    assert entry["addonId"] == "com.appdevforall.keygen.plugin"
    assert entry["version"] == "1.0.0"
    assert entry["download"]["url"].endswith("/dl/keystore-generator.cgp")
    assert entry["download"]["size"] == 3
    assert len(entry["download"]["sha256"]) == 64
    # a plugin still ships one; only a template omits it
    assert entry["sourceTarball"]["url"].endswith("/src/keystore-generator-src.tar.gz")


TEMPLATE_METADATA = METADATA | {
    "template": {
        "id": "org.appdevforall.fluttertemplates",
        "version": "1.0.0",
        "minAppVersion": "26.38",
    },
}


def make_template(tmp_path: Path, with_tarball: bool = False) -> Path:
    """A template addon: no Gradle build, no manifest, metadata in addon.json."""
    addon = tmp_path / "templates" / "Flutter-Templates"
    (addon / "FlutterBasic" / "template").mkdir(parents=True)
    (addon / "templates.json").write_text(
        json.dumps({"templates": [{"path": "FlutterBasic"}]}))
    (addon / "FlutterBasic" / "template" / "template.json").write_text(
        json.dumps({"name": "Flutter Basic", "description": "A starter"}))
    (addon / "addon.json").write_text(json.dumps(TEMPLATE_METADATA))
    dist = tmp_path / "dist"
    dist.mkdir(exist_ok=True)
    (dist / "flutter-templates.cgt").write_bytes(b"cgt")
    if with_tarball:
        (dist / "flutter-templates-src.tar.gz").write_bytes(b"tar")
    site = tmp_path / "site"
    site.mkdir(exist_ok=True)
    (site / "catalog.schema.json").write_text(
        Path(__file__).parents[3].joinpath("site/catalog.schema.json").read_text())
    return dist


def test_a_template_entry_downloads_a_cgt(tmp_path):
    dist = make_template(tmp_path)
    entry = catalog.build(tmp_path, dist)["addons"][0]
    assert entry["type"] == "template"
    assert entry["slug"] == "flutter-templates"
    assert entry["name"] == "Flutter Templates"
    # from addon.json's template block, not from a manifest it does not have
    assert entry["addonId"] == "org.appdevforall.fluttertemplates"
    assert entry["version"] == "1.0.0"
    assert entry["minAppVersion"] == "26.38"
    assert entry["download"]["url"].endswith("/dl/flutter-templates.cgt")


def test_a_template_entry_omits_the_source_tarball(tmp_path):
    """The .cgt is plain text, so it is its own source (ADFA-6252).

    The schema must also accept the omission, which catalog.build() validates,
    so this covers site/catalog.schema.json as much as catalog.py.
    """
    dist = make_template(tmp_path)
    entry = catalog.build(tmp_path, dist)["addons"][0]
    assert "sourceTarball" not in entry
    assert entry["sourceUrl"].endswith("/templates/Flutter-Templates")


def test_a_template_does_not_need_a_tarball_in_dist(tmp_path):
    """Requiring one would fail the publish for every template."""
    dist = make_template(tmp_path)
    assert not (dist / "flutter-templates-src.tar.gz").exists()
    catalog.build(tmp_path, dist)          # must not raise


def test_a_missing_cgt_still_stops_the_build(tmp_path):
    dist = make_template(tmp_path)
    (dist / "flutter-templates.cgt").unlink()
    with pytest.raises(RuntimeError, match="flutter-templates.cgt"):
        catalog.build(tmp_path, dist)


def test_no_field_is_null(tmp_path):
    dist = make(tmp_path)
    entry = catalog.build(tmp_path, dist)["addons"][0]
    assert None not in entry.values()


def test_a_bad_version_stops_the_build(tmp_path):
    dist = make(tmp_path, build_extra='android { defaultConfig { versionName = "draft" } }')
    with pytest.raises(RuntimeError, match="version"):
        catalog.build(tmp_path, dist)


def test_a_missing_artifact_stops_the_build(tmp_path):
    dist = make(tmp_path)
    (dist / "keystore-generator.cgp").unlink()
    with pytest.raises(RuntimeError, match="keystore-generator.cgp"):
        catalog.build(tmp_path, dist)


def test_urls_use_the_base_they_are_published_under(tmp_path):
    dist = make(tmp_path)
    doc = catalog.build(tmp_path, dist,
                        base="https://addons.appdevforall.org/staging/run-7")
    entry = doc["addons"][0]
    for field in ("iconUrl", "pageUrl"):
        assert entry[field].startswith("https://addons.appdevforall.org/staging/run-7/")
    assert entry["download"]["url"].endswith("/staging/run-7/dl/keystore-generator.cgp")
    assert entry["sourceTarball"]["url"].endswith(
        "/staging/run-7/src/keystore-generator-src.tar.gz")


def test_the_base_defaults_to_the_live_site(tmp_path):
    dist = make(tmp_path)
    entry = catalog.build(tmp_path, dist)["addons"][0]
    assert entry["pageUrl"] == "https://addons.appdevforall.org/p/keystore-generator.html"


def test_entry_carries_both_icon_variants(tmp_path):
    dist = make(tmp_path)
    entry = catalog.build(tmp_path, dist)["addons"][0]
    assert entry["iconUrl"].endswith("/p/keystore-generator.png")
    assert entry["iconDarkUrl"].endswith("/p/keystore-generator-night.png")
