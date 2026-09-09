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
        CrmActivityController.class, CrmChannelController.class, CrmActionController.class})
public class OnnoCrmAutoConfiguration {
    @Bean @ConditionalOnMissingBean
    public CrmPriorityBinding crmPriorityBinding() { return CrmPriorityBinding.empty(); }

    @Bean @ConditionalOnMissingBean
    public CrmStateConfiguration crmStateConfiguration() { return CrmStateConfiguration.empty(); }

    @Bean @ConditionalOnMissingBean
    public CrmChannelAccess crmChannelAccess(su.onno.ui.UiAccessService access) {
        return principal -> access.roles(principal).contains("ADMIN");
    }
    @Bean @ConditionalOnMissingBean
    public CrmFeatures crmFeatures() { return new CrmFeatures(false); }

    /** Scoped inbox endpoints own record access. Generic exports must not bypass workspace policies. */
    @Bean public UiEntityAccessPolicy crmWorkspaceGenericAccess() {
        return (roles,type,name,write) -> roles.contains("ADMIN") || !type.equals("catalog") || !Set.of(
                "crmconversations","crmconversationmessages","crmcontactidentities").contains(name.replace("_", "").toLowerCase(Locale.ROOT));
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
