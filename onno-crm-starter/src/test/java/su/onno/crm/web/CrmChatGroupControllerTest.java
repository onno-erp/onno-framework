package su.onno.crm.web;

import java.security.Principal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import su.onno.crm.service.*;
import su.onno.ui.UiAccessService;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CrmChatGroupControllerTest {
    @Test void deniesUnauthorizedWorkspaceAndConversationBeforeWritingGroups() {
        var groups=mock(CrmChatGroupService.class);var workspaces=mock(CrmInboxWorkspaceService.class);var access=mock(UiAccessService.class);
        var controller=new CrmChatGroupController(groups,workspaces,access);Principal alice=()->"alice";
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN)).when(workspaces).requireAccess(alice,false);
        assertThatThrownBy(()->controller.list(null,alice)).isInstanceOf(ResponseStatusException.class);
        doNothing().when(workspaces).requireAccess(alice,false);
        when(workspaces.requireWorkspace("private",alice,false)).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN));
        assertThatThrownBy(()->controller.list("private",alice)).isInstanceOf(ResponseStatusException.class);
        var id=UUID.randomUUID();when(workspaces.requireConversation(id,alice,false)).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN));
        assertThatThrownBy(()->controller.change(null,new CrmChatGroupController.Change("create",null,"VIP",id),alice)).isInstanceOf(ResponseStatusException.class);
        verify(groups,never()).change(any(),any(),any(),any(),any(),any());
        verify(groups,never()).list(any(),any());
    }
}
