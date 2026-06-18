package su.onno.ui.media;

import org.springframework.core.io.Resource;

/**
 * A stored binary read back for serving, returned from {@link MediaStorage#load}. Backends that
 * keep bytes the framework can stream (filesystem) return one of these so {@code GET /api/media/...}
 * can pipe it to the client; backends that hand out their own public URLs (e.g. a public S3 bucket)
 * have no need to implement {@code load} — the persisted {@code url} points straight at them.
 *
 * @param resource    the readable bytes
 * @param contentType the content type to send back (never {@code null})
 * @param size        the size in bytes, or {@code -1} if unknown
 * @param filename    the original filename for a {@code Content-Disposition} hint, or {@code null}
 */
public record LoadedMedia(Resource resource, String contentType, long size, String filename) {
}
