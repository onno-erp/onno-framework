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

    public UiEventController(UiEventPublisher publisher, UiAccessService access) {
        this.publisher = publisher;
        this.access = access;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(Principal principal) {
        // Capture the subscriber's read-authorities now, on the request thread (where the
        // SecurityContext is available); the publisher filters every event against them (#190).
        return publisher.subscribe(access.roles(principal));
    }
}
