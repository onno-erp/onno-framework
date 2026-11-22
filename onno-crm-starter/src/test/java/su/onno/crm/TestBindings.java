package su.onno.crm;

import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;
import su.onno.crm.service.*;

public final class TestBindings {
    /** Roles of the CRM tests: only ADMIN holds crm.administer, and only because it is granted. */
    public enum TestRole implements su.onno.access.RoleKey { ADMIN, CRM_AGENT, CRM_MANAGER }
    public static final su.onno.access.RoleGrants GRANTS = su.onno.access.RoleGrants.of(TestRole.class)
            .role(TestRole.ADMIN, r -> r.permit(CrmPermissions.ADMINISTER));
    /** Make a mocked AccessService resolve permissions from {@link #GRANTS} and its stubbed roles. */
    public static su.onno.service.AccessService withGrants(su.onno.service.AccessService access) {
        org.mockito.Mockito.when(access.access(org.mockito.ArgumentMatchers.<java.security.Principal>any()))
                .thenAnswer(call -> GRANTS.resolve(access.roles(call.<java.security.Principal>getArgument(0))));
        return access;
    }
    @Catalog(name="ExistingClients")
    public static class Client extends CatalogObject {}
    public static CrmCustomerBinding<Client> customers() {
        return new CrmCustomerBinding<>(new CrmCatalogBinding<>(Client.class,id->{var c=new Client();c.setId(id);c.setDescription("Client");return Optional.of(c);}));
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    public static ObjectProvider<CrmAgentBinding<?>> agents() {
        var factory=new StaticListableBeanFactory();
        factory.addBean("agents",new CrmAgentBinding<>(customers().catalog(),user->Optional.empty()));
        return (ObjectProvider)factory.getBeanProvider(CrmAgentBinding.class);
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    public static ObjectProvider<CrmAgentBinding<?>> noAgents() {
        return (ObjectProvider)new StaticListableBeanFactory().getBeanProvider(CrmAgentBinding.class);
    }
    public static CrmContactService readableContacts() {
        var service=org.mockito.Mockito.mock(CrmContactService.class);
        org.mockito.Mockito.when(service.canRead(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn(true);
        return service;
    }
}
