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
import su.onno.ui.UiAccessService;

@RestController
@RequestMapping("/api/crm/gmail")
@ConditionalOnProperty(name="onno.crm.channels.gmail.enabled",havingValue="true")
public class GmailOAuthController {
    private final GmailClient client;private final GmailBridge bridge;private final UiAccessService access;
    private record Attempt(String state,String verifier,String user,Instant expires) {}
    public GmailOAuthController(GmailClient client,GmailBridge bridge,UiAccessService access){this.client=client;this.bridge=bridge;this.access=access;}
    private void require(Principal principal){if(!access.hasAnyRole(principal,List.of("CRM_MANAGER")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"CRM manager access is required");}
    @PostMapping("/authorize") public Map<String,String> authorize(HttpSession session,Principal principal) throws Exception {
        require(principal);String state=random(),verifier=random();
        String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String url=client.authorizationUrl(state,challenge);session.setAttribute("onno.crm.channels.gmail.oauth",new Attempt(state,verifier,principal.getName(),Instant.now().plusSeconds(600)));return Map.of("url",url);
    }
    @GetMapping("/callback") public ResponseEntity<Void> callback(@RequestParam(required=false) String code,@RequestParam(required=false) String state,
        @RequestParam(required=false) String error,HttpSession session,Principal principal){
        require(principal);Attempt attempt;
        synchronized(session){attempt=(Attempt)session.getAttribute("onno.crm.channels.gmail.oauth");session.removeAttribute("onno.crm.channels.gmail.oauth");}
        if(attempt==null||state==null||!MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),attempt.state.getBytes(StandardCharsets.UTF_8))||!attempt.user.equals(principal.getName())||Instant.now().isAfter(attempt.expires))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Gmail sign-in expired or did not match this session; start again");
        String result="error";
        if(error==null&&code!=null&&!code.isBlank())try{bridge.connect(client.exchange(code,attempt.verifier));result="connected";}catch(RuntimeException ignored){}
        return ResponseEntity.status(HttpStatus.SEE_OTHER).header("Location","/ui/crm-settings?gmail="+result).header("Cache-Control","no-store").build();
    }
    private static String random(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
}
