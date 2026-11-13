import json
from pathlib import Path

from addons import check

PREDICATE = "com.itsaky.androidide.plugins.build"
# check_names reads the slug rule from the published catalog contract, so a
# fixture root needs the real one. Same approach as test_catalog.
CATALOG_SCHEMA = Path(__file__).parents[3] / "site" / "catalog.schema.json"

MANIFEST = """<manifest><application>
<meta-data android:name="plugin.id" android:value="com.appdevforall.keygen.plugin" />
<meta-data android:name="plugin.name" android:value="{name}" />
</application></manifest>"""


def make_addon(root: Path, directory: str, plugin_name: str,
               gradle_name: str, page: str) -> Path:
    site = root / "site"
    site.mkdir(exist_ok=True)
    (site / "catalog.schema.json").write_text(CATALOG_SCHEMA.read_text())
    d = root / directory
    (d / "src" / "main" / "assets").mkdir(parents=True)
    for icon in ("icon_day.png", "icon_night.png"):
        (d / "src" / "main" / "assets" / icon).write_bytes(b"png")
    (d / "build.gradle.kts").write_text(
        PREDICATE + '\npluginBuilder { pluginName = "%s" }\n' % gradle_name)
    (d / "src" / "main" / "AndroidManifest.xml").write_text(
        MANIFEST.format(name=plugin_name))
    (d / page).write_text("<html><title>%s</title><body>x</body></html>" % plugin_name)
    return d


def test_a_compliant_addon_passes(tmp_path):
    make_addon(tmp_path, "Keystore-Generator", "Keystore Generator",
               "keystore-generator", "keystore-generator.html")
    assert check.check_names(tmp_path) == []


def test_a_wrong_page_filename_fails(tmp_path):
    make_addon(tmp_path, "Keystore-Generator", "Keystore Generator",
               "keystore-generator", "keygen.html")
    problems = check.check_names(tmp_path)
    assert len(problems) == 1
    assert "keystore-generator.html" in problems[0]


def test_a_wrong_plugin_name_fails(tmp_path):
    make_addon(tmp_path, "Keystore-Generator", "Key Gen",
               "keystore-generator", "keystore-generator.html")
    problems = check.check_names(tmp_path)
    assert any("plugin.name" in p for p in problems)


def test_a_bad_directory_name_fails(tmp_path):
    make_addon(tmp_path, "keystore-generator", "keystore generator",
               "keystore-generator", "keystore-generator.html")
    problems = check.check_names(tmp_path)
    assert any("directory" in p for p in problems)


def test_a_slug_the_catalog_would_reject_fails(tmp_path):
    """A dot passes every naming rule here and yields slug "code.together",
    which the catalog schema refuses. Caught on the pull request, or else at
    publish time after all 23 Gradle builds have run."""
    make_addon(tmp_path, "Code.Together", "Code.Together",
               "code.together", "code.together.html")
    problems = check.check_names(tmp_path)
    assert len(problems) == 1
    assert "code.together" in problems[0]


def test_a_slug_with_a_punctuation_tail_fails(tmp_path):
    make_addon(tmp_path, "Layout-Editor+", "Layout Editor+",
               "layout-editor+", "layout-editor+.html")
    assert any("layout-editor+" in p for p in check.check_names(tmp_path))


def test_the_slug_rule_is_read_from_the_catalog_schema(tmp_path):
    """Not a second copy of the pattern: the catalog is the public contract,
    and two copies drift."""
    make_addon(tmp_path, "Code.Together", "Code.Together",
               "code.together", "code.together.html")
    f = tmp_path / "site" / "catalog.schema.json"
    schema = json.loads(f.read_text())
    schema["$defs"]["addon"]["properties"]["slug"]["pattern"] = "^[a-z0-9.]+$"
    f.write_text(json.dumps(schema))
    assert check.check_names(tmp_path) == []


def test_a_stale_h1_fails(tmp_path):
    """The rename touched only <title>. The <h1> is what a visitor reads."""
    d = make_addon(tmp_path, "Keystore-Generator", "Keystore Generator",
                   "keystore-generator", "keystore-generator.html")
    (d / "keystore-generator.html").write_text(
        "<html><title>Keystore Generator</title><body>"
        "<h1>Key Gen Plugin</h1></body></html>")
    problems = check.check_names(tmp_path)
    assert any("h1" in p.lower() for p in problems)


def test_a_shorthand_product_name_fails(tmp_path):
    """Global Constraint 6: these pages are published, so the rule applies."""
    d = make_addon(tmp_path, "Keystore-Generator", "Keystore Generator",
                   "keystore-generator", "keystore-generator.html")
    (d / "keystore-generator.html").write_text(
        "<html><title>Keystore Generator</title><body><h1>Keystore Generator</h1>"
        "<p>A CoGo plugin.</p></body></html>")
    assert any("CoGo" in p for p in check.check_names(tmp_path))


def test_the_rule_does_not_fire_inside_code(tmp_path):
    d = make_addon(tmp_path, "Keystore-Generator", "Keystore Generator",
                   "keystore-generator", "keystore-generator.html")
    (d / "keystore-generator.html").write_text(
        "<html><title>Keystore Generator</title><body><h1>Keystore Generator</h1>"
        "<p>Clone <code>CodeOnTheGo.git</code> first.</p></body></html>")
    assert not [p for p in check.check_names(tmp_path) if "CodeOnTheGo" in p]


def test_a_missing_icon_fails_the_check(tmp_path):
    """Better here than half way through a publish, with the bucket already
    holding artifacts the catalog will never reference."""
    d = make_addon(tmp_path, "Keystore-Generator", "Keystore Generator",
                   "keystore-generator", "keystore-generator.html")
    (d / "src" / "main" / "assets" / "icon_night.png").unlink()
    problems = check.check_names(tmp_path)
    assert any("icon_night.png" in p for p in problems)


def test_a_missing_plugin_id_fails(tmp_path):
    """pluginId is the app's identity key; an empty one orphans every install."""
    d = make_addon(tmp_path, "Keystore-Generator", "Keystore Generator",
                   "keystore-generator", "keystore-generator.html")
    m = d / "src" / "main" / "AndroidManifest.xml"
    m.write_text(m.read_text().replace('android:name="plugin.id"', 'android:name="plugin.other"'))
    assert any("plugin.id" in p for p in check.check_names(tmp_path))


def test_a_missing_plugin_name_is_not_treated_as_agreement(tmp_path):
    d = make_addon(tmp_path, "Keystore-Generator", "Keystore Generator",
                   "keystore-generator", "keystore-generator.html")
    b = d / "build.gradle.kts"
    b.write_text(b.read_text().replace('pluginName = "keystore-generator"', ""))
    assert any("pluginName" in p for p in check.check_names(tmp_path))


def test_active_content_in_a_published_page_fails(tmp_path):
    """These bodies are published to the same origin as the catalog."""
    d = make_addon(tmp_path, "Keystore-Generator", "Keystore Generator",
                   "keystore-generator", "keystore-generator.html")
    (d / "keystore-generator.html").write_text(
        "<html><title>Keystore Generator</title><body><h1>Keystore Generator</h1>"
        '<img src=x onerror="alert(1)"><script>fetch("https://evil")</script>'
        "</body></html>")
    problems = check.check_names(tmp_path)
    assert any("script" in p.lower() for p in problems)
    assert any("onerror" in p.lower() or "handler" in p.lower() for p in problems)


def test_a_plugin_licence_at_the_root_fails(tmp_path):
    d = make_addon(tmp_path, "Keystore-Generator", "Keystore Generator",
                   "keystore-generator", "keystore-generator.html")
    (d / "LICENSE").write_text("licence")
    problems = check.check_names(tmp_path)
    assert len(problems) == 1
    assert "LICENSE" in problems[0]
