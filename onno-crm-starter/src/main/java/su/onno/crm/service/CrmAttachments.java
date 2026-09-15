package su.onno.crm.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import su.onno.crm.domain.ConversationMessage;
import su.onno.ui.UiMessages;
import su.onno.ui.media.LoadedMedia;
import su.onno.ui.media.MediaProperties;
import su.onno.ui.media.MediaStorage;

/**
 * The files on a message.
 *
 * <p>A message stores references, not bytes: {@code attachments} is a newline-joined list of the
 * URLs {@code POST /api/media} handed back, the same shape the framework's {@code gallery} field
 * widget persists. This service is the one place that turns those URLs back into something usable —
 * a name and a type for the inbox to render, and a readable stream for a channel to upload.
 *
 * <p>Only URLs this application issued are accepted. An agent's composer sends back what the media
 * endpoint returned, so anything else — a link typed by hand, a URL arriving in a webhook payload —
 * is refused rather than stored and later fetched by a delivery worker on the server's behalf.
 */
@Service
public class CrmAttachments {

    /** A file on a message. {@code key} is null for a backend that hands out its own public URLs. */
    public record Attachment(String url, String key, String filename, String contentType, long size, boolean image) {}

    /**
     * One file as the API describes it. The storage {@code key} stays server-side: a client has no
     * use for it and no business addressing the backend directly. Shared by every feed that carries
     * messages, so a file reads the same in the chat pane and in a contact's history.
     */
    public record View(String url, String filename, String contentType, long size, boolean image) {}

    /** How many files one message may carry. Beyond this a second message is the honest answer. */
    public static final int MAX_PER_MESSAGE = 10;

    private static final Set<String> IMAGE_TYPES =
            Set.of("image/avif", "image/gif", "image/jpeg", "image/png", "image/webp");

    private final MediaStorage storage;
    private final String basePath;
    private final UiMessages messages;

    public CrmAttachments(MediaStorage storage, MediaProperties properties) {
        this(storage, properties, new UiMessages(java.util.Map.of()));
    }

    public CrmAttachments(MediaStorage storage, MediaProperties properties, UiMessages messages) {
        this.messages = messages;
        this.storage = storage;
        var configured = properties == null ? null : properties.getPublicBasePath();
        this.basePath = configured == null || configured.isBlank() ? "/api/media" : configured;
    }

    /**
     * Media ingestion is opt-out ({@code onno.media.enabled}), and a CRM installed without it has
     * nowhere to put a file. Rather than fail at send time, the module reports no attachment
     * capability at all, and the composer offers no paperclip.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public CrmAttachments(org.springframework.beans.factory.ObjectProvider<MediaStorage> storage,
            org.springframework.beans.factory.ObjectProvider<MediaProperties> properties,
            org.springframework.beans.factory.ObjectProvider<UiMessages> messages) {
        this(storage.getIfAvailable(), properties.getIfAvailable(MediaProperties::new),
                messages.getIfAvailable(() -> new UiMessages(java.util.Map.of())));
    }

    /** How many files this deployment can carry at all; zero when uploads are turned off. */
    public int limit() { return storage == null ? 0 : MAX_PER_MESSAGE; }

    /**
     * Why files cannot travel here, for a channel to pass on to the composer. Blank when they can.
     *
     * <p>Resolved through the app's chrome strings rather than returned as a literal: this sentence
     * is read by an agent in the composer's tooltip, so it belongs in whatever language the rest of
     * the inbox is in. Thrown validation text stays English, as it does elsewhere in this service.
     */
    public String unavailableReason() {
        return storage == null ? messages.get("crm.attachments.disabled") : "";
    }

    /** The stored value for a list of media URLs, or null when there are none. */
    public String store(List<String> urls) {
        var accepted = accept(urls);
        return accepted.isEmpty() ? null : String.join("\n", accepted);
    }

    /**
     * Validate the URLs a caller wants to attach. Duplicates are collapsed, blanks dropped, and
     * anything that is not a reference this application's media endpoint issued is refused.
     */
    public List<String> accept(List<String> urls) {
        if (urls == null || urls.isEmpty()) return List.of();
        if (storage == null) throw new IllegalArgumentException(unavailableReason());
        var unique = new LinkedHashSet<String>();
        for (var url : urls) {
            if (url == null || url.isBlank()) continue;
            var trimmed = url.trim();
            if (trimmed.length() > 500) throw new IllegalArgumentException("Attachment reference is too long");
            if (key(trimmed) == null) throw new IllegalArgumentException("Attach files through the upload button");
            unique.add(trimmed);
        }
        if (unique.size() > MAX_PER_MESSAGE)
            throw new IllegalArgumentException("Attach at most " + MAX_PER_MESSAGE + " files to one message");
        if (String.join("\n", unique).length() > 4000)
            throw new IllegalArgumentException("Too many attachment references for one message");
        return List.copyOf(unique);
    }

    public List<String> urls(ConversationMessage message) {
        var value = message == null ? null : message.getAttachments();
        if (value == null || value.isBlank()) return List.of();
        return value.lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
    }

    /** The files on a message, described well enough to render or to upload. */
    public List<Attachment> of(ConversationMessage message) {
        var described = new ArrayList<Attachment>();
        for (var url : urls(message)) described.add(describe(url));
        return List.copyOf(described);
    }

    /** The files on a message as the API describes them. */
    public List<View> viewsOf(ConversationMessage message) {
        return of(message).stream()
                .map(file -> new View(file.url(), file.filename(), file.contentType(), file.size(), file.image()))
                .toList();
    }

    public Attachment describe(String url) {
        var key = key(url);
        var loaded = key == null ? Optional.<LoadedMedia>empty() : load(key);
        var contentType = loaded.map(LoadedMedia::contentType).orElse("application/octet-stream");
        var filename = loaded.map(LoadedMedia::filename).filter(name -> name != null && !name.isBlank())
                .orElseGet(() -> leaf(url));
        return new Attachment(url, key, filename, contentType, loaded.map(LoadedMedia::size).orElse(-1L),
                IMAGE_TYPES.contains(contentType.toLowerCase(Locale.ROOT)));
    }

    /**
     * Open an attachment for sending. Empty for a backend whose URLs are already reachable from the
     * outside (a public bucket) — a channel that takes a link sends the URL instead of the bytes.
     */
    public Optional<InputStream> open(Attachment attachment) {
        if (attachment.key() == null) return Optional.empty();
        return load(attachment.key()).map(media -> {
            try {
                return media.resource().getInputStream();
            } catch (IOException ex) {
                throw new IllegalStateException("Attachment could not be read", ex);
            }
        });
    }

    public byte[] bytes(Attachment attachment) {
        try (var stream = open(attachment).orElseThrow(
                () -> new IllegalArgumentException("Attachment is no longer stored"))) {
            return stream.readAllBytes();
        } catch (IOException ex) {
            throw new IllegalStateException("Attachment could not be read", ex);
        }
    }

    private Optional<LoadedMedia> load(String key) {
        // Messages sent before uploads were turned off still render; they just describe themselves
        // from the URL rather than from the store.
        if (storage == null) return Optional.empty();
        try {
            return storage.load(key);
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    /**
     * The storage key behind a persisted URL, or null when the URL is not one this application
     * issued. Path traversal and absolute or protocol-relative URLs are rejected outright.
     */
    private String key(String url) {
        var prefix = basePath.endsWith("/") ? basePath : basePath + "/";
        if (!url.startsWith(prefix)) return null;
        var key = url.substring(prefix.length());
        if (key.isBlank() || key.contains("..") || key.contains("//") || key.startsWith("/")
                || !key.matches("[A-Za-z0-9_./-]+")) return null;
        return key;
    }

    private static String leaf(String url) {
        var cut = url.lastIndexOf('/');
        var name = cut < 0 ? url : url.substring(cut + 1);
        return name.isBlank() ? "attachment" : name;
    }
}
