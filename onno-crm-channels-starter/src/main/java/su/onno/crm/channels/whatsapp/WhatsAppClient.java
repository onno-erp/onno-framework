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
 /** WhatsApp's own caption ceiling; a longer note is sent as its own message before the file. */
 public static final int CAPTION_LIMIT=1024;
 /** Documents cap here on the Cloud API; images cap lower and are sent as documents instead. */
 public static final int UPLOAD_LIMIT=100*1024*1024;
 private static final int IMAGE_LIMIT=5*1024*1024;
 private static final Set<String> IMAGE_TYPES=Set.of("image/jpeg","image/png");

 /**
  * Upload a file to the account's media store and send it to a recipient.
  *
  * <p>WhatsApp takes a file either as a public link or as a media id it holds itself. Uploading
  * gets the id, which is what lets a deployment whose own media sits behind sign-in send files at
  * all — the bytes go to WhatsApp directly rather than WhatsApp being asked to fetch a URL.
  *
  * <p>Only JPEG and PNG within WhatsApp's image ceiling travel as images; everything else — a PDF,
  * a spreadsheet, a large photo — is a document, which is the type that carries a filename.
  */
 public String sendFile(String account,String recipient,String filename,String contentType,byte[] content,String caption){
  if(content==null||content.length==0)throw new IllegalArgumentException("Attachment is empty");
  if(content.length>UPLOAD_LIMIT)throw new IllegalArgumentException("File is larger than WhatsApp accepts (100 MB)");
  String type=contentType==null||contentType.isBlank()?"application/octet-stream":contentType.toLowerCase(Locale.ROOT);
  boolean image=IMAGE_TYPES.contains(type)&&content.length<=IMAGE_LIMIT;
  String mediaId=upload(credentials(),filename,type,content);
  var body=json.createObjectNode().put("messaging_product","whatsapp").put("recipient_type","individual")
    .put("to",resolveRecipient(credentials(),recipient)).put("type",image?"image":"document");
  var media=body.putObject(image?"image":"document").put("id",mediaId);
  if(!image)media.put("filename",safeName(filename));
  if(caption!=null&&!caption.isBlank())media.put("caption",caption);
  return required(call(account+"/messages",body).path("messages").path(0),"id");
 }
 private String upload(JsonNode credentials,String filename,String contentType,byte[] content){
  String boundary="onno"+UUID.randomUUID().toString().replace("-","");
  var body=new java.io.ByteArrayOutputStream();
  try{
   body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"messaging_product\"\r\n\r\nwhatsapp\r\n").getBytes(StandardCharsets.UTF_8));
   body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"type\"\r\n\r\n"+contentType+"\r\n").getBytes(StandardCharsets.UTF_8));
   body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+safeName(filename)
     +"\"\r\nContent-Type: "+contentType+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
   body.write(content);
   body.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
  }catch(java.io.IOException e){throw new IllegalStateException("Attachment could not be prepared");}
  try{
   String token=required(credentials,"access_token");String phone=required(credentials,"phone_number_id");
   var request=HttpRequest.newBuilder(URI.create("https://graph.facebook.com/v25.0/"+phone+"/media"))
     .timeout(Duration.ofSeconds(120)).header("Authorization","Bearer "+token)
     .header("Content-Type","multipart/form-data; boundary="+boundary)
     .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
   var response=http.send(request,HttpResponse.BodyHandlers.ofString());
   if(response.statusCode()<200||response.statusCode()>=300)
    throw apiFailure(response.statusCode(),response.headers().firstValue("Retry-After").orElse("60"),response.body());
   return required(json.readTree(response.body()),"id");
  }catch(ApiFailure e){throw e;}
  catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("WhatsApp upload interrupted");}
  catch(Exception e){throw new IllegalArgumentException("WhatsApp upload failed; check credentials and connection");}
 }
 /** The filename is quoted into a header and shown to the recipient, so it is kept plain. */
 static String safeName(String name){
  String leaf=name==null||name.isBlank()?"attachment":name.replaceAll("[\\r\\n\"\\\\/]","_").strip();
  return leaf.isBlank()?"attachment":leaf.length()<=120?leaf:leaf.substring(0,120);
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
