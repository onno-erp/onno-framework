package su.onno.crm.channels.instagram;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Instagram Login client; credentials stay in an owner-readable file, never in CRM records. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(InstagramClient.class)
@Component
@ConditionalOnProperty(name="onno.crm.channels.instagram.enabled",havingValue="true")
public class InstagramClient {
    private final Path credentials;
    private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    public InstagramClient(@Value("${onno.crm.channels.instagram.tokens-file:${user.home}/.config/onno/instagram-tokens.json}") String file,ObjectMapper json) {
        this.credentials=Path.of(file);this.json=json;
    }
    public boolean configured(){return Files.isRegularFile(credentials);}
    public record Profile(String id,String userId,String username) {}
    public record Page(JsonNode data,String after) {}
    public Profile profile(){var p=get("me?fields=id,user_id,username");return new Profile(required(p,"id"),required(p,"user_id"),required(p,"username"));}
    public Page conversations(String account,String after){return page(get(encode(account)+"/conversations?platform=instagram&fields=id,updated_time&limit=50"+cursor(after)));}
    public Page messages(String conversation,String after){return page(get(encode(conversation)+"/messages?fields=id,created_time,from,to,message&limit=100"+cursor(after)));}
    public String send(String account,String recipient,String body){
        var request=json.createObjectNode();request.putObject("recipient").put("id",recipient);request.putObject("message").put("text",body);
        return required(call(encode(account)+"/messages",request),"message_id");
    }
    private JsonNode get(String path){return call(path,null);}
    private JsonNode call(String path,JsonNode body){
        try {
            var token=json.readTree(credentials.toFile()).path("access_token").asText();if(token.isBlank())throw new IllegalStateException();
            var request=HttpRequest.newBuilder(URI.create("https://graph.instagram.com/v25.0/"+path)).timeout(Duration.ofSeconds(30)).header("Authorization","Bearer "+token);
            if(body!=null)request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()<200||response.statusCode()>=300)throw new ApiFailure(response.statusCode(),response.headers().firstValue("Retry-After").orElse("60"));
            return json.readTree(response.body());
        }catch(ApiFailure e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Instagram request interrupted");}
        catch(Exception e){throw new IllegalStateException("Instagram request failed; check the private credential file and connection");}
    }
    static Page page(JsonNode p){return new Page(p.path("data"),p.path("paging").hasNonNull("next")?p.path("paging").path("cursors").path("after").asText():"");}
    private static String cursor(String after){return after==null||after.isBlank()?"":"&after="+encode(after);}
    static String encode(String value){return URLEncoder.encode(value,java.nio.charset.StandardCharsets.UTF_8);}
    static String required(JsonNode value,String field){String s=value.path(field).asText();if(s.isBlank())throw new IllegalArgumentException("Instagram response is missing "+field);return s;}
    public static final class ApiFailure extends RuntimeException {
        final int status; final long retrySeconds;
        ApiFailure(int status,String retry){super("Instagram returned HTTP "+status);this.status=status;long seconds=60;try{seconds=Math.max(1,Long.parseLong(retry));}catch(NumberFormatException ignored){}this.retrySeconds=seconds;}
    }
}
