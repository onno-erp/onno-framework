package su.onno.crm.channels.whatsapp;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
class WhatsAppWebhookTest {
 @TempDir Path directory;
 @Test void verifiesRawBodyBeforeDispatchAndProtectsChallenge() throws Exception {
  Path file=directory.resolve("credentials.json");Files.writeString(file,"{\"app_secret\":\"test-secret\",\"verify_token\":\"challenge-secret\"}");
  var json=new ObjectMapper();var client=new WhatsAppClient(file.toString(),json);var bridge=mock(WhatsAppBridge.class);
  var mvc=MockMvcBuilders.standaloneSetup(new WhatsAppWebhookController(client,bridge,json)).build();
  byte[] body="{\"object\":\"whatsapp_business_account\"}".getBytes(StandardCharsets.UTF_8);
  Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));String signature="sha256="+HexFormat.of().formatHex(mac.doFinal(body));
  mvc.perform(post("/api/crm/whatsapp/webhook").content(body)).andExpect(status().isForbidden());
  mvc.perform(post("/api/crm/whatsapp/webhook").header("X-Hub-Signature-256",signature).content("{}")).andExpect(status().isForbidden());verifyNoInteractions(bridge);
  mvc.perform(post("/api/crm/whatsapp/webhook").header("X-Hub-Signature-256",signature).content(body)).andExpect(status().isOk());verify(bridge).receive(json.readTree(body));
  mvc.perform(get("/api/crm/whatsapp/webhook").param("hub.mode","subscribe").param("hub.verify_token","wrong").param("hub.challenge","value")).andExpect(status().isForbidden());
  mvc.perform(get("/api/crm/whatsapp/webhook").param("hub.mode","subscribe").param("hub.verify_token","challenge-secret").param("hub.challenge","value")).andExpect(status().isOk()).andExpect(content().string("value"));
 }
}
