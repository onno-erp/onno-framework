package su.onno.web.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/**
 * The default {@link MediaStorage}: streams uploads to a directory tree on disk and serves them
 * back through {@code GET /api/media/{key}}. Keys are date-sharded ({@code yyyy/MM/<uuid>.<ext>})
 * so a single directory never accumulates unbounded entries and the stored name leaks nothing about
 * the original. Suitable for single-node deployments and development; multi-node setups should swap
 * in an object-store backend by exposing their own {@code MediaStorage} bean.
 */
public class FilesystemMediaStorage implements MediaStorage {

    private static final Logger log = LoggerFactory.getLogger(FilesystemMediaStorage.class);
    private static final DateTimeFormatter SHARD = DateTimeFormatter.ofPattern("yyyy/MM", Locale.ROOT);

    private final Path root;
    /** Sidecar {@code <key>.properties} files with the original filename and content type; never served. */
    private final Path metadataRoot;
    private final String publicBasePath;

    public FilesystemMediaStorage(Path root, String publicBasePath) {
        this.root = root.toAbsolutePath().normalize();
        this.metadataRoot = this.root.resolve(".metadata");
        // Strip a trailing slash so we can join with "/" + key without doubling it.
        this.publicBasePath = publicBasePath.endsWith("/")
                ? publicBasePath.substring(0, publicBasePath.length() - 1)
                : publicBasePath;
    }

    @Override
    public StoredMedia store(InputStream content, String filename, String contentType, long size) throws IOException {
        String ext = extensionFor(filename, contentType);
        String key = LocalDate.now().format(SHARD) + "/" + UUID.randomUUID() + ext;
        Path target = resolveKey(key);
        Files.createDirectories(target.getParent());
        long written = Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        try {
            writeMetadata(key, filename, contentType);
        } catch (IOException e) {
            Files.deleteIfExists(target);
            throw e;
        }
        return new StoredMedia(key, publicBasePath + "/" + key, contentType, written, blankToNull(filename));
    }

    @Override
    public Optional<LoadedMedia> load(String key) {
        Path target;
        try {
            target = resolveKey(key);
        } catch (IllegalArgumentException traversal) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        String contentType;
        long size;
        try {
            contentType = Files.probeContentType(target);
            size = Files.size(target);
        } catch (IOException e) {
            return Optional.empty();
        }
        if (contentType == null) {
            contentType = "application/octet-stream";
        }
        String filename = target.getFileName().toString();
        Path metadataFile = metadataFileFor(root.relativize(target).toString());
        if (Files.isRegularFile(metadataFile)) {
            Properties metadata = new Properties();
            try (InputStream input = Files.newInputStream(metadataFile)) {
                metadata.load(input);
                filename = metadata.getProperty("filename", filename);
                contentType = validContentType(key, metadata.getProperty("contentType"), contentType);
            } catch (IOException | IllegalArgumentException e) {
                // The file itself stays readable (with its probed type) when its metadata is corrupt.
                log.debug("Ignoring unreadable media metadata {}: {}", metadataFile, e.toString());
            }
        }
        return Optional.of(new LoadedMedia(new FileSystemResource(target), contentType, size, filename));
    }

    /**
     * The sidecar's content type when it parses as a concrete media type, else the probed one — so a
     * hand-edited or corrupt sidecar can never make serving fail.
     */
    private static String validContentType(String key, String stored, String probed) {
        if (stored == null || stored.isBlank()) {
            return probed;
        }
        try {
            MediaType parsed = MediaType.parseMediaType(stored);
            if (!parsed.isWildcardType() && !parsed.isWildcardSubtype()) {
                return stored;
            }
        } catch (InvalidMediaTypeException ignored) {
            // fall through to the probed type
        }
        log.debug("Ignoring invalid stored content type '{}' for media {}; using {}", stored, key, probed);
        return probed;
    }

    private void writeMetadata(String key, String filename, String contentType) throws IOException {
        Properties metadata = new Properties();
        if (blankToNull(filename) != null) {
            metadata.setProperty("filename", filename);
        }
        if (blankToNull(contentType) != null) {
            metadata.setProperty("contentType", contentType);
        }
        Path metadataFile = metadataFileFor(key);
        Files.createDirectories(metadataFile.getParent());
        try (OutputStream output = Files.newOutputStream(metadataFile)) {
            metadata.store(output, null);
        }
    }

    private Path metadataFileFor(String key) {
        return metadataRoot.resolve(key + ".properties");
    }

    /**
     * Resolve a key under {@link #root}, rejecting any path that escapes it (e.g. {@code ../}) or
     * lands in the metadata subtree. This is the single traversal guard for both store and load.
     */
    private Path resolveKey(String key) {
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root) || resolved.startsWith(metadataRoot)) {
            throw new IllegalArgumentException("key escapes media storage: " + key);
        }
        return resolved;
    }

    /** A leading-dot extension drawn from the filename, falling back to the content type. */
    private static String extensionFor(String filename, String contentType) {
        if (filename != null) {
            int dot = filename.lastIndexOf('.');
            if (dot >= 0 && dot < filename.length() - 1) {
                String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (ext.matches("[a-z0-9]{1,8}")) {
                    return "." + ext;
                }
            }
        }
        if (contentType != null) {
            int slash = contentType.indexOf('/');
            if (slash >= 0 && slash < contentType.length() - 1) {
                String sub = contentType.substring(slash + 1).toLowerCase(Locale.ROOT);
                // Drop a "+xml" structured suffix and any ";charset=..." parameters, then keep the
                // subtype only if it's a clean alnum token. Compound subtypes like "octet-stream" or
                // "vnd.ms-excel" yield no extension rather than a misleading fragment.
                sub = sub.split("[;+]", 2)[0].trim();
                if (sub.matches("[a-z0-9]{1,8}")) {
                    return "." + ("jpeg".equals(sub) ? "jpg" : sub);
                }
            }
        }
        return "";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
