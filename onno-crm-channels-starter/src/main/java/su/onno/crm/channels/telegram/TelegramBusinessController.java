package su.onno.crm.channels.telegram;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import su.onno.crm.service.*;

@RestController
@RequestMapping("/api/crm/telegram/business")
@ConditionalOnProperty(name="onno.crm.channels.telegram.enabled",havingValue="true")
public class TelegramBusinessController {
    private final TelegramBusinessAccounts business;
    private final CrmChannelAccess access;
    private final CrmInboxWorkspaceService workspaces;
    public TelegramBusinessController(TelegramBusinessAccounts business,CrmChannelAccess access,CrmInboxWorkspaceService workspaces) {
        this.business=business;this.access=access;this.workspaces=workspaces;
    }
    @PostMapping("/{account}/authorize")
    public Map<String,String> authorize(@PathVariable UUID account,Principal principal) {
        if(!access.canManage(principal))workspaces.requireAccess(principal,true);
        return Map.of("url",business.authorize(account,principal));
    }
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String,String> invalid(RuntimeException error) {return Map.of("message","Telegram business setup is unavailable. Check the bot connection and try again.");}
}
