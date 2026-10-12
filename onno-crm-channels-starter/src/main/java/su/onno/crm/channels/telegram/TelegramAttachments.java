package su.onno.crm.channels.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import su.onno.crm.service.CrmAttachments;

/**
 * Shared file import for ordinary and business Telegram messages.
 *
 * <p>Runs before the polling transaction and workspace lock are taken: a download can take up to
 * 30 seconds, and every CRM write would otherwise wait on it. Importing never throws — a file that
 * cannot be fetched or stored becomes a short placeholder line on the message, so one bad file
 * can never stall intake for every other chat.
 */
final class TelegramAttachments {
    private static final Logger log = LoggerFactory.getLogger(TelegramAttachments.class);
    private static final long DOWNLOAD_LIMIT = 20L * 1024 * 1024;
    private static final List<String> FILE_KINDS =
            List.of("document", "video", "animation", "audio", "voice", "video_note", "sticker");

    static final String UNAVAILABLE = "[File unavailable: media storage is disabled]";
    static final String TOO_LARGE = "[File exceeds Telegram's 20 MB download limit]";
    static final String NOT_DOWNLOADED = "[File could not be downloaded from Telegram]";
    static final String NOT_IMPORTED = "[File could not be imported]";
    static final String EMPTY = "[Empty file skipped]";
    static final String OVER_MEDIA_LIMIT = "[File skipped: exceeds the media size limit]";
    static final String TYPE_NOT_ALLOWED = "[File skipped: file type is not allowed]";

    private TelegramAttachments() {}

    /** Stored attachment value (or null) plus a placeholder line to append to the body (or blank). */
    record Imported(String urls, String failure) {
        static final Imported NONE = new Imported(null, "");
    }

    private record FileRef(JsonNode file, String name, String type) {}

    /** Whether the message carries a file this importer would try to fetch. */
    static boolean hasFile(JsonNode message) {
        return message != null && find(message) != null;
    }

    static Imported read(JsonNode message, TelegramClient client, CrmAttachments storage) {
        if (message == null) return Imported.NONE;
        FileRef found = find(message);
        if (found == null) return Imported.NONE;
        if (storage.limit() == 0) return new Imported(null, UNAVAILABLE);
        JsonNode file = found.file();
        if (file.path("file_size").asLong() > DOWNLOAD_LIMIT) return new Imported(null, TOO_LARGE);
        try {
            byte[] content = client.download(file.path("file_id").asText());
            String url = storage.ingest(file.path("file_name").asText(found.name()),
                    file.path("mime_type").asText(found.type()), content);
            return new Imported(storage.store(List.of(url)), "");
        } catch (TelegramClient.ApiFailure error) {
            return new Imported(null, NOT_DOWNLOADED);
        } catch (CrmAttachments.RejectedAttachment rejected) {
            // The deployment's media policy (onno.media.*) refused the file.
            return new Imported(null, switch (rejected.reason()) {
                case EMPTY -> EMPTY;
                case TOO_LARGE -> OVER_MEDIA_LIMIT;
                case TYPE_NOT_ALLOWED -> TYPE_NOT_ALLOWED;
            });
        } catch (IllegalArgumentException error) {
            // TelegramClient.download refuses files over the Bot API limit after getFile reports the size.
            return new Imported(null, TOO_LARGE);
        } catch (RuntimeException error) {
            // Storage failures (disabled backend, I/O) must not block the rest of the update.
            log.warn("Telegram file import skipped ({})", error.getClass().getSimpleName());
            return new Imported(null, NOT_IMPORTED);
        }
    }

    private static FileRef find(JsonNode message) {
        JsonNode photos = message.path("photo");
        if (photos.isArray() && !photos.isEmpty()) {
            return new FileRef(photos.get(photos.size() - 1), "photo.jpg", "image/jpeg");
        }
        for (String kind : FILE_KINDS) {
            if (!message.has(kind)) continue;
            JsonNode file = message.path(kind);
            String type = switch (kind) {
                case "video", "animation", "video_note" -> "video/mp4";
                case "voice" -> "audio/ogg";
                case "audio" -> "audio/mpeg";
                case "sticker" -> file.path("is_video").asBoolean() ? "video/webm"
                        : file.path("is_animated").asBoolean() ? "application/gzip" : "image/webp";
                default -> "application/octet-stream";
            };
            String extension = switch (type) {
                case "video/mp4" -> ".mp4";
                case "audio/ogg" -> ".ogg";
                case "audio/mpeg" -> ".mp3";
                case "image/webp" -> ".webp";
                case "video/webm" -> ".webm";
                default -> "";
            };
            return new FileRef(file, kind + extension, type);
        }
        return null;
    }
}
