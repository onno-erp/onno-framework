package su.onno.ui.media;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the {@code /api/media} binary-upload endpoint and its storage backend, under
 * the {@code onno.media.*} namespace.
 */
@ConfigurationProperties(prefix = "onno.media")
public class MediaProperties {

    /** Whether the upload endpoint and the default filesystem storage are wired at all. */
    private boolean enabled = true;

    /**
     * Largest single upload accepted. Also raises Spring's 1&nbsp;MB multipart default to match, so
     * uploads up to this size reach the controller instead of being rejected by the container.
     */
    private DataSize maxFileSize = DataSize.ofMegabytes(10);

    /**
     * Content types the endpoint accepts. Entries may be exact ({@code image/png}) or a wildcard
     * subtype ({@code image/*}). Empty means accept any type — fine for an authenticated admin
     * endpoint; set it to lock uploads down to, say, images only.
     */
    private List<String> allowedContentTypes = new ArrayList<>();

    /**
     * URL prefix the filesystem backend builds stored-media URLs from, and the path
     * {@code GET /api/media/{key}} serves from. Other backends (e.g. S3) ignore it.
     */
    private String publicBasePath = "/api/media";

    private final Filesystem filesystem = new Filesystem();

    public static class Filesystem {
        /**
         * Directory the filesystem backend writes uploads beneath. Defaults to {@code onno-media}
         * under the JVM temp dir; set an absolute, persistent path in production.
         */
        private String directory = System.getProperty("java.io.tmpdir") + "/onno-media";

        public String getDirectory() {
            return directory;
        }

        public void setDirectory(String directory) {
            this.directory = directory;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public DataSize getMaxFileSize() {
        return maxFileSize;
    }

    public void setMaxFileSize(DataSize maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    public List<String> getAllowedContentTypes() {
        return allowedContentTypes;
    }

    public void setAllowedContentTypes(List<String> allowedContentTypes) {
        this.allowedContentTypes = allowedContentTypes;
    }

    public String getPublicBasePath() {
        return publicBasePath;
    }

    public void setPublicBasePath(String publicBasePath) {
        this.publicBasePath = publicBasePath;
    }

    public Filesystem getFilesystem() {
        return filesystem;
    }
}
