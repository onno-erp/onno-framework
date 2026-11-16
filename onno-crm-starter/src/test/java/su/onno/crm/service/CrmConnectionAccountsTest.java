package su.onno.crm.service;

import java.security.Principal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import su.onno.crm.domain.*;
import su.onno.crm.repository.ConversationRepository;
import su.onno.web.AccessService;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CrmConnectionAccountsTest {
    final Principal alice=()->"alice",bob=()->"bob",admin=()->"admin";
    CrmConnectionAccounts accounts;
    @BeforeEach void setup() {
        var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""));
        accounts=new CrmConnectionAccounts(jdbc,p->p!=null && p.getName().equals("admin"),su.onno.crm.service.CrmFeatures.defaults().withPersonalAccounts(true));accounts.initialize();
    }
    @Test void ownershipIsServerAssignedAndPersonalAccountsAreInvisibleToOtherUsersIncludingAdmins() {
        var own=accounts.create("gmail",su.onno.crm.service.CrmConnectionAccounts.Scope.PERSONAL,"My work",alice);
        var shared=accounts.create("gmail",su.onno.crm.service.CrmConnectionAccounts.Scope.SHARED,"Team",admin);
        assertThat(own.owner()).isEqualTo("alice");
        assertThat(accounts.visible(alice)).containsExactlyInAnyOrder(own,shared);
        assertThat(accounts.visible(bob)).containsExactly(shared);
        assertThat(accounts.visible(admin)).containsExactly(shared);
        for(var other:List.of(bob,admin))assertThatThrownBy(()->accounts.require(own.id(),other)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->accounts.create("gmail",su.onno.crm.service.CrmConnectionAccounts.Scope.SHARED,"Not allowed",alice)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->accounts.rename(shared.id(),"Not allowed",alice)).isInstanceOf(ResponseStatusException.class);
        assertThat(accounts.rename(own.id(),"Personal work",alice).label()).isEqualTo("Personal work");
    }
    @Test void ownershipSurvivesRecreatingTheServiceAndHiddenInboxesApplyToSingleAndBatchReads() {
        var personal=accounts.create("gmail",su.onno.crm.service.CrmConnectionAccounts.Scope.PERSONAL,"Private",alice);UUID inbox=UUID.randomUUID();accounts.bindInbox(personal.id(),inbox);
        var c=new Conversation();c.setId(UUID.randomUUID());c.setCustomer(UUID.randomUUID());c.setInbox(su.onno.types.Ref.of(Inbox.class,inbox));
        var access=mock(AccessService.class);when(access.roles(any())).thenReturn(Set.of("ADMIN"));
        var contacts=mock(CrmContactService.class);when(contacts.canRead(any(),any())).thenReturn(true);when(contacts.readable(any(),any())).thenReturn(Set.of(c.getCustomer()));
        var repository=mock(ConversationRepository.class);when(repository.findActiveById(c.getId())).thenReturn(Optional.of(c));
        var service=new CrmInboxWorkspaceService(List.of(new CrmInboxWorkspace("all","All",Set.of("ADMIN"),row->true)),repository,access,mock(CrmWorkspaceService.class),contacts,accounts);
        assertThat(service.canAccess(c,alice,false)).isTrue();
        assertThat(service.accessible(List.of(c),alice,false)).containsExactly(c);
        for(var other:List.of(bob,admin)) {
            assertThat(service.canAccess(c,other,false)).isFalse();
            assertThat(service.accessible(List.of(c),other,false)).isEmpty();
            assertThatThrownBy(()->service.requireConversation(c.getId(),other,false)).isInstanceOf(ResponseStatusException.class);
        }
    }
    @Test void genericPolicyClosesCrmCatalogsToAdministratorsOnlyWhenPersonalAccountsAreEnabled() {
        var closed=new su.onno.crm.OnnoCrmAutoConfiguration().crmWorkspaceGenericAccess(CrmFeatures.defaults().withPersonalAccounts(true));
        assertThat(closed.includesAdministrators()).isTrue();
        for(String catalog:List.of("crmconversations","crmconversationmessages","crmcontactidentities","crminboxes"))
            assertThat(closed.allows(Set.of("ADMIN"),"catalog",catalog,false)).isFalse();
        assertThat(closed.allows(Set.of("ADMIN"),"catalog","contacts",false)).isTrue();
        var open=new su.onno.crm.OnnoCrmAutoConfiguration().crmWorkspaceGenericAccess(CrmFeatures.defaults());
        assertThat(open.allows(Set.of("ADMIN"),"catalog","crm_conversations",false)).isTrue();
        assertThat(open.allows(Set.of("CRM_AGENT"),"catalog","crm_conversations",false)).isFalse();
    }
    @Test void personalAccountsCannotBeCreatedWhenTheFeatureIsOff() {
        var off=new CrmConnectionAccounts(new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","")),p->true,CrmFeatures.defaults());
        off.initialize();
        assertThat(off.canManagePersonal(alice)).isFalse();
        assertThatThrownBy(()->off.create("gmail",CrmConnectionAccounts.Scope.PERSONAL,"Mine",alice)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void anInboxCannotBeBoundToTwoAccounts() {
        var first=accounts.create("gmail",CrmConnectionAccounts.Scope.PERSONAL,"First",alice);
        var second=accounts.create("gmail",CrmConnectionAccounts.Scope.PERSONAL,"Second",alice);
        UUID inbox=UUID.randomUUID();
        accounts.bindInbox(first.id(),inbox);
        accounts.bindInbox(first.id(),inbox);
        assertThatThrownBy(()->accounts.bindInbox(second.id(),inbox)).isInstanceOf(IllegalStateException.class);
    }
}
