package su.onno.crm.channels.gmail;

import jakarta.servlet.http.HttpSession;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import su.onno.crm.service.CrmChannelAccess;
import su.onno.crm.service.CrmConnectionAccounts;
import su.onno.crm.service.CrmInboxWorkspaceService;

@RestController
@RequestMapping("/api/crm/gmail")
@ConditionalOnProperty(name="onno.crm.channels.gmail.enabled",havingValue="true")
public class GmailOAuthController {
    private final GmailClient client;
    private final GmailBridge bridge;
    private final CrmChannelAccess access;
    private final CrmConnectionAccounts accounts;
    private final CrmInboxWorkspaceService workspaces;
    private record Attempt(String state,String verifier,String user,Instant expires,String returnPath,UUID accountId) {}
    public GmailOAuthController(GmailClient client, GmailBridge bridge, CrmChannelAccess access,
                                CrmConnectionAccounts accounts, CrmInboxWorkspaceService workspaces) {
        this.client = client;
        this.bridge = bridge;
        this.access = access;
        this.accounts = accounts;
        this.workspaces = workspaces;
    }
    private void require(Principal principal){if(!access.canManage(principal))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Channel management access is required");}
    public record AuthorizationRequest(String returnPath,UUID accountId) { public AuthorizationRequest(String returnPath) { this(returnPath,null); } }
    private void requireAccount(UUID id, Principal p) {
        if (id == null) {
            require(p);
            return;
        }
        var account = accounts.require(id, p);
        if (!account.provider().equals("gmail")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        if (!access.canManage(p)) workspaces.requireAccess(p, true);
    }
    static String returnPath(String value) {
        if(value==null || !value.startsWith("/") || value.startsWith("//") || value.contains("\\")
                || value.chars().anyMatch(c->c<32) || value.length()>2000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a local return path");
        return value;
    }
    @PostMapping("/authorize") public Map<String,String> authorize(@RequestBody AuthorizationRequest request,HttpSession session,Principal principal) throws Exception {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a local return path");
        requireAccount(request.accountId(), principal);
        String returnPath = returnPath(request.returnPath());
        String state = random(), verifier = random();
        String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String url=client.authorizationUrl(state,challenge);session.setAttribute("onno.crm.channels.gmail.oauth",new Attempt(state,verifier,principal.getName(),Instant.now().plusSeconds(600),returnPath,request.accountId()));return Map.of("url",url);
    }
    @GetMapping("/callback") public ResponseEntity<Void> callback(@RequestParam(required=false) String code,@RequestParam(required=false) String state,
        @RequestParam(required=false) String error,HttpSession session,Principal principal){
        if(principal==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);Attempt attempt;
        synchronized(session){attempt=(Attempt)session.getAttribute("onno.crm.channels.gmail.oauth");session.removeAttribute("onno.crm.channels.gmail.oauth");}
        if(attempt==null||state==null||!MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),attempt.state.getBytes(StandardCharsets.UTF_8))||!attempt.user.equals(principal.getName())||Instant.now().isAfter(attempt.expires))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Gmail sign-in expired or did not match this session; start again");
        requireAccount(attempt.accountId,principal);
        String result="error";
        if(error==null&&code!=null&&!code.isBlank())try{bridge.connect(client.exchange(code,attempt.verifier),attempt.accountId);accounts.changed();result="connected";}catch(RuntimeException ignored){}
        return ResponseEntity.status(HttpStatus.SEE_OTHER).header("Location",org.springframework.web.util.UriComponentsBuilder.fromUriString(attempt.returnPath).replaceQueryParam("gmail",result).build().toUriString()).header("Cache-Control","no-store").build();
    }
    private static String random(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
}
