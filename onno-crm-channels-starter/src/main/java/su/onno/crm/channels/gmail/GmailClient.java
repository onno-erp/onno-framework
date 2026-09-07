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
        this.json=json;this.clientFile=Path.of(clientFile);this.tokensFile=Path.of(tokensFile);this.redirectUri=redirectUri;
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
        return request("POST","https://gmail.googleapis.com/gmail/v1/users/me/messages/send",accessToken(),json.createObjectNode().put("raw",raw).put("threadId",threadId));
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
        try {
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()/100!=2)throw new ApiFailure(response.statusCode());
            return json.readTree(response.body());
        } catch(ApiFailure e) {throw e;} catch(InterruptedException e) {Thread.currentThread().interrupt();throw new ApiFailure(0);}
        catch(Exception e){throw new ApiFailure(0);}
    }
    static String form(Map<String,String> fields) {return fields.entrySet().stream().map(e->encode(e.getKey())+"="+encode(e.getValue())).collect(java.util.stream.Collectors.joining("&"));}
    static String encode(String value) {return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    public static final class ApiFailure extends RuntimeException {
        public final int status;
        ApiFailure(int status){super("Gmail request failed ("+status+"). Check access and reconnect if needed.");this.status=status;}
    }
}
