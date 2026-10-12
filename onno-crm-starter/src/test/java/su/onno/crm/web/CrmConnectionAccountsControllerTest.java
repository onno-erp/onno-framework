package su.onno.crm.web;

import java.security.Principal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import su.onno.crm.service.*;
import su.onno.crm.service.CrmConnectionAccounts.Scope;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CrmConnectionAccountsControllerTest {
    final Principal viewer = () -> "viewer", admin = () -> "admin", owner = () -> "owner";

    CrmConnectionAccounts accounts(CrmChannelAccess access, boolean personal) {
        // DB_CLOSE_DELAY keeps the in-memory database alive across JDBC connections.
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        var accounts = new CrmConnectionAccounts(jdbc, access, CrmFeatures.defaults().withPersonalAccounts(personal));
        accounts.initialize();
        return accounts;
    }

    CrmAccountProvider provider(boolean enabled) {
        return new CrmAccountProvider() {
            public Descriptor descriptor() {
                return new Descriptor("gmail", "Gmail", "EMAIL", "", "Mail", enabled, List.of(Scope.SHARED, Scope.PERSONAL), "Configure OAuth");
            }
            public CrmChannelConnection.View view(CrmConnectionAccounts.Account account) {
                return new CrmChannelConnection.View(account.id().toString(), "Gmail", "EMAIL", "CONNECTED", "", "", List.of("disconnect"));
            }
            public void command(CrmConnectionAccounts.Account account, String action) {}
        };
    }

    @Test void unavailableProvidersStayVisibleButCannotBeCreatedAndViewersCannotCreatePersonalAccounts() {
        var access = mock(CrmChannelAccess.class);
        when(access.canManagePersonal(viewer)).thenReturn(true);
        when(access.canManage(admin)).thenReturn(true);
        var accounts = accounts(access, true);
        var controller = new CrmConnectionAccountsController(accounts, List.of(provider(false)), access, mock(CrmInboxWorkspaceService.class));
        assertThat(controller.list(viewer).providers()).hasSize(1);
        assertThat(controller.list(viewer).canManagePersonal()).isFalse();
        assertThatThrownBy(() -> controller.create(new CrmConnectionAccountsController.Create("gmail", "PERSONAL", "Private"), viewer))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.create(new CrmConnectionAccountsController.Create("gmail", "SHARED", "Team"), admin))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(accounts.visible(viewer)).isEmpty();
    }

    @Test void personalScopeIsWithheldUnlessTheDeploymentEnablesIt() {
        var access = mock(CrmChannelAccess.class);
        when(access.canManage(admin)).thenReturn(true);
        when(access.canManagePersonal(any())).thenReturn(true);
        var controller = new CrmConnectionAccountsController(accounts(access, false), List.of(provider(true)), access, mock(CrmInboxWorkspaceService.class));
        var listing = controller.list(admin);
        assertThat(listing.providers().getFirst().scopes()).containsExactly(Scope.SHARED);
        assertThat(listing.canManagePersonal()).isFalse();
        assertThatThrownBy(() -> controller.create(new CrmConnectionAccountsController.Create("gmail", "PERSONAL", "Mine"), admin))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test void ownersKeepControlOfTheirPersonalAccountAfterLosingInboxAccess() {
        var access = mock(CrmChannelAccess.class);
        when(access.canManagePersonal(owner)).thenReturn(true);
        var workspaces = mock(CrmInboxWorkspaceService.class);
        when(workspaces.available(owner)).thenReturn(List.of(new CrmInboxWorkspace("all", "All", Set.of("AGENT"), c -> true)));
        when(workspaces.permitted(any(), eq(owner), eq(true))).thenReturn(true);
        var accounts = accounts(access, true);
        var controller = new CrmConnectionAccountsController(accounts, List.of(provider(true)), access, workspaces);
        var created = controller.create(new CrmConnectionAccountsController.Create("gmail", "PERSONAL", "Mine"), owner);

        // The owner's role is revoked: no workspace access, no personal-account policy.
        when(workspaces.permitted(any(), eq(owner), anyBoolean())).thenReturn(false);
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN)).when(workspaces).requireAccess(eq(owner), anyBoolean());
        when(access.canManagePersonal(owner)).thenReturn(false);

        assertThat(controller.command(created.id(), new CrmConnectionAccountsController.Command("disconnect", null), owner))
                .isInstanceOf(CrmConnectionAccountsController.AccountView.class);
        assertThatThrownBy(() -> controller.command(created.id(), new CrmConnectionAccountsController.Command("disconnect", null), viewer))
                .isInstanceOf(ResponseStatusException.class);
    }
}
