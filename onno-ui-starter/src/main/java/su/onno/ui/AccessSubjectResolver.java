package su.onno.ui;

import su.onno.access.AccessSubject;
import su.onno.access.AccessSubjectContributor;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Resolves the {@link AccessSubject} for an authenticated request: the login and roles (as
 * {@link UiAccessService#roles(Principal)} reads them), the identity-linked record from
 * {@code Layout.identity(...)} via {@link CurrentUserResolver}, and attributes from every
 * {@link AccessSubjectContributor} bean.
 *
 * <p>The result is cached on the current request, so a handler that resolves the subject several
 * times (or a DivKit surface composed of many widgets) pays for one identity lookup. Off a request
 * thread nothing is cached; callers capture the subject once and pass it along instead.
 *
 * <p>A request without an authenticated user resolves to an anonymous user with no roles — never to
 * {@link AccessSubject#system()}, which only trusted code passes explicitly.
 */
public class AccessSubjectResolver {

    private static final String CACHE_KEY = AccessSubjectResolver.class.getName() + ".subject";

    private final UiAccessService access;
    private final CurrentUserResolver users;
    private final Supplier<List<AccessSubjectContributor>> contributors;

    public AccessSubjectResolver(UiAccessService access, CurrentUserResolver users,
                                 Supplier<List<AccessSubjectContributor>> contributors) {
        this.access = access;
        this.users = users;
        this.contributors = contributors;
    }

    /** The subject for {@code principal} (or the request's authentication when it is {@code null}). */
    public AccessSubject.User resolve(Principal principal) {
        String username = access.username(principal);
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        String key = CACHE_KEY + ":" + username;
        if (request != null && request.getAttribute(key, RequestAttributes.SCOPE_REQUEST)
                instanceof AccessSubject.User cached) {
            return cached;
        }
        AccessSubject.User subject = build(username, access.roles(principal));
        if (request != null) {
            request.setAttribute(key, subject, RequestAttributes.SCOPE_REQUEST);
        }
        return subject;
    }

    /** Build a subject for a known login and role set, e.g. for an MCP exchange or a test. */
    public AccessSubject.User build(String username, Set<String> roles) {
        AccessSubject.User subject = new AccessSubject.User(username, roles, null, Map.of());
        if (username != null && !username.isBlank() && users != null) {
            String recordId = users.resolve(() -> username).recordId();
            if (recordId != null) {
                subject = subject.withRecordId(UUID.fromString(recordId));
            }
        }
        for (AccessSubjectContributor contributor : contributors.get()) {
            Map<String, ?> extra = contributor.contribute(subject);
            if (extra != null && !extra.isEmpty()) {
                subject = subject.withAttributes(extra);
            }
        }
        return subject;
    }
}
