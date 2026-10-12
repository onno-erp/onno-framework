package su.onno.crm.channels.gmail;

import java.security.Principal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;
import su.onno.crm.service.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GmailScopedOAuthTest {
    @Test void authorizationBindsAccountAndOwnerAndConsumesTheAttemptOnce() throws Exception {
        var client=mock(GmailClient.class);var bridge=mock(GmailBridge.class);var accounts=mock(CrmConnectionAccounts.class);var workspaces=mock(CrmInboxWorkspaceService.class);
        var controller=new GmailOAuthController(client,bridge,p->false,accounts,workspaces);
        Principal alice=()->"alice";UUID id=UUID.randomUUID();
        when(accounts.require(id,alice)).thenReturn(new CrmConnectionAccounts.Account(id,"gmail",CrmConnectionAccounts.Scope.PERSONAL,"alice","Work",null));
        var state=new java.util.concurrent.atomic.AtomicReference<String>();
        when(client.authorizationUrl(anyString(),anyString())).thenAnswer(call->{state.set(call.getArgument(0));return "https://accounts.google.com/test";});
        var tokens=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();when(client.exchange(eq("code"),anyString())).thenReturn(tokens);
        var session=new MockHttpSession();controller.authorize(new GmailOAuthController.AuthorizationRequest("/ui/connections",id),session,alice);
        assertThat(controller.callback("code",state.get(),null,session,alice).getHeaders().getLocation().toString()).isEqualTo("/ui/connections?gmail=connected");
        verify(bridge).connect(tokens,id);verify(accounts,times(2)).require(id,alice);verify(workspaces,times(2)).requireAccess(alice,true);
        assertThatThrownBy(()->controller.callback("code",state.get(),null,session,alice)).isInstanceOf(ResponseStatusException.class);
        verify(bridge,times(1)).connect(any(),any());
    }
    @Test void anotherSessionIdentityCannotCompleteTheOwnersAuthorization() throws Exception {
        var client=mock(GmailClient.class);var bridge=mock(GmailBridge.class);var accounts=mock(CrmConnectionAccounts.class);
        var controller=new GmailOAuthController(client,bridge,p->true,accounts,mock(CrmInboxWorkspaceService.class));
        Principal alice=()->"alice",admin=()->"admin";UUID id=UUID.randomUUID();
        when(accounts.require(id,alice)).thenReturn(new CrmConnectionAccounts.Account(id,"gmail",CrmConnectionAccounts.Scope.PERSONAL,"alice","Work",null));
        var state=new java.util.concurrent.atomic.AtomicReference<String>();when(client.authorizationUrl(anyString(),anyString())).thenAnswer(call->{state.set(call.getArgument(0));return "https://accounts.google.com/test";});
        var session=new MockHttpSession();controller.authorize(new GmailOAuthController.AuthorizationRequest("/ui/connections",id),session,alice);
        assertThatThrownBy(()->controller.callback("code",state.get(),null,session,admin)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(bridge);verify(client,never()).exchange(anyString(),anyString());
    }
}
