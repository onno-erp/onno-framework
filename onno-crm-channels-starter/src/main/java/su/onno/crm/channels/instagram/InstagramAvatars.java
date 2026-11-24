package su.onno.crm.channels.instagram;

import java.security.Principal;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import su.onno.crm.repository.ContactIdentityRepository;
import su.onno.crm.service.CrmInboxWorkspaceService;

/**
 * Reads the profile pictures {@link InstagramBridge} caches in {@code onno_crm_ig_avatar}, for people who
 * may see the contact: the identity's customer must be in one of the reader's inbox workspaces.
 */
@Service
@ConditionalOnProperty(name="onno.crm.channels.instagram.enabled",havingValue="true")
public class InstagramAvatars {
    public record Picture(byte[] content,String contentType){}
    private final JdbcTemplate jdbc;private final CrmInboxWorkspaceService access;private final ContactIdentityRepository identities;
    public InstagramAvatars(JdbcTemplate jdbc,CrmInboxWorkspaceService access,ContactIdentityRepository identities){this.jdbc=jdbc;this.access=access;this.identities=identities;}
    /** The identity's cached picture; 404 when there is none, 403 when the contact is outside the reader's workspaces. */
    public Picture read(UUID identity,Principal principal){
        var found=identities.findActiveById(identity).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        access.requireCustomer(found.getCustomer(),principal,false);
        var rows=jdbc.query("SELECT content,content_type FROM onno_crm_ig_avatar WHERE identity_id=? AND content<>''",
                (r,n)->new Picture(Base64.getDecoder().decode(r.getString(1)),r.getString(2)),identity);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return rows.getFirst();
    }
}
