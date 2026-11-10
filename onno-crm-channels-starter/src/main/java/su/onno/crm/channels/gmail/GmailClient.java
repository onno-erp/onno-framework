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

/** Local single-account Gmail client. Credentials are never returned to the browser or logs. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(GmailClient.class)
@Component
@ConditionalOnProperty(name="onno.crm.channels.gmail.enabled", havingValue="true")
public class GmailClient {
    private final ObjectMapper json;
    private final Path clientFile, tokensFile;
    private final String redirectUri;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    static final String SCOPES = "https://www.googleapis.com/auth/gmail.readonly https://www.googleapis.com/auth/gmail.send";
    public GmailClient(ObjectMapper json,
            @Value("${onno.crm.channels.gmail.client-file:${user.home}/.config/onno/gmail-client.json}") String clientFile,
            @Value("${onno.crm.channels.gmail.tokens-file:${user.home}/.config/onno/gmail-tokens.json}") String tokensFile,
            @Value("${onno.crm.channels.gmail.redirect-uri:http://127.0.0.1:8090/api/crm/gmail/callback}") String redirectUri) {
        this.json=wholeEmails(json);this.clientFile=Path.of(clientFile);this.tokensFile=Path.of(tokensFile);this.redirectUri=redirectUri;
    }
    /** Gmail receives emails up to 50 MB and returns one ({@code format=raw}) as a single base64 string: about 67 million characters. */
    static final int MAX_EMAIL_CHARACTERS=100_000_000;
    /**
     * A mapper that reads a whole email. Jackson's default string limit (20 million characters) rejects
     * anything over about 15 MB, so the copy raises it; the shared mapper keeps its own limit.
     */
    static ObjectMapper wholeEmails(ObjectMapper json) {
        if(json.getFactory().streamReadConstraints().getMaxStringLength()>=MAX_EMAIL_CHARACTERS)return json;
        var copy=json.copy();
        copy.getFactory().setStreamReadConstraints(copy.getFactory().streamReadConstraints().rebuild().maxStringLength(MAX_EMAIL_CHARACTERS).build());
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
        String granted=token.path("scope").asText();
        if(!new HashSet<>(Arrays.asList(granted.split(" "))).containsAll(Arrays.asList(SCOPES.split(" "))))
            throw new IllegalArgumentException("Allow Gmail read and send access to connect the inbox");
        return token;
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
                refreshed.put("refresh_token",token.path("refresh_token").asText());saveTokens(refreshed);token=refreshed;
            }
            return token.path("access_token").asText();
        } catch(ApiFailure e) {throw e;} catch(Exception e) {throw new IllegalArgumentException("Reconnect Gmail to restore access");}
    }
    public JsonNode get(String path) {return request("GET","https://gmail.googleapis.com/gmail/v1/users/me/"+path,accessToken(),null);}
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
        var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("Authorization","Bearer "+token);
        if(body==null)request.GET();else request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        return execute(request.build());
    }
    private JsonNode execute(HttpRequest request) {
        HttpResponse<String> response;
        try { response=http.send(request,HttpResponse.BodyHandlers.ofString()); }
        catch(InterruptedException e) {Thread.currentThread().interrupt();throw new ApiFailure(0,"interrupted",null,false);}
        catch(Exception e){throw new ApiFailure(0,e.getClass().getSimpleName(),null,false);}
        if(response.statusCode()/100!=2) {
            String reason=reason(response.body());
            boolean limited=response.statusCode()==429 || response.statusCode()==403 && (reason.equals("rateLimitExceeded") || reason.equals("userRateLimitExceeded"));
            throw new ApiFailure(response.statusCode(),reason,limited?retryAt(response.headers().firstValue("Retry-After").orElse(null),response.body(),Instant.now()):null,false);
        }
        try { return json.readTree(response.body()); }
        catch(Exception e){throw new ApiFailure(response.statusCode(),e.getClass().getSimpleName(),null,true);}
    }
    /** Google's short error reason, such as {@code rateLimitExceeded}; never the response body itself. */
    private String reason(String body) {
        try { var error=json.readTree(body).path("error");
            String reason=error.path("errors").path(0).path("reason").asText(error.path("status").asText());
            return reason.matches("[A-Za-z_]{1,60}")?reason:"";
        } catch(Exception e) { return ""; }
    }
    private static final java.util.regex.Pattern RETRY_AFTER=java.util.regex.Pattern.compile("Retry after (\\d{4}-\\d\\d-\\d\\dT[0-9:.]+Z)");
    /** When Gmail allows the next request: its {@code Retry-After} header, the time in its message, or a minute from now. */
    static Instant retryAt(String header,String body,Instant now) {
        if(header!=null && header.trim().matches("\\d{1,6}"))return now.plusSeconds(Long.parseLong(header.trim()));
        var match=RETRY_AFTER.matcher(body==null?"":body);
        if(match.find())try { var at=Instant.parse(match.group(1));if(at.isAfter(now))return at; } catch(java.time.format.DateTimeParseException ignored) {}
        return now.plusSeconds(60);
    }
    static String form(Map<String,String> fields) {return fields.entrySet().stream().map(e->encode(e.getKey())+"="+encode(e.getValue())).collect(java.util.stream.Collectors.joining("&"));}
    static String encode(String value) {return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    public static final class ApiFailure extends RuntimeException {
        /** The HTTP status, or 0 when no response arrived. */
        public final int status;
        /** Set when Gmail limits this mailbox's requests: no request should be made before it. */
        public final Instant retryAt;
        /** Gmail answered but the response could not be read, so asking again gives the same result. */
        public final boolean unreadable;
        ApiFailure(int status){this(status,"",null,false);}
        ApiFailure(int status,String reason,Instant retryAt,boolean unreadable){
            super(unreadable?"Gmail response could not be read ("+reason+")"
                :"Gmail request failed ("+status+(reason.isBlank()?"":" "+reason)+(retryAt==null?"":", retry after "+retryAt)+"). Check access and reconnect if needed.");
            this.status=status;this.retryAt=retryAt;this.unreadable=unreadable;
        }
    }
}
