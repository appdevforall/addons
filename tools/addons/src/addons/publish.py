import hashlib
import os
from pathlib import Path

import boto3

SHORT = "public, max-age=60"
IMMUTABLE = "public, max-age=31536000, immutable"

CONTENT_TYPES = {
    ".html": "text/html",
    ".json": "application/json",
    ".css": "text/css",
    ".js": "text/javascript",
    ".png": "image/png",
    ".svg": "image/svg+xml",
    ".cgp": "application/octet-stream",
    ".cgt": "application/octet-stream",
    ".gz": "application/gzip",
}
ATTACHMENTS = {".cgp", ".cgt", ".gz"}


def hashed_name(path: Path) -> str:
    """`app.js` -> `app.<8 hex of sha256>.js`.

    The zone's Browser Cache TTL overrides our Cache-Control for the file
    types Cloudflare caches by default, so a changed asset can sit in a
    browser for hours. A content-derived name sidesteps that: new bytes
    mean a new URL, and the old one is never asked for again.
    """
    digest = hashlib.sha256(path.read_bytes()).hexdigest()[:8]
    stem, _, suffix = path.name.rpartition(".")
    return f"{stem}.{digest}.{suffix}"


def headers_for(key: str) -> dict:
    suffix = Path(key).suffix
    headers = {
        "ContentType": CONTENT_TYPES.get(suffix, "application/octet-stream"),
        "CacheControl": IMMUTABLE if Path(key).parent.name == "assets" else SHORT,
    }
    if suffix in ATTACHMENTS:
        headers["ContentDisposition"] = f'attachment; filename="{Path(key).name}"'
    return headers


def put(client, bucket: str, key: str, path: Path) -> None:
    # upload_file streams from disk and retries; put_object would read a
    # 200 MB addon entirely into memory
    client.upload_file(str(path), bucket, key, ExtraArgs=headers_for(key))


def publish(client, bucket: str, objects: list[tuple[str, Path]],
            catalog: tuple[str, Path]) -> None:
    for key, path in objects:
        put(client, bucket, key, path)
    put(client, bucket, catalog[0], catalog[1])


def bucket_from_env() -> str:
    return os.environ.get("R2_BUCKET", "addons")


def client_from_env():
    account = os.environ["R2_ACCOUNT_ID"]
    return boto3.client(
        "s3",
        endpoint_url=f"https://{account}.r2.cloudflarestorage.com",
        aws_access_key_id=os.environ["R2_ACCESS_KEY_ID"],
        aws_secret_access_key=os.environ["R2_SECRET_ACCESS_KEY"],
        region_name="auto",
    )
