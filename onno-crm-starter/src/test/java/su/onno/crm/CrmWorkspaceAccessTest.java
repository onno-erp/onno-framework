package su.onno.crm;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.security.Principal;
import java.util.List;
import org.junit.jupiter.api.Test;
import su.onno.crm.service.*;
import su.onno.crm.web.CrmChannelController;
import su.onno.ui.UiAccessService;
class CrmWorkspaceAccessTest {
    @Test void agentsCannotManageChannelCredentials() {
        var access=mock(CrmChannelAccess.class);var provider=mock(CrmChannelConnection.class);
        Principal agent=()->"agent";
        var command=new CrmChannelController.Command();command.action="reconnect";command.credential="secret";
        assertThatThrownBy(()->new CrmChannelController(List.of(provider),access,mock(CrmInboxWorkspaceService.class)).command("telegram",command,agent))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(provider);
        assertThat(command.toString()).doesNotContain("secret");
    }
    @Test void managerCanInvokeOnlySupportedConnectorActions() {
        var access=mock(CrmChannelAccess.class);var provider=mock(CrmChannelConnection.class);
        Principal manager=()->"manager";when(access.canManage(manager)).thenReturn(true);
        when(provider.key()).thenReturn("telegram");when(provider.view()).thenReturn(new CrmChannelConnection.View("telegram","Telegram","TELEGRAM","CONNECTED","@bot","",List.of("check")));
        var controller=new CrmChannelController(List.of(provider),access,mock(CrmInboxWorkspaceService.class));var command=new CrmChannelController.Command();command.action="check";
        controller.command("telegram",command,manager);verify(provider).command("check",null);
        command.action="reconnect";assertThatThrownBy(()->controller.command("telegram",command,manager)).isInstanceOf(IllegalArgumentException.class);
    }
}
