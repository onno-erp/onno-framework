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
        CrmInboxController.class,
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
