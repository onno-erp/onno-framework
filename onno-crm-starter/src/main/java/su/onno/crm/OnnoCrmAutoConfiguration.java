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
import su.onno.service.EntityAccessPolicy;

/** Installs messaging only when the host explicitly binds its customer catalog. */
@AutoConfiguration(before = {OnnoAutoConfiguration.class, OnnoRepositoriesAutoConfiguration.class})
@ConditionalOnBean(CrmCustomerBinding.class)
@AutoConfigurationPackage(basePackages = "su.onno.crm")
@Import({ConversationService.class, CrmConversationStatuses.class, CrmWorkspaceEvents.class,
        CrmInboxWorkspaceService.class, CrmInboxWorkspaceController.class, CrmFeedQuery.class,
        CrmWorkspaceService.class, CrmChatGroupService.class, CrmChatGroupController.class,
        CrmContactService.class, CrmContactController.class, CrmInboxController.class,
        CrmActivityController.class, CrmChannelController.class, CrmActionController.class,
        CrmAttachments.class, CrmConnectionAccountsController.class})
public class OnnoCrmAutoConfiguration {
    @Bean @ConditionalOnMissingBean
    public CrmConnectionAccounts crmConnectionAccounts(org.springframework.jdbc.core.JdbcTemplate jdbc, CrmChannelAccess access,
            CrmFeatures features, ObjectProvider<su.onno.web.EventPublisher> events) {
        return new CrmConnectionAccounts(jdbc, access, features, events);
    }

    @Bean @ConditionalOnMissingBean
    public CrmPriorityBinding crmPriorityBinding() { return CrmPriorityBinding.empty(); }

    @Bean @ConditionalOnMissingBean
    public CrmStateConfiguration crmStateConfiguration() { return CrmStateConfiguration.empty(); }

    /** Shared channel connections are managed by explicit holders of {@link CrmPermissions#ADMINISTER}. */
    @Bean @ConditionalOnMissingBean
    public CrmChannelAccess crmChannelAccess(su.onno.service.AccessService access) {
        return principal -> principal != null && access.access(principal).granted(CrmPermissions.ADMINISTER);
    }
    @Bean @ConditionalOnMissingBean
    public CrmFeatures crmFeatures() { return CrmFeatures.defaults(); }

    /**
     * Scoped inbox endpoints own record access; generic surfaces (REST, UI, MCP, events) must not
     * bypass workspace policies. Only explicit holders of {@link CrmPermissions#ADMINISTER} read
     * these catalogs generically (with an entity grant on them, like any catalog).
     *
     * <p>Even they lose generic access when {@link CrmFeatures#personalAccounts()} is on: generic
     * surfaces cannot apply the per-row personal-inbox rule, so the catalogs are then closed to every
     * role. The decision is configuration, never data, so it cannot flip at runtime when someone
     * connects an account.</p>
     */
    @Bean public EntityAccessPolicy crmWorkspaceGenericAccess(CrmFeatures features,
            ObjectProvider<su.onno.access.RoleGrants> grants) {
        return crmWorkspaceGenericAccess(features, grants.getIfAvailable(su.onno.access.RoleGrants::open));
    }

    /** See {@link #crmWorkspaceGenericAccess(CrmFeatures, ObjectProvider)}. */
    public EntityAccessPolicy crmWorkspaceGenericAccess(CrmFeatures features, su.onno.access.RoleGrants grants) {
        return new CrmGenericAccessPolicy(features.personalAccounts(), grants);
    }

    /** See {@link #crmWorkspaceGenericAccess(CrmFeatures, ObjectProvider)}. */
    static final class CrmGenericAccessPolicy implements EntityAccessPolicy {
        private static final Set<String> PROTECTED = Set.of(
                "crmconversations", "crmconversationmessages", "crmcontactidentities", "crminboxes");
        private final boolean personalAccounts;
        private final su.onno.access.RoleGrants grants;

        CrmGenericAccessPolicy(boolean personalAccounts, su.onno.access.RoleGrants grants) {
            this.personalAccounts = personalAccounts;
            this.grants = Objects.requireNonNull(grants, "grants");
        }

        @Override public boolean allows(Set<String> roles, String type, String name, boolean write) {
            if (!type.equals("catalog") || !PROTECTED.contains(name.replace("_", "").toLowerCase(Locale.ROOT))) return true;
            return !personalAccounts && grants.resolve(roles).granted(CrmPermissions.ADMINISTER);
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
