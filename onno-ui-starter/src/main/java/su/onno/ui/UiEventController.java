package su.onno.ui;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.security.Principal;

@RestController
@RequestMapping("/api/events")
public class UiEventController {

    private final UiEventPublisher publisher;
    private final UiAccessService access;
    private final CurrentUserResolver currentUser;

    public UiEventController(UiEventPublisher publisher, UiAccessService access, CurrentUserResolver currentUser) {
        this.publisher = publisher;
        this.access = access;
        this.currentUser = currentUser;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(Principal principal) {
        // Capture the subscriber's read-authorities and notification-routing id now, on the request
        // thread (where the SecurityContext is available). Roles filter every broadcast event (#190);
        // the routing id (identity record id, or username when unlinked) addresses per-user
        // notification events to this viewer's streams.
        CurrentUserResolver.CurrentUser me = currentUser.resolve(principal);
        String userId = me.recordId() != null ? me.recordId() : me.username();
        return publisher.subscribe(access.roles(principal), userId);
    }
}
