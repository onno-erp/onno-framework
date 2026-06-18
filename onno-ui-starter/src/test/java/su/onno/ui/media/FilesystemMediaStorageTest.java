package su.onno.ui.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FilesystemMediaStorageTest {

    @Test
    void storesUnderDateShardedKeyAndBuildsPublicUrl(@TempDir Path dir) throws Exception {
        FilesystemMediaStorage storage = new FilesystemMediaStorage(dir, "/api/media");
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);

        StoredMedia stored = storage.store(new ByteArrayInputStream(bytes), "photo.png", "image/png", bytes.length);

        assertThat(stored.key()).matches("\\d{4}/\\d{2}/[0-9a-f-]{36}\\.png");
        assertThat(stored.url()).isEqualTo("/api/media/" + stored.key());
        assertThat(stored.contentType()).isEqualTo("image/png");
        assertThat(stored.size()).isEqualTo(bytes.length);
        assertThat(stored.filename()).isEqualTo("photo.png");
        assertThat(dir.resolve(stored.key())).exists();
    }

    @Test
    void roundTripsThroughLoad(@TempDir Path dir) throws Exception {
        FilesystemMediaStorage storage = new FilesystemMediaStorage(dir, "/api/media");
        byte[] bytes = "round-trip".getBytes(StandardCharsets.UTF_8);

        StoredMedia stored = storage.store(new ByteArrayInputStream(bytes), "doc.txt", "text/plain", bytes.length);
        Optional<LoadedMedia> loaded = storage.load(stored.key());

        assertThat(loaded).isPresent();
        assertThat(loaded.get().size()).isEqualTo(bytes.length);
        assertThat(loaded.get().resource().getContentAsByteArray()).isEqualTo(bytes);
    }

    @Test
    void derivesExtensionFromContentTypeWhenFilenameHasNone(@TempDir Path dir) throws Exception {
        FilesystemMediaStorage storage = new FilesystemMediaStorage(dir, "/api/media");

        StoredMedia jpeg = storage.store(new ByteArrayInputStream(new byte[] {1}), "noext", "image/jpeg", 1);
        // image/jpeg normalizes to the conventional .jpg, not .jpeg.
        assertThat(jpeg.key()).endsWith(".jpg");

        StoredMedia unknown = storage.store(new ByteArrayInputStream(new byte[] {1}), null, "application/octet-stream", 1);
        // No usable subtype token → no extension rather than a bogus one.
        assertThat(unknown.key()).matches("\\d{4}/\\d{2}/[0-9a-f-]{36}");
    }

    @Test
    void loadRejectsPathTraversal(@TempDir Path dir) {
        FilesystemMediaStorage storage = new FilesystemMediaStorage(dir, "/api/media");

        assertThat(storage.load("../../etc/passwd")).isEmpty();
    }

    @Test
    void loadMissingKeyIsEmpty(@TempDir Path dir) {
        FilesystemMediaStorage storage = new FilesystemMediaStorage(dir, "/api/media");

        assertThat(storage.load("2026/06/does-not-exist.png")).isEmpty();
    }

    @Test
    void trailingSlashInBasePathIsNotDoubled(@TempDir Path dir) throws Exception {
        FilesystemMediaStorage storage = new FilesystemMediaStorage(dir, "/api/media/");

        StoredMedia stored = storage.store(new ByteArrayInputStream(new byte[] {1}), "a.png", "image/png", 1);

        assertThat(stored.url()).isEqualTo("/api/media/" + stored.key());
    }
}
