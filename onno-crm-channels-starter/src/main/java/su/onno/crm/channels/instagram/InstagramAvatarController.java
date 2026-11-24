package su.onno.crm.channels.instagram;

import java.security.Principal;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Authenticated cached profile pictures; Instagram's signed CDN URLs never leave the server. */
@RestController
@ConditionalOnProperty(name="onno.crm.channels.instagram.enabled",havingValue="true")
public class InstagramAvatarController {
    private final InstagramAvatars avatars;
    public InstagramAvatarController(InstagramAvatars avatars){this.avatars=avatars;}
    @GetMapping("/api/crm/instagram/avatars/{id}")
    public ResponseEntity<byte[]> avatar(@PathVariable UUID id,Principal principal){
        var picture=avatars.read(id,principal);
        MediaType type;try{type=MediaType.parseMediaType(picture.contentType());}catch(RuntimeException e){type=MediaType.IMAGE_JPEG;}
        if(!"image".equals(type.getType()))type=MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().contentType(type).header("X-Content-Type-Options","nosniff")
                .cacheControl(CacheControl.noCache().cachePrivate()).body(picture.content());
    }
}
