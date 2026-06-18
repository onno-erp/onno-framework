package su.onno.ui.media;

/**
 * A reference to a stored binary, returned from {@link MediaStorage#store} and serialized as the
 * JSON body of {@code POST /api/media}. The {@code url} is what callers persist into a {@code file}/
 * {@code image} attribute (or a newline-joined gallery value) — never the bytes themselves.
 *
 * @param key         the storage-internal identifier (opaque to clients; e.g. a date-sharded path
 *                    for the filesystem backend, or an object key for S3)
 * @param url         the address the UI/clients use to fetch the binary back — a framework route
 *                    (e.g. {@code /api/media/2026/06/<uuid>.jpg}) for the filesystem backend, or an
 *                    absolute object-store URL for backends that serve directly
 * @param contentType the stored content type (as accepted; never {@code null})
 * @param size        the stored size in bytes
 * @param filename    the original client filename, sanitized; may be {@code null} if none was sent
 */
public record StoredMedia(String key, String url, String contentType, long size, String filename) {
}
