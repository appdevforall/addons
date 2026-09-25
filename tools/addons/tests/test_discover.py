from pathlib import Path

from addons import discover

PREDICATE = "com.itsaky.androidide.plugins.build"


def make_addon(root: Path, path: str, is_addon: bool = True) -> None:
    d = root / path
    d.mkdir(parents=True)
    (d / "build.gradle.kts").write_text(PREDICATE if is_addon else "plain")


def test_finds_addons_in_both_locations(tmp_path):
    make_addon(tmp_path, "Keystore-Generator")
    make_addon(tmp_path, "plugins/Voice-Alerts")
    make_addon(tmp_path, "not-an-addon", is_addon=False)
    names = [p.name for p in discover.find_addons(tmp_path)]
    assert names == ["Keystore-Generator", "Voice-Alerts"]


def test_applies_the_skip_list(tmp_path):
    make_addon(tmp_path, "Keystore-Generator")
    make_addon(tmp_path, "cotg-ndk")
    (tmp_path / "tools" / "addons").mkdir(parents=True)
    (tmp_path / "tools" / "addons" / "skip.txt").write_text("# a comment\ncotg-ndk  held\n")
    names = [p.name for p in discover.find_addons(tmp_path)]
    assert names == ["Keystore-Generator"]


def test_cli_prints_repo_relative_paths(tmp_path, capsys):
    """Callers cd into these and grep changed-file lists with them, so the
    location has to survive. Printing a bare name loses it."""
    from addons import cli
    make_addon(tmp_path, "plugins/Voice-Alerts")
    make_addon(tmp_path, "Legacy-Addon")
    cli.main(["--root", str(tmp_path), "discover"])
    assert capsys.readouterr().out.split() == ["Legacy-Addon", "plugins/Voice-Alerts"]


def test_only_selects_a_subset_by_path_or_name(tmp_path):
    make_addon(tmp_path, "plugins/Voice-Alerts")
    make_addon(tmp_path, "plugins/Keystore-Generator")
    by_name = discover.find_addons(tmp_path, only=["Voice-Alerts"])
    by_path = discover.find_addons(tmp_path, only=["plugins/Voice-Alerts"])
    assert [p.name for p in by_name] == ["Voice-Alerts"]
    assert [p.name for p in by_path] == ["Voice-Alerts"]


def test_only_rejects_an_unknown_addon(tmp_path):
    import pytest
    make_addon(tmp_path, "plugins/Voice-Alerts")
    with pytest.raises(RuntimeError, match="Nope"):
        discover.find_addons(tmp_path, only=["Nope"])


def test_finds_every_addon_type(tmp_path):
    for area in ("plugins", "templates", "snippets", "code-actions"):
        make_addon(tmp_path, f"{area}/Some-Addon-{area}")
    found = [p.relative_to(tmp_path).as_posix() for p in discover.find_addons(tmp_path)]
    assert sorted(found) == [
        "code-actions/Some-Addon-code-actions",
        "plugins/Some-Addon-plugins",
        "snippets/Some-Addon-snippets",
        "templates/Some-Addon-templates",
    ]


def test_skipped_addons_can_still_be_listed_for_compiling(tmp_path):
    """Two different questions: what do we publish, and what must still
    compile. A skipped addon is excluded from the gallery, not from the
    build that proves a libs refresh did not break it."""
    make_addon(tmp_path, "plugins/Shipping")
    make_addon(tmp_path, "held-back")
    (tmp_path / "tools" / "addons").mkdir(parents=True)
    (tmp_path / "tools" / "addons" / "skip.txt").write_text("held-back  reason\n")
    assert [p.name for p in discover.find_addons(tmp_path)] == ["Shipping"]
    assert [p.name for p in discover.find_addons(tmp_path, include_skipped=True)] \
        == ["Shipping", "held-back"]


def test_a_bang_prefix_means_never_build(tmp_path):
    make_addon(tmp_path, "plugins/Shipping")
    make_addon(tmp_path, "held-back")
    make_addon(tmp_path, "broken")
    (tmp_path / "tools" / "addons").mkdir(parents=True)
    (tmp_path / "tools" / "addons" / "skip.txt").write_text(
        "held-back  held out of the gallery\n!broken  does not build at all\n")
    assert [p.name for p in discover.find_addons(tmp_path)] == ["Shipping"]
    # compile coverage picks up the held-back one but never the broken one
    assert [p.name for p in discover.find_addons(tmp_path, include_skipped=True)] \
        == ["Shipping", "held-back"]


def make_template(root: Path, path: str, with_index: bool = True) -> None:
    d = root / path
    d.mkdir(parents=True)
    if with_index:
        (d / "templates.json").write_text('{"templates": [{"path": "One"}]}')


def test_finds_a_template_with_no_gradle_build(tmp_path):
    """A template is discovered by templates.json, not build.gradle.kts.

    This is the whole point of the second rule (ADFA-6252): a template has no
    Gradle project, so the plugin predicate can never match it.
    """
    make_template(tmp_path, "templates/Flutter-Templates")
    found = discover.find_addons(tmp_path)
    assert [p.name for p in found] == ["Flutter-Templates"]
    assert discover.is_template(found[0])


def test_a_templates_dir_without_the_index_is_not_an_addon(tmp_path):
    """templates/ also holds documentation; only a bundle source is an addon."""
    make_template(tmp_path, "templates/Notes", with_index=False)
    assert discover.find_addons(tmp_path) == []


def test_templates_and_plugins_are_listed_together(tmp_path):
    make_addon(tmp_path, "plugins/Random-XKCD")
    make_template(tmp_path, "templates/Flutter-Templates")
    found = discover.find_addons(tmp_path)
    assert [p.name for p in found] == ["Flutter-Templates", "Random-XKCD"]
    assert [discover.is_template(p) for p in found] == [True, False]


def test_a_plugin_is_not_a_template(tmp_path):
    make_addon(tmp_path, "plugins/Random-XKCD")
    assert not discover.is_template(tmp_path / "plugins" / "Random-XKCD")


def test_skip_txt_holds_back_a_template_too(tmp_path):
    make_template(tmp_path, "templates/Flutter-Templates")
    (tmp_path / "tools" / "addons").mkdir(parents=True)
    (tmp_path / "tools" / "addons" / "skip.txt").write_text(
        "Flutter-Templates  not ready\n")
    assert discover.find_addons(tmp_path) == []


def test_only_resolves_a_template_by_path_or_name(tmp_path):
    make_template(tmp_path, "templates/Flutter-Templates")
    by_name = discover.find_addons(tmp_path, ["Flutter-Templates"])
    by_path = discover.find_addons(tmp_path, ["templates/Flutter-Templates"])
    assert by_name == by_path
