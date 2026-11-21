"""The acceptance criteria in docs/catalog-watcher-design.md, numbered as there."""
import json

import pytest

from addons import watch

WEBHOOK = "https://hooks.example/secret"


def addon(addon_id, name, kind="plugin", **extra):
    return {"addonId": addon_id, "name": name, "type": kind, **extra}


@pytest.fixture
def env(tmp_path, monkeypatch):
    """A file:// catalog to fetch, a snapshot path, and a Slack that records posts."""
    published = tmp_path / "catalog.json"
    posts = []
    monkeypatch.setattr(watch, "post", lambda hook, text: posts.append(text))
    monkeypatch.setenv("SLACK_WEBHOOK_URL", WEBHOOK)

    def publish(*addons, raw=None):
        published.write_text(raw if raw is not None else json.dumps(
            {"schemaVersion": 2, "generated": "2026-11-20T00:00:00Z",
             "addons": list(addons)}))

    class Env:
        url = published.as_uri()
        snapshot = tmp_path / "state" / "catalog-snapshot.json"

        def check(self):
            return watch.main(self.url, self.snapshot)
    e = Env()
    e.publish, e.posts = publish, posts
    return e


BRACKETS = addon("org.x.brackets", "Rainbow Brackets")
KOTLIN = addon("org.x.kotlin", "Kotlin Console App", "template")
THEME = addon("org.x.theme", "Old Theme Pack")


def test_1_first_run_saves_a_baseline_and_posts_nothing(env):
    env.publish(BRACKETS)
    assert env.check() == 0
    assert env.posts == []
    assert json.loads(env.snapshot.read_text())["addons"] == [BRACKETS]


def test_2_unchanged_posts_nothing(env):
    env.publish(BRACKETS)
    env.check()
    assert env.check() == 0
    assert env.posts == []


def test_3_an_addition(env):
    env.publish(BRACKETS)
    env.check()
    env.publish(BRACKETS, KOTLIN)
    assert env.check() == 0
    assert len(env.posts) == 1
    assert "➕ Added    template  Kotlin Console App" in env.posts[0]
    assert "Rainbow Brackets" not in env.posts[0]


def test_4_a_removal_is_named_from_the_snapshot(env):
    env.publish(BRACKETS, THEME)
    env.check()
    env.publish(BRACKETS)
    env.check()
    assert "➖ Removed  plugin    Old Theme Pack" in env.posts[0]


def test_5_additions_first_then_removals_each_by_name(env):
    env.publish(THEME)
    env.check()
    env.publish(KOTLIN, BRACKETS)
    env.check()
    lines = [l for l in env.posts[0].splitlines() if l.startswith(("➕", "➖"))]
    assert [l.split(maxsplit=3)[3] for l in lines] == [
        "Kotlin Console App", "Rainbow Brackets", "Old Theme Pack"]


def test_6_a_changed_addon_id_is_a_removal_and_an_addition(env):
    env.publish(BRACKETS)
    env.check()
    env.publish(addon("org.y.brackets", "Rainbow Brackets"))
    env.check()
    assert "➕ Added" in env.posts[0] and "➖ Removed" in env.posts[0]


def test_7_edits_to_other_fields_are_not_changes(env):
    env.publish(BRACKETS)
    env.check()
    env.publish(addon("org.x.brackets", "Rainbow Brackets 2", version="2.0"))
    assert env.check() == 0
    assert env.posts == []


@pytest.mark.parametrize("bad", [
    "not json",
    json.dumps({"addons": []}),
    json.dumps({"addons": "nope"}),
    json.dumps({}),
    json.dumps({"addons": [BRACKETS, BRACKETS]}),
    json.dumps({"addons": [{"addonId": "a", "name": "A"}]}),
])
def test_8_a_bad_catalog_reports_and_keeps_the_snapshot(env, bad):
    env.publish(BRACKETS)
    env.check()
    before = env.snapshot.read_text()
    env.publish(raw=bad)
    assert env.check() == 1
    assert env.posts[0].startswith("⚠️ Add-ons catalog check failed")
    assert env.snapshot.read_text() == before


def test_8_an_unreachable_url_reports(env, tmp_path):
    env.url = (tmp_path / "missing.json").as_uri()
    assert env.check() == 1
    assert "could not fetch" in env.posts[0]
    assert not env.snapshot.exists()


def test_9_a_failed_post_keeps_the_snapshot_and_reports_again(env, monkeypatch):
    env.publish(BRACKETS)
    env.check()
    env.publish(BRACKETS, KOTLIN)
    monkeypatch.setattr(watch, "post", lambda hook, text: (_ for _ in ()).throw(
        watch.WatchError("Slack post failed: 500")))
    assert env.check() == 1
    assert "Kotlin" not in env.snapshot.read_text()
    monkeypatch.setattr(watch, "post", lambda hook, text: env.posts.append(text))
    assert env.check() == 0
    assert "Kotlin Console App" in env.posts[0]


def test_the_webhook_never_appears_in_an_error(env):
    env.url = "http://127.0.0.1:9/catalog.json"
    assert env.check() == 1
    assert WEBHOOK not in env.posts[0]


def test_no_webhook_fails_before_fetching(env, monkeypatch):
    monkeypatch.delenv("SLACK_WEBHOOK_URL")
    assert env.check() == 1
    assert not env.snapshot.exists()


def test_fetch_names_itself_because_cloudflare_refuses_urllib(monkeypatch):
    sent = {}

    class Response:
        def __enter__(self):
            return self

        def __exit__(self, *a):
            pass

        def read(self):
            return b'{"addons": []}'

    def urlopen(request, timeout):
        sent["ua"] = request.get_header("User-agent")
        return Response()
    monkeypatch.setattr(watch.urllib.request, "urlopen", urlopen)
    watch.fetch("https://addons.example/v2/catalog.json")
    assert sent["ua"] == watch.USER_AGENT
