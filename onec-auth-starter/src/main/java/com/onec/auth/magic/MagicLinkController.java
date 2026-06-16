package com.onec.auth.magic;

import com.onec.auth.OnecAuthProperties;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.Optional;

/**
 * Public endpoints for passwordless magic-link login (in-memory mode):
 * <ul>
 *   <li>{@code POST /api/auth/magic/request} — body {@code {"email": "..."}}; issues + emails a
 *       single-use link. Always answers {@code 202} regardless of whether the address is registered,
 *       so it can't be used to enumerate accounts.</li>
 *   <li>{@code GET /api/auth/magic/verify?token=...} — consumes the token and, on success, establishes
 *       the session the same way {@code /api/auth/login} does, then redirects the browser into the app.
 *       This is a top-level navigation (the link is clicked from an email), so it redirects rather than
 *       returning JSON.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth/magic")
public class MagicLinkController {

    private final MagicLinkService service;
    private final OnecAuthProperties properties;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public MagicLinkController(MagicLinkService service, OnecAuthProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @PostMapping("/request")
    ResponseEntity<Void> request(@RequestBody(required = false) MagicLinkRequest body,
                                 HttpServletRequest request) {
        if (body != null && body.email() != null && !body.email().isBlank()) {
            String origin = ServletUriComponentsBuilder.fromContextPath(request).build().toUriString();
            service.requestLink(body.email(), origin);
        }
        // 202 whether or not an account matched (and even for a blank email): no enumeration signal.
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/verify")
    ResponseEntity<Void> verify(@RequestParam(required = false) String token,
                                HttpServletRequest request,
                                HttpServletResponse response) {
        Optional<Authentication> authentication = service.verify(token);
        if (authentication.isEmpty()) {
            // Invalid/expired/used link — send the user back to the login screen with a hint.
            return redirect("/login?error=link");
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication.get());
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);

        return redirect(properties.getMagicLink().getRedirectPath());
    }

    private static ResponseEntity<Void> redirect(String path) {
        // Same-origin path only — guard against an open redirect from a tampered config value.
        String target = (path != null && path.startsWith("/")) ? path : "/";
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }

    record MagicLinkRequest(String email) {
    }
}
