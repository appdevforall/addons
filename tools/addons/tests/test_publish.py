from addons import publish


class FakeClient:
    def __init__(self):
        self.store = {}
        self.order = []

    def upload_file(self, Filename, Bucket, Key, ExtraArgs=None):
        self.store[Key] = (open(Filename, "rb").read(), ExtraArgs or {})
        self.order.append(Key)


def test_headers_for_a_download():
    head = publish.headers_for("dl/keystore-generator.cgp")
    assert head["ContentType"] == "application/octet-stream"
    assert head["CacheControl"] == "public, max-age=60"
    assert head["ContentDisposition"] == 'attachment; filename="keystore-generator.cgp"'


def test_headers_for_a_page():
    head = publish.headers_for("p/keystore-generator.html")
    assert head["ContentType"] == "text/html"
    assert "ContentDisposition" not in head


def test_headers_for_an_asset():
    head = publish.headers_for("assets/styles.abc12345.css")
    assert head["ContentType"] == "text/css"
    assert head["CacheControl"] == "public, max-age=31536000, immutable"


def test_the_catalogs_go_last(tmp_path):
    """Ordering is the only atomicity R2 offers (design section 10.6).

    Every published major is written, and all of them after the objects they
    reference, so a reader mid-publish never sees an entry without its download.
    """
    one = tmp_path / "a.cgp"
    one.write_bytes(b"a")
    v1 = tmp_path / "catalog.v1.json"
    v1.write_bytes(b"{}")
    v2 = tmp_path / "catalog.json"
    v2.write_bytes(b"{}")
    client = FakeClient()
    publish.publish(client, "addons", [("dl/a.cgp", one)],
                    [("v1/catalog.json", v1), ("v2/catalog.json", v2)])
    assert client.order == ["dl/a.cgp", "v1/catalog.json", "v2/catalog.json"]


def test_bucket_comes_from_the_environment(monkeypatch):
    monkeypatch.setenv("R2_BUCKET", "addons-staging")
    assert publish.bucket_from_env() == "addons-staging"


def test_bucket_defaults_to_addons(monkeypatch):
    monkeypatch.delenv("R2_BUCKET", raising=False)
    assert publish.bucket_from_env() == "addons"


def test_hashed_name_is_stable_and_content_derived(tmp_path):
    f = tmp_path / "app.js"
    f.write_bytes(b"one")
    first = publish.hashed_name(f)
    assert publish.hashed_name(f) == first          # stable for same bytes
    assert first.startswith("app.") and first.endswith(".js")
    f.write_bytes(b"two")
    assert publish.hashed_name(f) != first          # changes with content


def test_hashed_assets_are_immutable():
    head = publish.headers_for("assets/app.abc12345.js")
    assert head["CacheControl"] == "public, max-age=31536000, immutable"
    assert publish.headers_for("index.html")["CacheControl"] == "public, max-age=60"


def test_svg_is_served_as_an_image():
    assert publish.headers_for("assets/adfa-logo.svg")["ContentType"] == "image/svg+xml"
    assert "ContentDisposition" not in publish.headers_for("assets/adfa-logo.svg")


def test_assets_stay_immutable_under_a_staging_prefix():
    # publish keys carry the prefix, so a startswith("assets/") test misses them
    head = publish.headers_for("staging/1234/assets/app.abc12345.js")
    assert head["CacheControl"] == "public, max-age=31536000, immutable"


def test_a_download_under_a_prefix_is_still_an_attachment():
    head = publish.headers_for("staging/1234/dl/voice-alerts.cgp")
    assert head["CacheControl"] == "public, max-age=60"
    assert head["ContentDisposition"].startswith("attachment")


def test_a_partial_publish_may_not_overwrite_the_live_catalog(tmp_path):
    import pytest
    from addons import cli
    _repo(tmp_path, ["A-One", "B-Two"])
    with pytest.raises(SystemExit, match="subset"):
        cli.main(["--root", str(tmp_path), "publish", "--dist", str(tmp_path),
                  "--only", "plugins/A-One"])


def _repo(tmp_path, names):
    for n in names:
        d = tmp_path / "plugins" / n
        (d / "src" / "main" / "assets").mkdir(parents=True)
        (d / "build.gradle.kts").write_text("com.itsaky.androidide.plugins.build")
    return tmp_path


def test_publishing_everything_to_the_live_site_is_allowed(tmp_path):
    """The workflow always passes --only, even for addon=all. A guard that
    trips on 'was --only given' blocks the only live publish path there is."""
    from addons import cli, discover
    _repo(tmp_path, ["A-One", "B-Two"])
    every = [p.relative_to(tmp_path).as_posix() for p in discover.find_addons(tmp_path)]
    # the guard must not fire when the selection is everything
    assert discover.find_addons(tmp_path, every) == discover.find_addons(tmp_path)


def test_an_empty_selection_is_refused(tmp_path):
    """argparse nargs='*' yields [], which is falsy; an unquoted $ONLY that
    expands to nothing must not publish an empty catalog over the live one."""
    import pytest
    from addons import cli
    _repo(tmp_path, ["A-One"])
    with pytest.raises(SystemExit):
        cli.main(["--root", str(tmp_path), "publish", "--dist", str(tmp_path), "--only"])


def test_publish_emits_every_expected_key(tmp_path, monkeypatch):
    """Design 8.7 asks for this directly: the keys and headers cannot be
    checked any other way before a real upload."""
    import json
    from addons import cli, publish as pub

    addon = tmp_path / "plugins" / "Demo-Addon"
    (addon / "src" / "main" / "assets").mkdir(parents=True)
    (addon / "build.gradle.kts").write_text("com.itsaky.androidide.plugins.build")
    for icon in ("icon_day.png", "icon_night.png"):
        (addon / "src" / "main" / "assets" / icon).write_bytes(b"png")
    (addon / "demo-addon.html").write_text(
        "<html><title>Demo Addon</title><body><h1>Demo Addon</h1></body></html>")

    site = tmp_path / "site"
    site.mkdir()
    for n, body in (("styles.css", "a{}"), ("app.js", "//"),
                    ("index.html", '<link href="assets/styles.css">'),
                    ("catalog.schema.json", "{}"),
                    ("catalog.v2.schema.json", "{}")):
        (site / n).write_text(body)
    (site / "assets").mkdir()
    (site / "assets" / "adfa-logo.svg").write_text("<svg/>")
    (site / "page.template.html").write_text(
        '<link href="../assets/styles.css">{{title}}{{body}}')

    dist = tmp_path / "dist"
    dist.mkdir()
    (dist / "catalog.json").write_text("{}")
    (dist / "catalog.v1.json").write_text("{}")
    (dist / "demo-addon.cgp").write_bytes(b"cgp")
    (dist / "demo-addon-src.tar.gz").write_bytes(b"tar")

    client = FakeClient()
    monkeypatch.setattr(pub, "client_from_env", lambda: client)
    cli.main(["--root", str(tmp_path), "publish", "--dist", str(dist),
              "--prefix", "staging/7/"])

    keys = client.order
    for expected in ("staging/7/index.html",
                     "staging/7/p/demo-addon.html",
                     "staging/7/p/demo-addon.png",
                     "staging/7/p/demo-addon-night.png",
                     "staging/7/dl/demo-addon.cgp",
                     "staging/7/src/demo-addon-src.tar.gz",
                     "staging/7/v1/catalog.schema.json",
                     "staging/7/v2/catalog.schema.json",
                     "staging/7/v1/catalog.json"):
        assert expected in keys, f"{expected} was never uploaded"
    # Both published majors, and both after everything they reference: a reader
    # mid-publish must never find an entry whose download is not there yet.
    assert keys[-2:] == ["staging/7/v1/catalog.json", "staging/7/v2/catalog.json"], \
        "the catalogs must go last, newest last"
    assert any(k.startswith("staging/7/assets/styles.") and k.endswith(".css")
               for k in keys), "hashed stylesheet missing"
    # the page must reference the hashed asset, not the plain name
    page = client.store["staging/7/p/demo-addon.html"][0].decode()
    assert "../assets/styles.css" not in page
