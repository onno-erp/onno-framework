# Media uploads (binary ingestion)

The framework ingests binary files — images, attachments — by **streaming them to a storage backend
and persisting only a reference URL**, rather than base64-encoding bytes into a field's string value.
This keeps request bodies and database columns small, and gives API-only clients a way to upload raw
bytes without object-storage credentials of their own.

## Endpoint

```
POST /api/media        multipart/form-data, part name "file"
  → 200 { "key", "url", "contentType", "size", "filename" }

GET  /api/media/{key}  → the stored bytes (filesystem backend only)
```

`POST` validates the content type against the allow-list and the size against the configured cap,
streams the file to the active `MediaStorage`, and returns a reference. Persist the `url` into a
`String` attribute (single value) or newline-join several for a gallery — the same attach-by-URL
shape the generic catalog/document API already accepts. Both endpoints sit under `/api/**`, so they
require an authenticated session; `POST` is CSRF-protected like every other mutating call.

`GET /api/media/{key}` serves bytes for backends the framework streams (the filesystem default).
Backends that hand out their own public URLs (e.g. a public S3 bucket) don't implement
`MediaStorage.load`, and the persisted `url` points straight at them instead.

## Admin UI widgets

Set a field hint on an `EntityView` field; the admin form then streams the file on drop/select and
stores the returned URL:

```java
f.field("photo").widget("image")      // single image
 .field("avatar").widget("avatar")    // small round image
 .field("gallery").widget("gallery")  // several images, newline-joined URLs
 .field("contract").widget("file");   // any file type
```

Legacy records that still hold a base64 `data:` URL keep rendering, so the switch from base64 to
binary upload is backward compatible — no migration required.

## Configuration (`onno.media.*`)

| Property | Default | Meaning |
| --- | --- | --- |
| `onno.media.enabled` | `true` | Wire the endpoint and the default filesystem storage. |
| `onno.media.max-file-size` | `10MB` | Largest accepted upload. Also raises Spring's 1 MB multipart default to match. |
| `onno.media.allowed-content-types` | _(empty = any)_ | Exact (`image/png`) or wildcard-subtype (`image/*`) types to accept. |
| `onno.media.public-base-path` | `/api/media` | URL prefix the filesystem backend builds reference URLs from. |
| `onno.media.filesystem.directory` | `${java.io.tmpdir}/onno-media` | Where the filesystem backend writes. Set an absolute, persistent path in production. |

## Pluggable storage (the `MediaStorage` SPI)

`su.onno.ui.media.MediaStorage` is the backend SPI:

```java
StoredMedia store(InputStream content, String filename, String contentType, long size);
default Optional<LoadedMedia> load(String key) { return Optional.empty(); }
```

The framework ships `FilesystemMediaStorage` (date-sharded, path-traversal-safe) as the default. It
backs off the moment an application — or a commercial connector — contributes its own `MediaStorage`
bean, so swapping in S3-compatible object storage is done by addition, not by editing the framework:

```java
@Bean
MediaStorage s3MediaStorage(/* your S3 client + config */) {
    return new MyS3MediaStorage(...);  // store() → PutObject; url() → the object's public/presigned URL
}
```

Such a backend typically returns an absolute object-store URL and leaves `load` unimplemented; for a
private bucket, return a framework route and stream through `load` (as the filesystem default does),
or hand back a presigned URL.
