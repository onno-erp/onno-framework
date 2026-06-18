package su.onno.ui.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises {@link MediaController} against a real {@link FilesystemMediaStorage} by invoking its
 * handler methods directly (no servlet container), covering validation, the store→serve round trip,
 * and the 404 for an unknown key.
 */
class MediaControllerTest {

    private MediaController controller(Path dir, List<String> allowed) {
        MediaProperties props = new MediaProperties();
        props.getFilesystem().setDirectory(dir.toString());
        props.setAllowedContentTypes(allowed);
        return new MediaController(new FilesystemMediaStorage(dir, props.getPublicBasePath()), props);
    }

    @Test
    void uploadStoresAndServeRoundTrips(@TempDir Path dir) throws Exception {
        MediaController controller = controller(dir, List.of());
        byte[] bytes = "the-bytes".getBytes(StandardCharsets.UTF_8);

        StoredMedia stored = controller.upload(file("p.png", "image/png", bytes));

        assertThat(stored.url()).startsWith("/api/media/");
        assertThat(stored.contentType()).isEqualTo("image/png");
        assertThat(stored.size()).isEqualTo(bytes.length);

        // {*key} arrives with a leading slash; the controller strips it.
        ResponseEntity<Resource> served = controller.serve("/" + stored.key());
        assertThat(served.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(served.getBody()).isNotNull();
        assertThat(served.getBody().getContentAsByteArray()).isEqualTo(bytes);
        assertThat(served.getHeaders().getContentType()).hasToString("image/png");
    }

    @Test
    void emptyUploadIsBadRequest(@TempDir Path dir) {
        MediaController controller = controller(dir, List.of());

        assertThatThrownBy(() -> controller.upload(file("e.png", "image/png", new byte[0])))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void disallowedContentTypeIsRejected(@TempDir Path dir) {
        MediaController controller = controller(dir, List.of("image/*"));

        assertThatThrownBy(() -> controller.upload(file("x.txt", "text/plain", new byte[] {1})))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void wildcardSubtypeAllowsMatchingType(@TempDir Path dir) throws Exception {
        MediaController controller = controller(dir, List.of("image/*"));

        StoredMedia stored = controller.upload(file("ok.jpg", "image/jpeg", new byte[] {1, 2, 3}));

        assertThat(stored.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void serveUnknownKeyIs404(@TempDir Path dir) {
        MediaController controller = controller(dir, List.of());

        assertThatThrownBy(() -> controller.serve("/2026/06/nope.png"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void contentTypeParametersAreStrippedBeforeValidation(@TempDir Path dir) throws Exception {
        MediaController controller = controller(dir, List.of("text/plain"));

        StoredMedia stored = controller.upload(file("c.txt", "text/plain; charset=utf-8", new byte[] {1}));

        // The ";charset=..." parameter is dropped, so validation and storage see the bare type.
        assertThat(stored.contentType()).isEqualTo("text/plain");
    }

    /** A minimal {@link MultipartFile} over an in-memory byte array. */
    private static MultipartFile file(String name, String contentType, byte[] content) {
        return new MultipartFile() {
            @Override public String getName() { return "file"; }
            @Override public String getOriginalFilename() { return name; }
            @Override public String getContentType() { return contentType; }
            @Override public boolean isEmpty() { return content.length == 0; }
            @Override public long getSize() { return content.length; }
            @Override public byte[] getBytes() { return content; }
            @Override public InputStream getInputStream() { return new ByteArrayInputStream(content); }
            @Override public void transferTo(java.io.File dest) throws IOException {
                Files.write(dest.toPath(), content);
            }
            @Override public void transferTo(Path dest) throws IOException {
                Files.write(dest, content);
            }
            void writeTo(OutputStream out) throws IOException { out.write(content); }
        };
    }
}
