package su.onno.crm.web;

import su.onno.command.CommandRegistry;
import java.security.Principal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import su.onno.command.Command;
import su.onno.command.CommandContext;
import su.onno.command.CommandResult;
import su.onno.crm.domain.Conversation;
import su.onno.crm.service.CrmInboxWorkspaceService;
import su.onno.web.*;
import su.onno.service.*;

/**
 * Runs the host's record {@link su.onno.command.Commands commands} for {@link Conversation} inside the
 * conversation's workspace access boundary (workspace membership replaces generic entity access,
 * which CRM denies to non-admins). Roles, read-only mode and per-record availability/enabled state
 * are enforced as on every other surface. List entries: {@code {id, title, visible, enabled}}.
 */
@RestController
@RequestMapping("/api/crm/inbox-workspaces/{workspace}/conversations/{id}/actions")
public class CrmActionController {
    private final CrmInboxWorkspaceService workspaces;
    private final CommandRegistry commands;
    private final AccessService access;
    private final CatalogQueryService catalogs;
    private final WebProperties webProperties;
    public CrmActionController(CrmInboxWorkspaceService workspaces,@org.springframework.context.annotation.Lazy CommandRegistry commands,AccessService access,
            CatalogQueryService catalogs,WebProperties webProperties) {
        this.workspaces=workspaces;this.commands=commands;this.access=access;this.catalogs=catalogs;this.webProperties=webProperties;
    }
    private boolean allowed(Command<?> command,Principal principal) {
        return command.isRecordCommand() &&
            (command.roles().isEmpty() || access.hasAnyRole(principal,command.roles()));
    }
    // The workspace boundary authorizes the conversation; the row is read as trusted code.
    private Map<String,Object> row(UUID id) { return catalogs.get(su.onno.access.AccessSubject.system(),catalogs.forClass(Conversation.class),id); }
    @GetMapping public Object list(@PathVariable String workspace,@PathVariable UUID id,Principal principal) {
        workspaces.requireConversation(workspace,id,principal,false);
        var definition=workspaces.requireWorkspace(workspace,principal,false);
        boolean writable=!webProperties.isReadOnly() && workspaces.permitted(definition,principal,true);
        var record=row(id);
        return commands.commands(Conversation.class).stream().filter(c->allowed(c,principal)).map(c->{
            var state=CommandRegistry.recordState(c,record,catalogs.forClass(Conversation.class).attributes());
            // group: the submenu it belongs to ("Change status"); inputs: it asks for values first,
            // so a one-click surface such as a row menu leaves it to the conversation's own actions.
            return Map.<String,Object>of("id",c.key(),"title",state.title(),"visible",state.available(),"enabled",writable&&state.enabled(),
                "group",c.group(),"inputs",!c.inputs().isEmpty() || !c.inputGroups().isEmpty());
        }).filter(c->Boolean.TRUE.equals(c.get("visible"))).toList();
    }
    @PostMapping("/{key}") public CommandResult run(@PathVariable String workspace,@PathVariable UUID id,@PathVariable String key,
            @RequestBody(required=false) Map<String,Object> body,Principal principal,su.onno.access.AccessSubject subject) {
        workspaces.requireConversation(workspace,id,principal,true);
        if(webProperties.isReadOnly())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Application is read-only");
        var command=commands.find(Conversation.class,key);
        if(command==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Unknown command");
        if(!allowed(command,principal))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        var state=CommandRegistry.recordState(command,row(id),catalogs.forClass(Conversation.class).attributes());
        if(!state.available() || !state.enabled())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Command unavailable");
        var result=command.handler().apply(CommandContext.from("catalogs","CrmConversations",id,principal.getName(),body,subject));
        return result==null?CommandResult.ok():result;
    }
}
