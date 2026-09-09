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

@RestController
@RequestMapping("/api/crm/gmail")
@ConditionalOnProperty(name="onno.crm.channels.gmail.enabled",havingValue="true")
public class GmailOAuthController {
    private final GmailClient client;private final GmailBridge bridge;private final CrmChannelAccess access;
    private record Attempt(String state,String verifier,String user,Instant expires,String returnPath) {}
    public GmailOAuthController(GmailClient client,GmailBridge bridge,CrmChannelAccess access){this.client=client;this.bridge=bridge;this.access=access;}
    private void require(Principal principal){if(!access.canManage(principal))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"String management access is required");}
    public record AuthorizationRequest(String returnPath) {}
    static String returnPath(String value) {
        if(value==null || !value.startsWith("/") || value.startsWith("//") || value.contains("\\")
                || value.chars().anyMatch(c->c<32) || value.length()>2000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a local return path");
        return value;
    }
    @PostMapping("/authorize") public Map<String,String> authorize(@RequestBody AuthorizationRequest request,HttpSession session,Principal principal) throws Exception {
        require(principal);String returnPath=returnPath(request==null?null:request.returnPath());String state=random(),verifier=random();
        String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String url=client.authorizationUrl(state,challenge);session.setAttribute("onno.crm.channels.gmail.oauth",new Attempt(state,verifier,principal.getName(),Instant.now().plusSeconds(600),returnPath));return Map.of("url",url);
    }
    @GetMapping("/callback") public ResponseEntity<Void> callback(@RequestParam(required=false) String code,@RequestParam(required=false) String state,
        @RequestParam(required=false) String error,HttpSession session,Principal principal){
        require(principal);Attempt attempt;
        synchronized(session){attempt=(Attempt)session.getAttribute("onno.crm.channels.gmail.oauth");session.removeAttribute("onno.crm.channels.gmail.oauth");}
        if(attempt==null||state==null||!MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),attempt.state.getBytes(StandardCharsets.UTF_8))||!attempt.user.equals(principal.getName())||Instant.now().isAfter(attempt.expires))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Gmail sign-in expired or did not match this session; start again");
        String result="error";
        if(error==null&&code!=null&&!code.isBlank())try{bridge.connect(client.exchange(code,attempt.verifier));result="connected";}catch(RuntimeException ignored){}
        return ResponseEntity.status(HttpStatus.SEE_OTHER).header("Location",org.springframework.web.util.UriComponentsBuilder.fromUriString(attempt.returnPath).replaceQueryParam("gmail",result).build().toUriString()).header("Cache-Control","no-store").build();
    }
    private static String random(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
}
