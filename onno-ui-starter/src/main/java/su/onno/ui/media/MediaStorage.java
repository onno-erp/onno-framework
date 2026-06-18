package su.onno.ui.media;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Pluggable backend for binary uploads — the SPI behind {@code POST /api/media}. The framework
 * ships a {@link FilesystemMediaStorage} default; an application (or a commercial connector) swaps
 * in S3-compatible object storage simply by exposing its own {@code MediaStorage} bean, which the
 * auto-configuration's {@code @ConditionalOnMissingBean} default then steps aside for.
 *
 * <p>Implementations must stream the content rather than buffer it whole, so multi-megabyte uploads
 * don't sit in memory. The returned {@link StoredMedia#url() url} is what callers persist; it must
 * resolve back to the bytes (via {@link #load} for framework-served backends, or directly for
 * backends that hand out their own URLs).
 */
public interface MediaStorage {

    /**
     * Persist an uploaded binary and return a reference to it.
     *
     * @param content     the file bytes; the implementation reads (and the caller closes) this stream
     * @param filename    the original client filename, already sanitized to a bare name (may be blank)
     * @param contentType the validated content type (never {@code null}/blank)
     * @param size        the declared size in bytes, or {@code -1} if the client didn't report one
     */
    StoredMedia store(InputStream content, String filename, String contentType, long size) throws IOException;

    /**
     * Read a previously stored binary back for serving by {@code GET /api/media/{key}}. The default
     * returns empty, which makes that endpoint answer 404 — appropriate for backends whose
     * {@link StoredMedia#url() url} already points at a directly reachable object-store address.
     *
     * @param key the {@link StoredMedia#key() key} produced by a prior {@link #store}
     */
    default Optional<LoadedMedia> load(String key) {
        return Optional.empty();
    }
}
