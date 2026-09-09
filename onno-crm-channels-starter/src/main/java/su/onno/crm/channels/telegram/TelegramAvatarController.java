package su.onno.crm.channels.telegram;

import java.security.Principal;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import su.onno.crm.repository.ContactIdentityRepository;
import su.onno.crm.service.CrmInboxWorkspaceService;

/** Authenticated cached image endpoint; provider download URLs never leave the server. */
@RestController
@ConditionalOnProperty(name="onno.crm.channels.telegram.enabled",havingValue="true")
public class TelegramAvatarController {
    private final JdbcTemplate jdbc; private final CrmInboxWorkspaceService access; private final ContactIdentityRepository identities;
    public TelegramAvatarController(JdbcTemplate jdbc,CrmInboxWorkspaceService access,ContactIdentityRepository identities){this.jdbc=jdbc;this.access=access;this.identities=identities;}
    @GetMapping("/api/crm/telegram/avatars/{id}")
    public ResponseEntity<byte[]> avatar(@PathVariable UUID id,Principal principal){
        var identity=identities.findActiveById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        access.requireCustomer(identity.getCustomer(),principal,false);
        var images=jdbc.query("SELECT content FROM onno_crm_telegram_avatar WHERE identity_id=?",(r,n)->r.getString(1),id);
        if(images.isEmpty()||images.getFirst().isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noCache().cachePrivate()).body(Base64.getDecoder().decode(images.getFirst()));
    }
}
