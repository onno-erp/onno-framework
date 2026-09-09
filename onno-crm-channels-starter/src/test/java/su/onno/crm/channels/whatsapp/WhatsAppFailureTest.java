package su.onno.crm.channels.whatsapp;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class WhatsAppFailureTest {
 @Test void aliasesOnlyExplicitlyConfiguredTestRecipients() throws Exception {
  var credentials=new ObjectMapper().readTree("{\"test_recipient_aliases\":{\"15551234567\":\"15557654321\"}}");
  assertThat(WhatsAppClient.resolveRecipient(credentials,"15551234567")).isEqualTo("15557654321");
  assertThat(WhatsAppClient.resolveRecipient(credentials,"15550000000")).isEqualTo("15550000000");
 }
 @Test void retainsOnlyNumericProviderDiagnostics(){
  var client=new WhatsAppClient("unused",new ObjectMapper());
  var error=client.apiFailure(400,"2","{\"error\":{\"code\":131030,\"error_subcode\":7,\"message\":\"sensitive token and message\"}}");
  assertThat(error.code).isEqualTo(131030);assertThat(error.subcode).isEqualTo(7);
  assertThat(error.getMessage()).doesNotContain("sensitive");
  assertThat(client.apiFailure(502,"invalid","not json").code).isZero();
 }
}
