package su.onno.crm.web;
import java.util.*;
import org.junit.jupiter.api.Test;
import su.onno.crm.service.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class CrmChannelRegistryTest {
 @Test void onlyInstalledChannelsAreExposedIncludingThirdPartyKeys() {
  var workspaces=mock(CrmInboxWorkspaceService.class);
  var connector=new CrmChannelConnection() {
   public String key(){return "acme";}
   public View view(){return new View(key(),"Partner chat","acme.partner-chat","CONNECTED","","",List.of());}
   public void command(String action,String credential){throw new IllegalArgumentException();}
  };
  var controller=new CrmChannelController(List.of(connector),p->true,workspaces);
  assertThat(controller.types(()->"manager")).extracting(CrmChannelDefinition::key).containsExactly("acme.partner-chat");
  assertThat(new CrmChannelController(List.of(),p->true,workspaces).types(()->"manager")).isEmpty();
  var conversation=new su.onno.crm.domain.Conversation();conversation.setChannel("acme.partner-chat");
  assertThat(conversation.getChannel()).isEqualTo(connector.definition().key());
 }
}
