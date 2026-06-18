package su.onno.ui.media;

import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The framework's binary ingestion endpoint. {@code POST /api/media} streams an uploaded file to the
 * configured {@link MediaStorage} and returns a {@link StoredMedia} reference; callers persist its
 * {@code url} into a {@code file}/{@code image} attribute instead of base64-ing bytes through the
 * generic catalog API. {@code GET /api/media/{key}} serves the bytes back for storage backends that
 * the framework streams (the filesystem default); backends that hand out their own public URLs
 * simply don't implement {@link MediaStorage#load} and this answers 404.
 */
@RestController
@RequestMapping("/api/media")
public class MediaController {

    private final MediaStorage storage;
    private final MediaProperties properties;

    public MediaController(MediaStorage storage, MediaProperties properties) {
        this.storage = storage;
        this.properties = properties;
    }

    @PostMapping
    public StoredMedia upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No file provided");
        }
        String contentType = normalizeContentType(file.getContentType());
        requireAllowed(contentType);
        try {
            return storage.store(file.getInputStream(), sanitizeFilename(file.getOriginalFilename()),
                    contentType, file.getSize());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not store upload", e);
        }
    }

    @GetMapping("/{*key}")
    public ResponseEntity<Resource> serve(@PathVariable String key) {
        // {*key} captures with a leading slash; storage keys have none.
        String normalized = key.startsWith("/") ? key.substring(1) : key;
        Optional<LoadedMedia> loaded = storage.load(normalized);
        if (loaded.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        LoadedMedia media = loaded.get();
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(media.contentType()))
                // Keys are immutable (uuid-named), so the bytes behind one never change.
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        if (media.size() >= 0) {
            builder.contentLength(media.size());
        }
        if (media.filename() != null) {
            builder.header(HttpHeaders.CONTENT_DISPOSITION,
                    "inline; filename=\"" + media.filename().replace("\"", "") + "\"");
        }
        return builder.body(media.resource());
    }

    /** Reduce a raw client content type to its bare {@code type/subtype}, lower-cased. */
    private static String normalizeContentType(String raw) {
        if (raw == null || raw.isBlank()) {
            return "application/octet-stream";
        }
        int semi = raw.indexOf(';');
        return (semi >= 0 ? raw.substring(0, semi) : raw).trim().toLowerCase(Locale.ROOT);
    }

    private void requireAllowed(String contentType) {
        List<String> allowed = properties.getAllowedContentTypes();
        if (allowed == null || allowed.isEmpty()) {
            return; // empty allow-list = accept anything
        }
        for (String pattern : allowed) {
            if (matches(pattern.trim().toLowerCase(Locale.ROOT), contentType)) {
                return;
            }
        }
        throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Content type not allowed: " + contentType);
    }

    /** Exact match, or a wildcard-subtype pattern like {@code image/*}. */
    private static boolean matches(String pattern, String contentType) {
        if (pattern.equals(contentType) || pattern.equals("*/*")) {
            return true;
        }
        if (pattern.endsWith("/*")) {
            String prefix = pattern.substring(0, pattern.length() - 1); // keep trailing slash
            return contentType.startsWith(prefix);
        }
        return false;
    }

    /** Keep just the leaf name (clients sometimes send a full path) and strip control characters. */
    private static String sanitizeFilename(String original) {
        if (original == null) {
            return null;
        }
        String leaf = StringUtils.getFilename(original.replace('\\', '/'));
        if (leaf == null) {
            return null;
        }
        return leaf.replaceAll("[\\p{Cntrl}]", "").trim();
    }
}
