package su.onno.crm.channels.instagram;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Instagram Login client; credentials stay in an owner-readable file, never in CRM records. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(InstagramClient.class)
@Component
@ConditionalOnProperty(name="onno.crm.channels.instagram.enabled",havingValue="true")
public class InstagramClient {
    /** Conversations requested per page of the conversation list. */
    static final int CONVERSATION_PAGE_SIZE=25;
    /**
     * Messages requested per conversation. Instagram exposes details only for the 20 most recent
     * messages of a conversation; asking for older ones fails, so the adapter never pages further.
     */
    static final int MESSAGE_PAGE_SIZE=20;
    /** Graph error codes that mean "throttled": app, user, page, hourly and Instagram/Messenger business-use-case limits. */
    static final Set<Integer> RATE_LIMIT_CODES=Set.of(4,17,32,613,80002,80006);
    private static final Pattern REGAIN=Pattern.compile("\"estimated_time_to_regain_access\"\\s*:\\s*(\\d+)");

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
    /** One page of the account's conversations; the provider lists the most recently updated first. */
    public Page conversations(String account,String after){return page(get(encode(account)+"/conversations?platform=instagram&fields=id,updated_time&limit="+CONVERSATION_PAGE_SIZE+cursor(after)));}
    /** The most recent messages of one conversation (newest first) — the only ones Instagram details. */
    public Page messages(String conversation){return page(get(encode(conversation)+"/messages?fields=id,created_time,from,to,message&limit="+MESSAGE_PAGE_SIZE));}
    public String send(String account,String recipient,String body){
        var request=json.createObjectNode();request.putObject("recipient").put("id",recipient);request.putObject("message").put("text",body);
        return required(call(encode(account)+"/messages",request),"message_id");
    }
    private JsonNode get(String path){return call(path,null);}
    private JsonNode call(String path,JsonNode body){
        try {
            var token=json.readTree(credentials.toFile()).path("access_token").asText();if(token.isBlank())throw new IllegalStateException();
            // The token travels only in the Authorization header, never in the URL, so no request URL carries it.
            var request=HttpRequest.newBuilder(URI.create("https://graph.instagram.com/v25.0/"+path)).timeout(Duration.ofSeconds(30)).header("Authorization","Bearer "+token);
            if(body!=null)request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()<200||response.statusCode()>=300)throw apiFailure(response.statusCode(),response.headers().firstValue("Retry-After").orElse(null),
                    response.headers().firstValue("X-Business-Use-Case-Usage").orElse(null),response.body());
            return json.readTree(response.body());
        }catch(ApiFailure e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Instagram request interrupted");}
        catch(Exception e){throw new IllegalStateException("Instagram request failed; check the private credential file and connection");}
    }
    /**
     * A failure that keeps only numeric diagnostics: status, Graph error code/subcode and the wait the
     * provider asked for. The response body (which can echo message text) is never retained.
     */
    ApiFailure apiFailure(int status,String retryAfter,String usage,String body){
        int code=0,subcode=0;try{var error=json.readTree(body).path("error");code=error.path("code").asInt();subcode=error.path("error_subcode").asInt();}catch(Exception ignored){}
        long wait=0;
        try{if(retryAfter!=null)wait=Math.max(0,Long.parseLong(retryAfter.trim()));}catch(NumberFormatException ignored){}
        if(usage!=null){var m=REGAIN.matcher(usage);while(m.find())try{wait=Math.max(wait,Long.parseLong(m.group(1))*60);}catch(NumberFormatException ignored){}}
        return new ApiFailure(status,code,subcode,wait);
    }
    static Page page(JsonNode p){return new Page(p.path("data"),p.path("paging").hasNonNull("next")?p.path("paging").path("cursors").path("after").asText():"");}
    private static String cursor(String after){return after==null||after.isBlank()?"":"&after="+encode(after);}
    static String encode(String value){return URLEncoder.encode(value,java.nio.charset.StandardCharsets.UTF_8);}
    static String required(JsonNode value,String field){String s=value.path(field).asText();if(s.isBlank())throw new IllegalArgumentException("Instagram response is missing "+field);return s;}
    public static final class ApiFailure extends RuntimeException {
        final int status; final int code; final int subcode;
        /** Seconds the provider asked to wait (Retry-After or business-use-case usage), 0 when it did not say. */
        final long retrySeconds;
        ApiFailure(int status,int code,int subcode,long retrySeconds){
            super("Instagram returned HTTP "+status+" (code "+code+", subcode "+subcode+")");
            this.status=status;this.code=code;this.subcode=subcode;this.retrySeconds=Math.max(0,retrySeconds);
        }
        /** HTTP 429 or a Graph throttling error code. */
        boolean rateLimited(){return status==429||RATE_LIMIT_CODES.contains(code);}
        /** The token or its permissions were rejected. */
        boolean accessDenied(){return !rateLimited()&&(status==401||status==400||code==190);}
    }
}
