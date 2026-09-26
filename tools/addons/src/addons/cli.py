import argparse
import json
import sys
from pathlib import Path

from addons import catalog, check, discover, model, page, publish, tarball


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="addons")
    parser.add_argument("--root", type=Path, default=Path.cwd())
    sub = parser.add_subparsers(dest="command", required=True)
    discover_parser = sub.add_parser("discover")
    discover_parser.add_argument(
        "--include-skipped", action="store_true",
        help="also list skipped addons; for compile coverage, not publishing")
    discover_parser.add_argument(
        "--plugins-only", action="store_true",
        help="list only addons with a Gradle build; for callers that run Gradle")
    sub.add_parser("check")

    catalog_parser = sub.add_parser("catalog")
    catalog_parser.add_argument("--dist", type=Path, required=True)
    catalog_parser.add_argument("--out", type=Path, required=True)
    catalog_parser.add_argument("--only", nargs="*", default=None)
    catalog_parser.add_argument("--base", default=catalog.BASE,
                                help="site base the catalog is published under")

    publish_parser = sub.add_parser("publish")
    publish_parser.add_argument("--dist", type=Path, required=True)
    publish_parser.add_argument("--prefix", default="")
    publish_parser.add_argument("--only", nargs="*", default=None)

    tarball_parser = sub.add_parser("tarball")
    tarball_parser.add_argument("--out", type=Path, required=True)
    tarball_parser.add_argument("--only", nargs="*", default=None)
    args = parser.parse_args(argv)

    if args.command == "discover":
        # repo-relative, not bare names: callers cd into these and match them
        # against changed-file lists, so the location has to survive
        for path in discover.find_addons(args.root,
                                         include_skipped=args.include_skipped,
                                         plugins_only=args.plugins_only):
            print(path.relative_to(args.root).as_posix())
        return 0

    if args.command == "check":
        problems = check.run(args.root)
        for problem in problems:
            print(problem, file=sys.stderr)
        return 1 if problems else 0

    if args.command == "catalog":
        # One document per published major. --out names the current one; the older
        # ones land beside it as catalog.v<N>.json, because every major keeps being
        # written for consumers pinned to it (design section 10.2).
        args.out.parent.mkdir(parents=True, exist_ok=True)
        for version in (catalog.LATEST, catalog.LEGACY):
            document = catalog.build(args.root, args.dist, args.base, args.only,
                                     version)
            out = (args.out if version == catalog.LATEST
                   else args.out.with_name(f"{args.out.stem}.v{version}.json"))
            out.write_text(json.dumps(document, indent=2) + "\n")
            print(f"wrote {out} (v{version}) with {len(document['addons'])} addons")
        return 0

    if args.command == "tarball":
        args.out.mkdir(parents=True, exist_ok=True)
        for addon in discover.find_addons(args.root, args.only):
            meta = model.metadata(addon)
            archive = tarball.build(args.root, addon, args.out, meta)
            # None means the addon ships no source tarball; a template's .cgt
            # is its own source. Say so rather than printing nothing, so a
            # workflow log shows the addon was considered.
            if archive is None:
                print(f"skipped {addon.name}: no source tarball for a template")
            else:
                print(f"built {archive.name}")
        return 0

    if args.command == "publish":
        dist, prefix = args.dist, args.prefix
        # The catalog describes the whole site, so a *subset* published to the
        # live keys would erase every other addon. Selecting everything is
        # fine, and the workflow always passes --only even for "all".
        if args.only is not None:
            if not args.only:
                raise SystemExit("--only needs at least one addon")
            selected = discover.find_addons(args.root, args.only)
            if not prefix and len(selected) < len(discover.find_addons(args.root)):
                raise SystemExit(
                    "refusing to publish a subset to the live site: the "
                    "catalog would replace all addons with just "
                    f"{', '.join(args.only)}. Use --prefix for a staging run, "
                    "or publish every addon.")
        site = args.root / "site"
        template = (site / "page.template.html").read_text()
        # content-hashed asset names, so a changed asset always gets a new URL
        sources = {"styles.css": site / "styles.css",
                   "app.js": site / "app.js",
                   "adfa-logo.svg": site / "assets" / "adfa-logo.svg"}
        assets = {n: publish.hashed_name(p) for n, p in sources.items()}
        objects = [(f"{prefix}assets/{h}", sources[n]) for n, h in assets.items()]

        def with_hashed_assets(text: str) -> str:
            for name, hashed in assets.items():
                text = text.replace(f"assets/{name}", f"assets/{hashed}")
            return text

        index_file = dist / "index.html"
        index_file.write_text(with_hashed_assets((site / "index.html").read_text()))
        objects.append((f"{prefix}index.html", index_file))
        for addon in discover.find_addons(args.root, args.only):
            slug = model.slug(addon.name)
            wrapped = page.wrap((addon / f"{slug}.html").read_text(),
                                model.display_name(addon.name), template)
            page_file = dist / f"{slug}.page.html"
            page_file.write_text(with_hashed_assets(wrapped))
            # A template keeps its icons at the addon root: src/main/assets is
            # an Android path and it has no Android module (ADFA-6252).
            icons = (addon if discover.is_template(addon)
                     else addon / "src" / "main" / "assets")
            suffix = catalog.artifact_suffix(addon)
            objects += [
                (f"{prefix}p/{slug}.html", page_file),
                (f"{prefix}p/{slug}.png", icons / "icon_day.png"),
                (f"{prefix}p/{slug}-night.png", icons / "icon_night.png"),
                (f"{prefix}dl/{slug}.{suffix}", dist / f"{slug}.{suffix}"),
            ]
            # No source tarball for a template; see tarball.build().
            if not discover.is_template(addon):
                objects.append((f"{prefix}src/{slug}-src.tar.gz",
                                dist / f"{slug}-src.tar.gz"))
        # Every published major, newest last: publish.publish() writes the catalogs
        # after everything they reference, and a consumer pinned to v1 must keep
        # finding a v1 document (design section 10.2).
        catalogs = []
        for version in (catalog.LEGACY, catalog.LATEST):
            objects.append((f"{prefix}v{version}/catalog.schema.json",
                            catalog.schema_file(args.root, version)))
            local = (dist / "catalog.json" if version == catalog.LATEST
                     else dist / f"catalog.v{version}.json")
            catalogs.append((f"{prefix}v{version}/catalog.json", local))
        publish.publish(publish.client_from_env(), publish.bucket_from_env(),
                        objects, catalogs)
        print(f"published {len(objects) + len(catalogs)} objects")
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
