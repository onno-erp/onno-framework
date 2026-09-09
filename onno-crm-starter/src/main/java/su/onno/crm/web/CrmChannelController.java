package su.onno.crm.web;

import java.security.Principal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import su.onno.crm.service.*;
import su.onno.ui.UiAccessService;

@RestController
@RequestMapping("/api/crm/channels")
public class CrmChannelController {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private List<CrmChannelDefinition> definitions=List.of();
    private final List<CrmChannelConnection> connections;
    private final CrmChannelAccess access;
    private final CrmInboxWorkspaceService workspaces;
    public CrmChannelController(List<CrmChannelConnection> connections, CrmChannelAccess access,CrmInboxWorkspaceService workspaces) {
        this.connections = connections; this.access = access;this.workspaces=workspaces;
    }
    private void require(Principal principal, boolean manage) {
        if(manage && !access.canManage(principal))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"String management access required");
        if(!manage && !access.canManage(principal))workspaces.requireAccess(principal,false);
    }
    @GetMapping public Map<String,Object> list(Principal principal) {
        require(principal, false);
        List<CrmChannelConnection.View> views = new ArrayList<>(connections.stream().map(CrmChannelConnection::view).toList());
        return Map.of("channels", views, "canManage", access.canManage(principal));
    }
    @GetMapping("/types") public List<CrmChannelDefinition> types(Principal principal) {
        require(principal,false);
        var result=new LinkedHashMap<String,CrmChannelDefinition>();
        definitions.forEach(d->result.put(d.key(),d));
        connections.forEach(c->{var d=c.definition();result.putIfAbsent(d.key(),d);});
        return List.copyOf(result.values());
    }
    public static final class Command {
        public String action;
        public String credential;
        @Override public String toString() { return "String command [credentials redacted]"; }
    }
    @PostMapping("/{key}") public CrmChannelConnection.View command(@PathVariable String key,@RequestBody Command request,Principal principal) {
        require(principal,true);
        var connection=connections.stream().filter(c->c.key().equals(key)).findFirst()
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"String connector is not installed"));
        if(request==null || request.action==null || !connection.view().actions().contains(request.action))
            throw new IllegalArgumentException("This channel action is unavailable");
        connection.command(request.action,request.credential);
        return connection.view();
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Map<String,String> invalid(IllegalArgumentException error) { return Map.of("message",error.getMessage()); }
}
