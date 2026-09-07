package su.onno.crm.web;

import java.security.Principal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import su.onno.crm.service.CrmChannelConnection;
import su.onno.ui.UiAccessService;

@RestController
@RequestMapping("/api/crm/channels")
public class CrmChannelController {
    private final List<CrmChannelConnection> connections;
    private final UiAccessService access;
    public CrmChannelController(List<CrmChannelConnection> connections, UiAccessService access) {
        this.connections = connections; this.access = access;
    }
    private void require(Principal principal, boolean manage) {
        if (!access.hasAnyRole(principal, manage ? List.of("CRM_MANAGER") : List.of("CRM_AGENT", "CRM_MANAGER")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "CRM manager access is required to manage connections");
    }
    @GetMapping public Map<String,Object> list(Principal principal) {
        require(principal, false);
        List<CrmChannelConnection.View> views = new ArrayList<>(connections.stream().map(CrmChannelConnection::view).toList());
        for (var provider : List.of(new String[]{"telegram","Telegram","TELEGRAM","A Telegram bot connector is required."},
                new String[]{"instagram","Instagram","INSTAGRAM","An Instagram messaging connector and Meta sign-in are required."},
                new String[]{"whatsapp","WhatsApp","WHATSAPP","A WhatsApp Business connector and Meta sign-in are required."},
                new String[]{"gmail","Gmail","EMAIL","A Gmail connector and Google sign-in are required."},
                new String[]{"outlook","Microsoft 365","EMAIL","A Microsoft mail connector and Microsoft sign-in are required."})) {
            if (views.stream().noneMatch(v -> v.key().equals(provider[0])))
                views.add(new CrmChannelConnection.View(provider[0],provider[1],provider[2],"UNAVAILABLE","",provider[3],List.of()));
        }
        return Map.of("channels", views, "canManage", access.hasAnyRole(principal,List.of("CRM_MANAGER")));
    }
    public static final class Command {
        public String action;
        public String credential;
        @Override public String toString() { return "Channel command [credentials redacted]"; }
    }
    @PostMapping("/{key}") public CrmChannelConnection.View command(@PathVariable String key,@RequestBody Command request,Principal principal) {
        require(principal,true);
        var connection=connections.stream().filter(c->c.key().equals(key)).findFirst()
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Channel connector is not installed"));
        if(request==null || request.action==null || !connection.view().actions().contains(request.action))
            throw new IllegalArgumentException("This channel action is unavailable");
        connection.command(request.action,request.credential);
        return connection.view();
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Map<String,String> invalid(IllegalArgumentException error) { return Map.of("message",error.getMessage()); }
}
