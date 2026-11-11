package su.onno.crm.channels.gmail;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Local single-account Gmail client. Credentials are never returned to the browser or logs.
 *
 * <p>Gmail answers {@code format=raw} with the whole email as one base64 string, and Jackson's
 * default refuses strings over 20 million characters (an email of about 15 MB). Responses are
 * therefore read with a private copy of the application's mapper whose limit fits Gmail's largest
 * email; the application's own mapper is left as it is.
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(GmailClient.class)
@Component
@ConditionalOnProperty(name="onno.crm.channels.gmail.enabled", havingValue="true")
public class GmailClient {
    private final ObjectMapper json;
    /** {@link #json} with a string limit that fits a whole email; used only to read Gmail's answers. */
    private final ObjectMapper reader;
    private final Path clientFile, tokensFile;
    private final String redirectUri;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    static final String READ_SCOPE = "https://www.googleapis.com/auth/gmail.readonly";
    static final String SEND_SCOPE = "https://www.googleapis.com/auth/gmail.send";
    /** Lets the CRM mark mail read and move a thread back to the Inbox. Optional: older grants lack it. */
    static final String MODIFY_SCOPE = "https://www.googleapis.com/auth/gmail.modify";
    /** Scopes a new connection asks for. */
    static final String SCOPES = READ_SCOPE + " " + SEND_SCOPE + " " + MODIFY_SCOPE;
    /** Scopes a grant must include to connect; a grant without {@link #MODIFY_SCOPE} still works. */
    static final String REQUIRED_SCOPES = READ_SCOPE + " " + SEND_SCOPE;
    /**
     * Gmail accepts emails of up to 50 MB; in base64 that is about 67 million characters, the
     * longest string a Gmail answer can hold.
     */
    static final int MAX_STRING_LENGTH = 100_000_000;
    public GmailClient(ObjectMapper json,
            @Value("${onno.crm.channels.gmail.client-file:${user.home}/.config/onno/gmail-client.json}") String clientFile,
            @Value("${onno.crm.channels.gmail.tokens-file:${user.home}/.config/onno/gmail-tokens.json}") String tokensFile,
            @Value("${onno.crm.channels.gmail.redirect-uri:http://127.0.0.1:8090/api/crm/gmail/callback}") String redirectUri) {
        this.json=json;this.reader=wholeEmails(json);this.clientFile=Path.of(clientFile);this.tokensFile=Path.of(tokensFile);this.redirectUri=redirectUri;
    }
    /** A copy of {@code json} that reads a whole email as one string; {@code json} is left as it was. */
    static ObjectMapper wholeEmails(ObjectMapper json) {
        ObjectMapper copy;
        try { copy=json.copy(); }
        catch(IllegalStateException subclassWithoutCopy) { copy=new ObjectMapper(); } // only JSON trees are read with it
        var limits=copy.getFactory().streamReadConstraints();
        if(limits.getMaxStringLength()<MAX_STRING_LENGTH)
            copy.getFactory().setStreamReadConstraints(limits.rebuild().maxStringLength(MAX_STRING_LENGTH).build());
        return copy;
    }
    private final java.util.concurrent.ConcurrentMap<java.util.UUID,GmailClient> accountClients=new java.util.concurrent.ConcurrentHashMap<>();
    public GmailClient forAccount(java.util.UUID id) {
        return accountClients.computeIfAbsent(id,key->new GmailClient(json,clientFile.toString(),tokensFile.toAbsolutePath().getParent().resolve("gmail-"+key+".json").toString(),redirectUri));
    }
    public synchronized void disconnect() {
        try { Files.deleteIfExists(tokensFile); } catch(java.io.IOException error) { throw new IllegalStateException("Could not remove account credentials"); }
    }
    public boolean configured() { return Files.isRegularFile(clientFile); }
    public boolean authorized() { return Files.isRegularFile(tokensFile); }
    private JsonNode credentials() {
        try { var node=json.readTree(clientFile.toFile()).path("web");
            if(node.path("client_id").asText().isBlank() || node.path("client_secret").asText().isBlank()) throw new IllegalArgumentException();
            return node;
        } catch(Exception e) { throw new IllegalArgumentException("Gmail OAuth client file is missing or invalid"); }
    }
    public String authorizationUrl(String state, String challenge) {
        return "https://accounts.google.com/o/oauth2/v2/auth?"+form(Map.of(
            "client_id",credentials().path("client_id").asText(),"redirect_uri",redirectUri,
            "response_type","code","scope",SCOPES,"access_type","offline","prompt","consent",
            "state",state,"code_challenge",challenge,"code_challenge_method","S256"));
    }
    public synchronized JsonNode exchange(String code,String verifier) {
        var c=credentials();
        ObjectNode token=(ObjectNode)postForm("https://oauth2.googleapis.com/token",Map.of("code",code,
            "client_id",c.path("client_id").asText(),"client_secret",c.path("client_secret").asText(),
            "redirect_uri",redirectUri,"grant_type","authorization_code","code_verifier",verifier));
        if(token.path("refresh_token").asText().isBlank()) throw new IllegalArgumentException("Google did not grant offline access; reconnect with consent");
        if(!granted(token.path("scope").asText(),REQUIRED_SCOPES))
            throw new IllegalArgumentException("Allow Gmail read and send access to connect the inbox");
        return token;
    }
    /** Whether a space-separated OAuth scope list includes every scope in {@code wanted}. */
    static boolean granted(String scopes,String wanted) {
        return new HashSet<>(Arrays.asList(Objects.toString(scopes,"").split(" "))).containsAll(Arrays.asList(wanted.split(" ")));
    }
    /**
     * Whether this mailbox's grant lets the CRM change labels (mark read, move to Inbox). Mailboxes
     * connected before {@code gmail.modify} was requested lack it until they reconnect. Derived from
     * the scope Google reported with the saved token, so nothing else needs storing.
     */
    public boolean canModify() {
        if(!authorized())return false;
        try {
            String scopes=json.readTree(tokensFile.toFile()).path("scope").asText();
            return granted(scopes,MODIFY_SCOPE) || granted(scopes,"https://mail.google.com/");
        } catch(Exception e) { return false; }
    }
    public JsonNode profile(JsonNode tokens) { return request("GET","https://gmail.googleapis.com/gmail/v1/users/me/profile",tokens.path("access_token").asText(),null); }
    public synchronized void saveTokens(JsonNode tokens) {
        ObjectNode node=tokens.deepCopy();node.put("expires_at",Instant.now().getEpochSecond()+node.path("expires_in").asLong(3600)-60);
        try {
            Files.createDirectories(tokensFile.toAbsolutePath().getParent());
            Path temp=Files.createTempFile(tokensFile.toAbsolutePath().getParent(),"gmail-",".json",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try { json.writeValue(temp.toFile(),node);Files.move(temp,tokensFile,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            finally { Files.deleteIfExists(temp); }
        } catch(Exception e) { throw new IllegalStateException("Could not save Gmail credentials securely"); }
    }
    private synchronized String accessToken() {
        try {
            ObjectNode token=(ObjectNode)json.readTree(tokensFile.toFile());
            if(token.path("expires_at").asLong()<Instant.now().getEpochSecond()) {
                var c=credentials();ObjectNode refreshed=(ObjectNode)postForm("https://oauth2.googleapis.com/token",Map.of(
                    "client_id",c.path("client_id").asText(),"client_secret",c.path("client_secret").asText(),
                    "refresh_token",token.path("refresh_token").asText(),"grant_type","refresh_token"));
                refreshed.put("refresh_token",token.path("refresh_token").asText());
                // Keep the granted scopes, which canModify reads, if a refresh answer leaves them out.
                if(refreshed.path("scope").asText().isBlank() && !token.path("scope").asText().isBlank())refreshed.put("scope",token.path("scope").asText());
                saveTokens(refreshed);token=refreshed;
            }
            return token.path("access_token").asText();
        } catch(ApiFailure e) {throw e;} catch(Exception e) {throw new IllegalArgumentException("Reconnect Gmail to restore access");}
    }
    public JsonNode get(String path) {return request("GET","https://gmail.googleapis.com/gmail/v1/users/me/"+path,accessToken(),null);}
    /** A JSON {@code POST} to a Gmail API path under {@code users/me/}, such as {@code messages/batchModify}. */
    public JsonNode post(String path,JsonNode body) {return request("POST","https://gmail.googleapis.com/gmail/v1/users/me/"+path,accessToken(),body);}
    public JsonNode send(String raw,String threadId) {
        var payload=json.createObjectNode().put("raw",raw);
        if(threadId!=null&&!threadId.isBlank())payload.put("threadId",threadId);
        return request("POST","https://gmail.googleapis.com/gmail/v1/users/me/messages/send",accessToken(),payload);
    }
    private JsonNode postForm(String url,Map<String,String> fields) {
        return execute(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("Content-Type","application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form(fields))).build());
    }
    private JsonNode request(String method,String url,String token,JsonNode body) {
        // A whole email (format=raw) can be tens of megabytes; give it time to arrive.
        var timeout=url.contains("format=raw")?Duration.ofMinutes(3):Duration.ofSeconds(30);
        var request=HttpRequest.newBuilder(URI.create(url)).timeout(timeout).header("Authorization","Bearer "+token);
        if(body==null)request.GET();else request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        return execute(request.build());
    }
    private JsonNode execute(HttpRequest request) {
        HttpResponse<String> response;
        try { response=http.send(request,HttpResponse.BodyHandlers.ofString()); }
        catch(InterruptedException e) {Thread.currentThread().interrupt();throw new ApiFailure(0,null,null,e.getClass().getSimpleName());}
        catch(Exception e) {throw new ApiFailure(0,null,null,e.getClass().getSimpleName());}
        return read(response.statusCode(),response.body(),response.headers().firstValue("Retry-After").orElse(null),Instant.now());
    }
    /**
     * Gmail's answer as JSON, or an {@link ApiFailure} carrying the HTTP status, Google's error
     * reason and how long to wait. An answer that cannot be parsed fails with its status kept and
     * the parser's exception class as the detail; the body itself never reaches the failure.
     */
    JsonNode read(int status,String body,String retryAfterHeader,Instant now) {
        if(status/100!=2) {
            String reason=null,message=null;
            try { var error=reader.readTree(body==null?"":body).path("error");
                reason=error.path("errors").path(0).path("reason").asText(null);
                if(reason==null)reason=error.path("status").asText(null);
                message=error.path("message").asText(null);
            } catch(Exception ignored) {}
            throw new ApiFailure(status,reason,retryAfter(retryAfterHeader,message,now),null);
        }
        if(body==null || body.isBlank())return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        try { return reader.readTree(body); }
        catch(Exception e) { throw new ApiFailure(status,null,null,e.getClass().getSimpleName()); }
    }
    private static final java.util.regex.Pattern RETRY_AFTER_TEXT=java.util.regex.Pattern.compile("(?i)retry after (\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z)");
    /**
     * How long Gmail asked us to wait: the {@code Retry-After} header (seconds or an HTTP date), or
     * the "Retry after 2026-…Z" time Gmail writes into a rate-limit message. Null when it said nothing.
     */
    static Duration retryAfter(String header,String message,Instant now) {
        Instant until=null;
        if(header!=null && !header.isBlank()) {
            String value=header.strip();
            try { return clamp(Duration.ofSeconds(Long.parseLong(value))); }
            catch(NumberFormatException notSeconds) {
                try { until=java.time.ZonedDateTime.parse(value,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(); }
                catch(java.time.format.DateTimeParseException ignored) {}
            }
        }
        if(until==null && message!=null) {
            var matcher=RETRY_AFTER_TEXT.matcher(message);
            if(matcher.find()) try { until=Instant.parse(matcher.group(1)); } catch(java.time.format.DateTimeParseException ignored) {}
        }
        return until==null?null:clamp(Duration.between(now,until));
    }
    private static Duration clamp(Duration wait) {
        if(wait.compareTo(Duration.ofSeconds(1))<0)return Duration.ofSeconds(1);
        return wait.compareTo(Duration.ofDays(1))>0?Duration.ofDays(1):wait;
    }
    static String form(Map<String,String> fields) {return fields.entrySet().stream().map(e->encode(e.getKey())+"="+encode(e.getValue())).collect(java.util.stream.Collectors.joining("&"));}
    static String encode(String value) {return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    /**
     * A Gmail request that failed. {@code status} is the HTTP status (0 when no answer arrived),
     * {@code reason} Google's error reason (such as {@code rateLimitExceeded}) when it gave one,
     * {@code retryAfter} how long Gmail asked us to wait, and {@code detail} the exception class
     * behind a failure without a usable answer. None of them carries credentials or mail content.
     */
    public static final class ApiFailure extends RuntimeException {
        private static final Set<String> RATE_REASONS=Set.of("rateLimitExceeded","userRateLimitExceeded","dailyLimitExceeded","quotaExceeded","RESOURCE_EXHAUSTED");
        public final int status;
        public final String reason;
        public final Duration retryAfter;
        public final String detail;
        ApiFailure(int status){this(status,null,null,null);}
        ApiFailure(int status,String reason,Duration retryAfter,String detail){
            super("Gmail request failed ("+describe(status,reason,detail)+"). Check access and reconnect if needed.");
            this.status=status;this.reason=reason;this.retryAfter=retryAfter;this.detail=detail;
        }
        /** Gmail is asking us to slow down: HTTP 429, or 403 with a rate or quota reason. */
        public boolean rateLimited() { return status==429 || status==403 && reason!=null && RATE_REASONS.contains(reason); }
        /** Status, reason and detail for logs, such as {@code 429 rateLimitExceeded} or {@code 200 StreamConstraintsException}. */
        public String describe() { return describe(status,reason,detail); }
        private static String describe(int status,String reason,String detail) {
            StringBuilder text=new StringBuilder().append(status);
            if(reason!=null && !reason.isBlank())text.append(' ').append(reason);
            if(detail!=null && !detail.isBlank())text.append(' ').append(detail);
            return text.toString();
        }
    }
}
