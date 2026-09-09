package su.onno.crm.channels.whatsapp;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Only this route needs anonymous access and CSRF exemption; every POST verifies Meta's signature. */
@RestController
@RequestMapping("/api/crm/whatsapp/webhook")
@ConditionalOnProperty(name="onno.crm.channels.whatsapp.enabled",havingValue="true")
public class WhatsAppWebhookController {
 private final WhatsAppClient client;private final WhatsAppBridge bridge;private final ObjectMapper json;
 public WhatsAppWebhookController(WhatsAppClient client,WhatsAppBridge bridge,ObjectMapper json){this.client=client;this.bridge=bridge;this.json=json;}
 @GetMapping(produces=MediaType.TEXT_PLAIN_VALUE) public String challenge(@RequestParam("hub.mode") String mode,@RequestParam("hub.verify_token") String token,@RequestParam("hub.challenge") String challenge){
  if(!mode.equals("subscribe")||!client.verifyToken(token))throw new ResponseStatusException(HttpStatus.FORBIDDEN);return challenge;
 }
 @PostMapping public ResponseEntity<Void> receive(HttpServletRequest request) throws java.io.IOException {
  byte[] body=request.getInputStream().readNBytes(1_048_577);if(body.length>1_048_576)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
  if(!client.verifySignature(body,request.getHeader("X-Hub-Signature-256")))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  try{bridge.receive(json.readTree(body));}catch(com.fasterxml.jackson.core.JsonProcessingException|IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid WhatsApp event");}
  return ResponseEntity.ok().build();
 }
}
