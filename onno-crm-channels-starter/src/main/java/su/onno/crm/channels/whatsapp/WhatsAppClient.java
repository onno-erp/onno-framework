package su.onno.crm.channels.whatsapp;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** WhatsApp Cloud API and raw-body webhook authentication. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(WhatsAppClient.class)
@Component
@ConditionalOnProperty(name="onno.crm.channels.whatsapp.enabled",havingValue="true")
public class WhatsAppClient {
 private final Path file;private final ObjectMapper json;
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
 public WhatsAppClient(@Value("${onno.crm.channels.whatsapp.tokens-file:${user.home}/.config/onno/whatsapp-tokens.json}") String file,ObjectMapper json){this.file=Path.of(file);this.json=json;}
 private JsonNode credentials(){try{return json.readTree(file.toFile());}catch(Exception e){throw new IllegalArgumentException("Check the private WhatsApp credential file");}}
 public boolean configured(){return Files.isRegularFile(file);}
 public String businessAccount(){return required(credentials(),"business_account_id");}
 public record Profile(String id,String userId,String username){}
 public Profile profile(){var c=credentials();String id=required(c,"phone_number_id");var p=call(id+"?fields=id,display_phone_number,verified_name",null);return new Profile(required(p,"id"),id,required(p,"display_phone_number"));}
 public String send(String account,String recipient,String text){var body=json.createObjectNode().put("messaging_product","whatsapp").put("recipient_type","individual").put("to",resolveRecipient(credentials(),recipient)).put("type","text");body.putObject("text").put("body",text).put("preview_url",false);return required(call(account+"/messages",body).path("messages").path(0),"id");}
 static String resolveRecipient(JsonNode credentials,String recipient){
  String alias=credentials.path("test_recipient_aliases").path(recipient).asText("");
  if(alias.isBlank())return recipient;
  if(!alias.matches("[1-9][0-9]{6,14}"))throw new IllegalArgumentException("Invalid WhatsApp test recipient alias");
  return alias;
 }
 public boolean verifyToken(String token){String expected=credentials().path("verify_token").asText();return !expected.isBlank()&&token!=null&&MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8));}
 public boolean verifySignature(byte[] body,String signature){
  if(signature==null||!signature.matches("sha256=[0-9a-fA-F]{64}"))return false;
  try{String secret=required(credentials(),"app_secret");Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return MessageDigest.isEqual(mac.doFinal(body),HexFormat.of().parseHex(signature.substring(7)));}catch(Exception e){return false;}
 }
 private JsonNode call(String path,JsonNode body){try{
  String token=required(credentials(),"access_token");var request=HttpRequest.newBuilder(URI.create("https://graph.facebook.com/v25.0/"+path)).timeout(Duration.ofSeconds(30)).header("Authorization","Bearer "+token);
  if(body!=null)request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
  var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());if(response.statusCode()<200||response.statusCode()>=300)throw apiFailure(response.statusCode(),response.headers().firstValue("Retry-After").orElse("60"),response.body());return json.readTree(response.body());
 }catch(ApiFailure e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("WhatsApp request interrupted");}catch(Exception e){throw new IllegalArgumentException("WhatsApp request failed; check credentials and connection");}}
 static String required(JsonNode node,String field){String s=node.path(field).asText();if(s.isBlank())throw new IllegalArgumentException("WhatsApp response is missing "+field);return s;}
 ApiFailure apiFailure(int status,String retry,String body){
  int code=0,subcode=0;try{var error=json.readTree(body).path("error");code=error.path("code").asInt();subcode=error.path("error_subcode").asInt();}catch(Exception ignored){}
  return new ApiFailure(status,retry,code,subcode);
 }
 public static final class ApiFailure extends RuntimeException {final int status;final int code;final int subcode;final long retrySeconds;ApiFailure(int status,String retry){this(status,retry,0,0);}ApiFailure(int status,String retry,int code,int subcode){super("WhatsApp returned HTTP "+status+" (code "+code+", subcode "+subcode+")");this.status=status;this.code=code;this.subcode=subcode;long delay=60;try{delay=Math.max(1,Long.parseLong(retry));}catch(NumberFormatException ignored){}retrySeconds=delay;}}
}
