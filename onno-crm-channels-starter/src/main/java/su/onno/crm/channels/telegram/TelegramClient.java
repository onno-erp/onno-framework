package su.onno.crm.channels.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Telegram-only HTTP boundary; never exposes token-bearing URLs or provider exception bodies. */
public class TelegramClient {
    private final String endpoint;
    private final String fileEndpoint;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public TelegramClient(String token, ObjectMapper json) {
        this("https://api.telegram.org", token, json);
    }

    TelegramClient(String baseUrl, String token, ObjectMapper json) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Telegram token is required");
        this.endpoint = baseUrl + "/bot" + token + "/";
        this.fileEndpoint = baseUrl + "/file/bot" + token + "/";
        this.json = json;
    }

    public record Bot(long id, String username) {}
    public record Update(long id, long chatId, long senderId, long messageId, long date,
                         String name, String username, String text, boolean privateChat) {}

    public Bot bot() {
        JsonNode result = call("getMe", Map.of());
        return new Bot(result.path("id").asLong(), result.path("username").asText());
    }

    public boolean hasWebhook() {
        return !call("getWebhookInfo", Map.of()).path("url").asText().isBlank();
    }

    public List<Update> updates(long offset) {
        JsonNode result = call("getUpdates", Map.of("offset", offset, "timeout", 10,
                "limit", 50, "allowed_updates", List.of("message")));
        List<Update> updates = new ArrayList<>();
        for (JsonNode update : result) {
            JsonNode message = update.path("message");
            JsonNode from = message.path("from");
            String name = (from.path("first_name").asText() + " " + from.path("last_name").asText()).trim();
            String text = message.hasNonNull("text") ? message.path("text").asText()
                    : message.hasNonNull("caption") ? "[Attachment] " + message.path("caption").asText()
                    : message.has("message_id") ? "[Non-text message — attachments are not supported yet]" : null;
            updates.add(new Update(update.path("update_id").asLong(), message.path("chat").path("id").asLong(),
                    from.path("id").asLong(), message.path("message_id").asLong(), message.path("date").asLong(),
                    name, from.path("username").asText(), text,
                    "private".equals(message.path("chat").path("type").asText()) && !from.path("is_bot").asBoolean()));
        }
        return updates;
    }

    public long send(long chatId, String text) {
        return call("sendMessage", Map.of("chat_id", chatId, "text", text)).path("message_id").asLong();
    }

    /** Smallest available profile image, bounded to 1 MiB, or null when none is available. */
    public byte[] profilePhoto(long userId) {
        JsonNode photos=call("getUserProfilePhotos",Map.of("user_id",userId,"limit",1)).path("photos");
        if(photos.isEmpty() || photos.path(0).isEmpty())return null;
        String fileId=photos.path(0).path(0).path("file_id").asText();
        String path=call("getFile",Map.of("file_id",fileId)).path("file_path").asText();
        if(!path.matches("[a-zA-Z0-9_./-]+") || path.contains(".."))throw new ApiFailure(0,0);
        try {
            var request=HttpRequest.newBuilder(URI.create(fileEndpoint+path)).timeout(Duration.ofSeconds(15)).GET().build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(var stream=response.body()) {
                if(response.statusCode()!=200)throw new ApiFailure(response.statusCode(),0);
                byte[] bytes=stream.readNBytes(1024*1024+1);
                if(bytes.length>1024*1024 || bytes.length<3 || bytes[0]!=(byte)0xff || bytes[1]!=(byte)0xd8 || bytes[2]!=(byte)0xff)throw new ApiFailure(0,0);
                return bytes;
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new ApiFailure(0,0);}
        catch(Exception e){throw new ApiFailure(0,0);}
    }

    private JsonNode call(String method, Map<String, Object> body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + method))
                    .timeout(Duration.ofSeconds(25)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode envelope = json.readTree(response.body());
            if (response.statusCode() != 200 || !envelope.path("ok").asBoolean()) {
                throw new ApiFailure(envelope.path("error_code").asInt(response.statusCode()),
                        envelope.path("parameters").path("retry_after").asInt(0));
            }
            return envelope.path("result");
        } catch (ApiFailure ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiFailure(0, 0);
        } catch (Exception ex) {
            // JDK HTTP exception messages can contain the URL, which includes the bot token.
            throw new ApiFailure(0, 0);
        }
    }

    public static final class ApiFailure extends RuntimeException {
        private final int retryAfter;
        ApiFailure(int code, int retryAfter) {
            super(code == 0 ? "Telegram request outcome is unknown; check the chat before retrying"
                    : "Telegram rejected the request (code " + code + ")");
            this.retryAfter = Math.max(0, retryAfter);
        }
        public int retryAfter() { return retryAfter; }
    }
}
