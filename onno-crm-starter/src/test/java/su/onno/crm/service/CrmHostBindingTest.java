package su.onno.crm.service;

import java.security.Principal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;
import su.onno.crm.TestBindings;
import su.onno.crm.domain.*;
import su.onno.crm.repository.*;
import su.onno.ui.UiAccessService;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CrmHostBindingTest {
    @Catalog(name="HotelGuests") static class Guest extends CatalogObject {
        String address;
        String passport = "not exposed";
        public String getMailingAddress() { return address; }
    }
    Principal viewer=()->"support";
    Map<UUID,Guest> guests=new HashMap<>();
    Map<UUID,ContactIdentity> identityRows=new HashMap<>();
    ConversationRepository conversations=mock(ConversationRepository.class);
    ContactIdentityRepository identities=mock(ContactIdentityRepository.class);
    UiAccessService access=mock(UiAccessService.class);
    JdbcTemplate jdbc;
    CrmCatalogBinding<Guest> catalog;
    CrmWorkspaceService workspace;
    CrmContactService contacts;
    Guest guest;
    boolean recordsReadable=true;
    AtomicInteger incomingCalls=new AtomicInteger();

    @BeforeEach void setup() {
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""));
        guest=new Guest();guest.setId(UUID.randomUUID());guest.setDescription("Existing guest");guest.address="Lisbon";guests.put(guest.getId(),guest);
        catalog=new CrmCatalogBinding<>(Guest.class,id->Optional.ofNullable(guests.get(id)))
                .field("postalAddress","Postal address","text",Guest::getMailingAddress).readableWhen((row,p)->recordsReadable);
        var binding=new CrmCustomerBinding<>(catalog,seed->{incomingCalls.incrementAndGet();return catalog.ref(guest.getId());});
        workspace=new CrmWorkspaceService(jdbc,new com.fasterxml.jackson.databind.ObjectMapper(),List.of(),binding,
                TestBindings.noAgents(),CrmStateConfiguration.empty());workspace.initialize();
        contacts=new CrmContactService(binding,conversations,identities,workspace,jdbc,access);contacts.initialize();
        when(access.canRead(viewer,"catalog","HotelGuests")).thenReturn(true);
        when(identities.findActiveById(any())).thenAnswer(call->Optional.ofNullable(identityRows.get(call.getArgument(0))));
        when(identities.findById(any())).thenAnswer(call->Optional.ofNullable(identityRows.get(call.getArgument(0))));
        when(identities.save(any())).thenAnswer(call->{ContactIdentity i=call.getArgument(0);identityRows.put(i.getId(),i);return i;});
        when(identities.findByCustomerAndDeletionMarkFalse(any())).thenAnswer(call->identityRows.values().stream().filter(i->i.getCustomer().equals(call.getArgument(0))).toList());
    }
    @Test void existingCatalogSuppliesOnlyHostSelectedFieldsAndNoBusinessTables() {
        var contact=contacts.get(guest.getId(),viewer);
        assertThat(contact.catalogName()).isEqualTo("HotelGuests");
        assertThat(contact.fields()).containsEntry("postalAddress","Lisbon").doesNotContainKeys("passport","stage","owner","email");
        assertThat(contact.canWrite()).isFalse();
        assertThat(workspace.get().availableFields()).extracting(CrmWorkspaceService.DisplayField::key).contains("customer.postalAddress").doesNotContain("customer.stage");
        assertThat(jdbc.queryForList("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC'",String.class))
                .containsExactlyInAnyOrder("ONNO_CRM_CATALOG_BINDING","ONNO_CRM_CONTACT_GUARD","ONNO_CRM_CUSTOMER_REDIRECT");
        assertThat(workspace.get().config().actions()).filteredOn(a->Set.of("assign","close","reopen").contains(a.key())).allMatch(a->!a.visible());
    }
    @Test void catalogAndRecordPermissionsCannotBeBypassedByInboxAccess() {
        when(access.canRead(viewer,"catalog","HotelGuests")).thenReturn(false);
        assertThatThrownBy(()->contacts.get(guest.getId(),viewer)).isInstanceOf(ResponseStatusException.class);
        when(access.canRead(viewer,"catalog","HotelGuests")).thenReturn(true);
        recordsReadable=false;
        assertThatThrownBy(()->contacts.fields(guest.getId(),viewer)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void providerIdentityReusesHostRecordAndUnknownContactsNeedExplicitPolicy() {
        var seed=new CrmCustomerBinding.IncomingContact(Channel.EMAIL,"mailbox","Guest@Example.test","Guest",null,null,null,"Email");
        assertThat(contacts.resolveIncoming(seed)).isEqualTo(guest.getId());
        assertThat(contacts.resolveIncoming(seed)).isEqualTo(guest.getId());
        assertThat(incomingCalls).hasValue(1);
        assertThat(identityRows.values()).singleElement().satisfies(i->{assertThat(i.getCustomer()).isEqualTo(guest.getId());assertThat(i.isVerified()).isFalse();});
        assertThat(guests).hasSize(1);
        assertThatThrownBy(()->new CrmCustomerBinding<>(catalog).resolve(seed)).hasMessageContaining("not configured");
        guest.setDeletionMark(true);
        assertThatThrownBy(()->contacts.resolveIncoming(seed)).hasMessageContaining("no longer active");
    }
    @Test void linksTransferWithoutMutatingHostDataOrProviderDestination() {
        var target=new Guest();target.setId(UUID.randomUUID());target.setDescription("Survivor");guests.put(target.getId(),target);
        var c=new Conversation();c.setCustomer(guest.getId());c.setChannel(Channel.EMAIL);
        var inbox=su.onno.types.Ref.of(Inbox.class,UUID.randomUUID());c.setInbox(inbox);
        when(conversations.findByCustomerAndDeletionMarkFalse(guest.getId())).thenReturn(List.of(c));
        contacts.link(guest.getId(),Channel.EMAIL,"mailbox","guest@example.test","guest@example.test",false);
        contacts.transferLinks(guest.getId(),target.getId());
        assertThat(c.getCustomer()).isEqualTo(target.getId());assertThat(c.getInbox()).isEqualTo(inbox);
        assertThat(contacts.canonical(guest.getId())).isEqualTo(target.getId());
        assertThat(guest.isDeletionMark()).isFalse();assertThat(guest.getDescription()).isEqualTo("Existing guest");
        assertThat(target.getDescription()).isEqualTo("Survivor");
    }
    @Test void disabledGroupsCreateNoStorageAndRejectCommands() {
        var groups=new CrmChatGroupService(jdbc,new com.fasterxml.jackson.databind.ObjectMapper(),new CrmFeatures(false));groups.initialize();
        assertThatThrownBy(()->groups.change("support","inbox","create",null,"VIP",guest.getId())).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME LIKE 'ONNO_CRM_CHAT_GROUP%'",Integer.class)).isZero();
    }
}
