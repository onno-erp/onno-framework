package su.onno.crm;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import su.onno.crm.repository.AgentRepository;
import su.onno.crm.service.CrmAgentIdentityResolver;
import su.onno.crm.service.ConversationService;
import su.onno.crm.ui.AgentView;
import su.onno.crm.ui.ConversationMessageView;
import su.onno.crm.ui.ConversationView;
import su.onno.crm.ui.CrmLayout;
import su.onno.crm.ui.CustomerView;
import su.onno.crm.ui.InboxPage;
import su.onno.crm.ui.InboxView;
import su.onno.crm.ui.OpportunityView;
import su.onno.crm.ui.PipelinePage;
import su.onno.crm.web.CrmInboxController;
import su.onno.spring.OnnoAutoConfiguration;
import su.onno.spring.OnnoRepositoriesAutoConfiguration;

/**
 * Installs the reusable CRM business model, repositories, commands, API, UI metadata, and widget
 * bundle. Registering {@code su.onno.crm} as an auto-configuration package makes the ordinary
 * onno metadata and Spring Data repository scanners discover module-owned types without requiring
 * a consuming application to broaden its own component scan.
 */
@AutoConfiguration(before = {OnnoAutoConfiguration.class, OnnoRepositoriesAutoConfiguration.class})
@AutoConfigurationPackage(basePackages = "su.onno.crm")
@Import({
        ConversationService.class,
        su.onno.crm.service.CrmConversationStatuses.class,
        su.onno.crm.ui.ChatStatusView.class,
        su.onno.crm.service.CrmWorkspaceEvents.class,
        su.onno.crm.service.CrmInboxWorkspaceService.class,
        su.onno.crm.web.CrmInboxWorkspaceController.class,
        su.onno.crm.web.CrmConversationViewController.class,
        su.onno.crm.service.CrmWorkspaceService.class,
        su.onno.crm.service.CrmChatGroupService.class,
        su.onno.crm.web.CrmChatGroupController.class,
        su.onno.crm.service.CrmContactService.class,
        su.onno.crm.web.CrmContactController.class,
        su.onno.crm.ui.CrmSettingsPage.class,
        CrmInboxController.class,
        su.onno.crm.web.CrmActivityController.class,
        su.onno.crm.web.CrmChannelController.class,
        su.onno.crm.ui.TagView.class,
        su.onno.crm.ui.LifecycleStageView.class,
        AgentView.class,
        ConversationMessageView.class,
        ConversationView.class,
        CrmLayout.class,
        CustomerView.class,
        InboxPage.class,
        InboxView.class,
        OpportunityView.class,
        PipelinePage.class
})
public class OnnoCrmAutoConfiguration {
    @Bean @ConditionalOnMissingBean(su.onno.crm.service.CrmStateConfiguration.class)
    public su.onno.crm.service.CrmStateConfiguration crmStateConfiguration(){return su.onno.crm.service.CrmStateConfiguration.empty();}
    @Bean @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="onno.schema.mode",havingValue="apply",matchIfMissing=true)
    public su.onno.crm.service.CrmStateProjection crmStateProjection(su.onno.crm.service.CrmStateConfiguration config,
            su.onno.crm.repository.LifecycleStageRepository stages,su.onno.crm.repository.ChatStatusRepository statuses){
        return new su.onno.crm.service.CrmStateProjection(config,stages,statuses);
    }
    @Bean public su.onno.ui.UiEntityAccessPolicy crmCodeOwnedStateAccess(){
        return (roles,type,name,write)->!write||!type.equals("catalog")||!java.util.Set.of("crmcustomerstages","crmconversationstatuses").contains(name.replace("_", "").toLowerCase(java.util.Locale.ROOT));
    }

    @Bean public su.onno.crm.service.CrmTagCatalog crmTagCatalog(su.onno.crm.repository.TagRepository tags) { return new su.onno.crm.service.CrmTagCatalog(tags); }
    @Bean
    public su.onno.ui.tags.TagAccessPolicy crmTagAccess(su.onno.crm.service.CrmInboxWorkspaceService workspaces) {
        return (kind,name,id,principal,write) -> {
            if (kind.equals("catalogs") && name.replace("_", "").equalsIgnoreCase("CrmCustomers")) workspaces.requireCustomer(id,principal,write);
        };
    }

    @Bean
    public org.springframework.context.ApplicationListener<org.springframework.boot.context.event.ApplicationReadyEvent> importCrmTags(
            org.springframework.beans.factory.ObjectProvider<su.onno.ui.tags.TagService> tags,
            su.onno.crm.repository.CustomerRepository customers) {
        return event -> tags.ifAvailable(service -> {
            service.library(su.onno.ui.tags.TagService.scope("catalogs","CrmCustomers"));
            customers.findAllActive().forEach(customer -> service.importLegacy(su.onno.ui.tags.TagService.scope("catalogs","CrmCustomers"),customer.getId(),customer.getTags()));
        });
    }


    @Bean
    public su.onno.ui.UiEntityAccessPolicy crmWorkspaceGenericAccess(org.springframework.beans.factory.ObjectProvider<su.onno.crm.service.CrmInboxWorkspace> definitions) {
        return (roles,type,name,write) -> definitions.orderedStream().findAny().isEmpty()
            || roles.contains("ADMIN") || !type.equals("catalog")
            || !java.util.Set.of("crmconversations","crmconversationmessages").contains(name);
    }

    @Bean
    @ConditionalOnMissingBean(su.onno.crm.service.CrmMessageTransport.class)
    public su.onno.crm.service.CrmMessageTransport crmMessageTransport() {
        return new su.onno.crm.service.CrmMessageTransport() {
            public Connection connection(su.onno.crm.domain.Conversation conversation) {
                return new Connection(false, "Messaging channel is not connected", 8000);
            }
            public void enqueue(su.onno.crm.domain.Conversation conversation,
                    su.onno.crm.domain.ConversationMessage message) {
                throw new IllegalArgumentException("Messaging channel is not connected");
            }
        };
    }

    /**
     * Default mapping works both when {@code Agent} is the host identity catalog and when an
     * enterprise application uses another identity catalog with the same authenticated email.
     */
    @Bean
    @ConditionalOnMissingBean
    public CrmAgentIdentityResolver crmAgentIdentityResolver(AgentRepository agents) {
        return user -> {
            if (user == null || user.username() == null || user.username().isBlank()) {
                return java.util.Optional.empty();
            }
            if ("CrmAgents".equals(user.entityName()) && user.recordId() != null) {
                try {
                    return java.util.Optional.of(java.util.UUID.fromString(user.recordId()));
                } catch (IllegalArgumentException ignored) {
                    // Fall through to the stable login lookup.
                }
            }
            return agents.findByEmailIgnoreCaseAndDeletionMarkFalse(user.username())
                    .map(agent -> agent.getId());
        };
    }
}
