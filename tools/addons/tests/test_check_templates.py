"""check.py rules for template addons (ADFA-6252).

Most of these cover failures the IDE handles in silence: a path in
templates.json that names nothing, or a template directory with no
template.json, is skipped at load with no error shown, so the template simply
never appears on the New Project screen. The check is the only thing that says
so before a release.
"""
import json
from pathlib import Path

from addons import check

CATALOG_SCHEMA = Path(__file__).parents[3] / "site" / "catalog.schema.json"

BLOCK = {"id": "org.appdevforall.fluttertemplates",
         "version": "1.0.0", "minAppVersion": "26.38"}

BASE_META = {"summary": "s", "description": "d", "tags": ["flutter"],
             "origin": "community", "license": "AGPL-3.0-or-later",
             "author": {"name": "RJ Ali", "url": ""}}

PAGE = ("<html><title>Flutter Templates</title>"
        "<body><h1>Flutter Templates</h1></body></html>")


def make_template(root: Path, index=None, block=BLOCK,
                  with_meta: bool = True) -> Path:
    site = root / "site"
    site.mkdir(exist_ok=True)
    (site / "catalog.schema.json").write_text(CATALOG_SCHEMA.read_text())

    d = root / "templates" / "Flutter-Templates"
    (d / "FlutterBasic" / "template").mkdir(parents=True)
    for icon in ("icon_day.png", "icon_night.png"):
        (d / icon).write_bytes(b"png")

    if index is None:
        index = {"templates": [{"path": "FlutterBasic"}]}
    (d / "templates.json").write_text(
        index if isinstance(index, str) else json.dumps(index))

    if with_meta:
        (d / "FlutterBasic" / "template" / "template.json").write_text(
            json.dumps({"name": "Flutter Basic", "description": "A starter"}))

    meta = dict(BASE_META)
    if block is not None:
        meta["template"] = block
    (d / "addon.json").write_text(json.dumps(meta))
    (d / "flutter-templates.html").write_text(PAGE)
    return d


def test_a_compliant_template_passes(tmp_path):
    make_template(tmp_path)
    assert check.check_names(tmp_path) == []


def test_a_template_needs_no_gradle_build_or_manifest(tmp_path):
    """The plugin checks must not run against a template."""
    d = make_template(tmp_path)
    assert not (d / "build.gradle.kts").exists()
    assert not (d / "src").exists()
    assert check.check_names(tmp_path) == []


def test_template_icons_live_at_the_addon_root(tmp_path):
    d = make_template(tmp_path)
    (d / "icon_day.png").unlink()
    problems = check.check_names(tmp_path)
    assert any("icon_day.png is missing" in p for p in problems)
    # and the message must not name the Android path a template does not have
    assert not any("src/main/assets" in p for p in problems)


def test_a_missing_template_directory_fails(tmp_path):
    make_template(tmp_path, index={"templates": [{"path": "NoSuchThing"}]})
    problems = check.check_names(tmp_path)
    assert any("NoSuchThing" in p and "not a directory" in p for p in problems)


def test_a_template_without_template_json_fails(tmp_path):
    make_template(tmp_path, with_meta=False)
    problems = check.check_names(tmp_path)
    assert any("FlutterBasic/template/template.json is missing" in p
               for p in problems)


def test_an_unlisted_template_directory_fails(tmp_path):
    """A directory nobody listed is never packaged, so say so."""
    d = make_template(tmp_path)
    (d / "FlutterBloc" / "template").mkdir(parents=True)
    problems = check.check_names(tmp_path)
    assert any("FlutterBloc" in p and "not listed" in p for p in problems)


def test_a_template_path_with_a_slash_fails(tmp_path):
    make_template(tmp_path, index={"templates": [{"path": "a/b"}]})
    problems = check.check_names(tmp_path)
    assert any("plain directory name" in p for p in problems)


def test_invalid_templates_json_fails(tmp_path):
    make_template(tmp_path, index="{ not json")
    problems = check.check_names(tmp_path)
    assert any("templates.json is not valid JSON" in p for p in problems)


def test_an_empty_templates_json_fails(tmp_path):
    make_template(tmp_path, index={"templates": []})
    problems = check.check_names(tmp_path)
    assert any("lists no templates" in p for p in problems)


def test_a_template_without_the_addon_json_block_fails(tmp_path):
    make_template(tmp_path, block=None)
    problems = check.check_names(tmp_path)
    assert any("no 'template' object" in p for p in problems)
    assert any("no addon id" in p for p in problems)


def test_a_bad_min_app_version_fails(tmp_path):
    bad = dict(BLOCK)
    bad["minAppVersion"] = "1.2.3"
    make_template(tmp_path, block=bad)
    problems = check.check_names(tmp_path)
    assert any("template.minAppVersion" in p for p in problems)


def test_the_addon_json_schema_rejects_a_bad_template_block(tmp_path):
    bad = dict(BLOCK)
    del bad["minAppVersion"]
    make_template(tmp_path, block=bad)
    problems = check.check_metadata(tmp_path)
    assert any("addon.json is invalid" in p for p in problems)
