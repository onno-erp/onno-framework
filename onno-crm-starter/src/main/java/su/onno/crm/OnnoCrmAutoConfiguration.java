package su.onno.crm;

import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import su.onno.crm.service.*;
import su.onno.crm.repository.*;
import su.onno.crm.web.*;
import su.onno.spring.OnnoAutoConfiguration;
import su.onno.spring.OnnoRepositoriesAutoConfiguration;
import su.onno.ui.UiEntityAccessPolicy;

/** Installs messaging only when the host explicitly binds its customer catalog. */
@AutoConfiguration(before = {OnnoAutoConfiguration.class, OnnoRepositoriesAutoConfiguration.class})
@ConditionalOnBean(CrmCustomerBinding.class)
@AutoConfigurationPackage(basePackages = "su.onno.crm")
@Import({ConversationService.class, CrmConversationStatuses.class, CrmWorkspaceEvents.class,
        CrmInboxWorkspaceService.class, CrmInboxWorkspaceController.class, CrmConversationViewController.class,
        CrmWorkspaceService.class, CrmChatGroupService.class, CrmChatGroupController.class,
        CrmContactService.class, CrmContactController.class, CrmInboxController.class,
        CrmActivityController.class, CrmChannelController.class, CrmActionController.class,
        CrmAttachments.class, CrmConnectionAccountsController.class})
public class OnnoCrmAutoConfiguration {
    @Bean @ConditionalOnMissingBean
    public CrmConnectionAccounts crmConnectionAccounts(org.springframework.jdbc.core.JdbcTemplate jdbc, CrmChannelAccess access,
            CrmFeatures features, ObjectProvider<su.onno.ui.UiEventPublisher> events) {
        return new CrmConnectionAccounts(jdbc, access, features, events);
    }

    @Bean @ConditionalOnMissingBean
    public CrmPriorityBinding crmPriorityBinding() { return CrmPriorityBinding.empty(); }

    @Bean @ConditionalOnMissingBean
    public CrmStateConfiguration crmStateConfiguration() { return CrmStateConfiguration.empty(); }

    @Bean @ConditionalOnMissingBean
    public CrmChannelAccess crmChannelAccess(su.onno.ui.UiAccessService access) {
        return principal -> access.roles(principal).contains("ADMIN");
    }
    @Bean @ConditionalOnMissingBean
    public CrmFeatures crmFeatures() { return CrmFeatures.defaults(); }

    /** Fails startup when a CrmInboxView bean names an unknown workspace; warns on undeclared filter keys. */
    @Bean @ConditionalOnMissingBean
    public su.onno.crm.ui.CrmInboxViewValidator crmInboxViewValidator(ObjectProvider<su.onno.crm.ui.CrmInboxView> views,
                                                                     ObjectProvider<CrmInboxWorkspace> workspaces) {
        return new su.onno.crm.ui.CrmInboxViewValidator(views, workspaces);
    }

    /**
     * Scoped inbox endpoints own record access; generic surfaces (REST, UI, MCP, events) must not
     * bypass workspace policies. Non-administrators never read these catalogs generically.
     *
     * <p>Administrators keep generic access unless {@link CrmFeatures#personalAccounts()} is on:
     * generic surfaces cannot apply the per-row personal-inbox rule, so the catalogs are then closed
     * to every role. The decision is configuration, never data, so it cannot flip at runtime when
     * someone connects an account.</p>
     */
    @Bean public UiEntityAccessPolicy crmWorkspaceGenericAccess(CrmFeatures features) {
        return new CrmGenericAccessPolicy(features.personalAccounts());
    }

    /** See {@link #crmWorkspaceGenericAccess}. */
    static final class CrmGenericAccessPolicy implements UiEntityAccessPolicy {
        private static final Set<String> PROTECTED = Set.of(
                "crmconversations", "crmconversationmessages", "crmcontactidentities", "crminboxes");
        private final boolean personalAccounts;

        CrmGenericAccessPolicy(boolean personalAccounts) {
            this.personalAccounts = personalAccounts;
        }

        @Override public boolean includesAdministrators() { return true; }

        @Override public boolean allows(Set<String> roles, String type, String name, boolean write) {
            if (!type.equals("catalog") || !PROTECTED.contains(name.replace("_", "").toLowerCase(Locale.ROOT))) return true;
            return roles.contains("ADMIN") && !personalAccounts;
        }
    }

    @Bean @ConditionalOnMissingBean(CrmMessageTransport.class)
    public CrmMessageTransport crmMessageTransport() {
        return new CrmMessageTransport() {
            public Connection connection(su.onno.crm.domain.Conversation conversation) {
                return new Connection(false,"Messaging channel is not connected",8000);
            }
            public void enqueue(su.onno.crm.domain.Conversation conversation, su.onno.crm.domain.ConversationMessage message) {
                throw new IllegalArgumentException("Messaging channel is not connected");
            }
        };
    }
    @Bean @ConditionalOnMissingBean
    public CrmAgentIdentityResolver crmAgentIdentityResolver(ObjectProvider<CrmAgentBinding<?>> bindings) {
        return user -> { var binding=bindings.getIfAvailable();return binding==null?Optional.empty():binding.resolve(user); };
    }
}
