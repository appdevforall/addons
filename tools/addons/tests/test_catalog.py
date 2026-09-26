import json
from pathlib import Path

import pytest

from addons import catalog

PREDICATE = "com.itsaky.androidide.plugins.build"


REPO = Path(__file__).parents[3]


def copy_schemas(site: Path) -> None:
    """Both published majors: catalog.build validates against the one it targets."""
    site.mkdir(exist_ok=True)
    for name in ("catalog.schema.json", "catalog.v2.schema.json"):
        (site / name).write_text((REPO / "site" / name).read_text())


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
    copy_schemas(tmp_path / "site")
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
    copy_schemas(site)
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


# --- the two published majors (ADFA-6252, design sections 10.2 and 10.5) -------
#
# v2 renames pluginId and makes sourceTarball optional. Both are changes 10.5
# forbids within a major, so v1 keeps being published unchanged for fielded app
# builds that cannot be updated.

def test_v1_still_says_pluginId(tmp_path):
    dist = make(tmp_path)
    doc = catalog.build(tmp_path, dist, schema_version=1)
    assert doc["schemaVersion"] == 1
    entry = doc["addons"][0]
    assert entry["pluginId"] == "com.appdevforall.keygen.plugin"
    assert "addonId" not in entry


def test_v1_still_requires_the_source_tarball(tmp_path):
    dist = make(tmp_path)
    entry = catalog.build(tmp_path, dist, schema_version=1)["addons"][0]
    assert entry["sourceTarball"]["url"].endswith("-src.tar.gz")


def test_v1_omits_templates(tmp_path):
    """v1 cannot describe one: it requires sourceTarball and names its id field
    for plugins. Removing an addon is the one thing 10.5 permits within a major,
    so a v1 consumer sees nothing it cannot install."""
    make(tmp_path)
    dist = make_template(tmp_path)
    v1 = catalog.build(tmp_path, dist, schema_version=1)
    v2 = catalog.build(tmp_path, dist, schema_version=2)
    assert [e["slug"] for e in v1["addons"]] == ["keystore-generator"]
    assert [e["slug"] for e in v2["addons"]] == ["flutter-templates",
                                                 "keystore-generator"]


def test_both_majors_agree_on_a_plugin(tmp_path):
    """Only the id field differs; a rename must not disturb anything else."""
    dist = make(tmp_path)
    v1 = catalog.build(tmp_path, dist, schema_version=1)["addons"][0]
    v2 = catalog.build(tmp_path, dist, schema_version=2)["addons"][0]
    assert v1.pop("pluginId") == v2.pop("addonId")
    assert v1 == v2


def test_each_major_validates_against_its_own_schema(tmp_path):
    """build() validates before returning, so reaching this point is the check.
    Guards against v2 being written against the v1 schema, which would accept it
    and hide the rename."""
    dist = make(tmp_path)
    assert catalog.build(tmp_path, dist, schema_version=1)["schemaVersion"] == 1
    assert catalog.build(tmp_path, dist, schema_version=2)["schemaVersion"] == 2
    assert catalog.schema_file(tmp_path, 1).name == "catalog.schema.json"
    assert catalog.schema_file(tmp_path, 2).name == "catalog.v2.schema.json"
