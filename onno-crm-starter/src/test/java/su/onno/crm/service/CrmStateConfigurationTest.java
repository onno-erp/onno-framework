package su.onno.crm.service;
import java.util.*;
import org.junit.jupiter.api.Test;
import su.onno.crm.service.CrmStateConfiguration.*;
import static org.assertj.core.api.Assertions.*;
class CrmStateConfigurationTest {
    @Test void transitionsUseApplicationIdsRatherThanNamesOrBuiltInChoices(){
        var incoming=new Status(new Choice(UUID.randomUUID(),"Needs an agent","#123456"),false);
        var reply=new Status(new Choice(UUID.randomUUID(),"Partner reviewing","#AA7700"),false);
        var closed=new Status(new Choice(UUID.randomUUID(),"Finished","#334455"),true);
        var t=new Transitions(incoming.choice().id(),reply.choice().id(),closed.choice().id(),incoming.choice().id());
        var config=new CrmStateConfiguration(List.of(new Choice(UUID.randomUUID(),"Contract review","#ABCDEF")),List.of(incoming,reply,closed),t);
        var service=new CrmConversationStatuses(config);
        assertThat(service.reply().id()).isEqualTo(reply.choice().id());
        assertThat(service.close().id()).isEqualTo(closed.choice().id());
        assertThat(service.incoming().id()).isEqualTo(incoming.choice().id());
        assertThatThrownBy(()->service.active(UUID.randomUUID())).hasMessageContaining("configured");
        assertThatThrownBy(()->new CrmStateConfiguration(List.of(),List.of(incoming,closed),t)).hasMessageContaining("Transition");
        assertThatThrownBy(()->new CrmStateConfiguration(List.of(),List.of(incoming,incoming),t)).hasMessageContaining("Duplicate");
    }
    @Test void genericStateCatalogWritesAreDeniedEvenForAdmins(){
        var policy=new su.onno.crm.OnnoCrmAutoConfiguration().crmCodeOwnedStateAccess();
        for(String name:List.of("crmcustomerstages","crmconversationstatuses")) {
            assertThat(policy.allows(Set.of("ADMIN"),"catalog",name,true)).isFalse();
            assertThat(policy.allows(Set.of("CRM_AGENT"),"catalog",name,false)).isTrue();
        }
        assertThat(policy.allows(Set.of("CRM_MANAGER"),"catalog","crmcustomers",true)).isTrue();
    }
}
