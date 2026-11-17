package su.onno.service;

import su.onno.access.AccessSubject;
import su.onno.access.AccessSubjectContributor;

import org.springframework.util.ClassUtils;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Resolves the {@link AccessSubject} for an authenticated request: the login and roles (as
 * {@link AccessService#roles(Principal)} reads them), the identity-linked record from
 * the {@code IdentityLink} via {@link CurrentUserResolver}, and attributes from every
 * {@link AccessSubjectContributor} bean.
 *
 * <p>The result is cached on the current request, so a handler that resolves the subject several
 * times (or a page composed of many widgets) pays for one identity lookup. Off a request
 * thread nothing is cached; callers capture the subject once and pass it along instead.
 *
 * <p>A request without an authenticated user resolves to an anonymous user with no roles — never to
 * {@link AccessSubject#system()}, which only trusted code passes explicitly.
 */
public class AccessSubjectResolver {

    private static final String CACHE_KEY = AccessSubjectResolver.class.getName() + ".subject";

    private final AccessService access;
    private final CurrentUserResolver users;
    private final Supplier<List<AccessSubjectContributor>> contributors;

    public AccessSubjectResolver(AccessService access, CurrentUserResolver users,
                                 Supplier<List<AccessSubjectContributor>> contributors) {
        this.access = access;
        this.users = users;
        this.contributors = contributors;
    }

    /** The subject for {@code principal} (or the request's authentication when it is {@code null}). */
    public AccessSubject.User resolve(Principal principal) {
        String username = access.username(principal);
        String key = CACHE_KEY + ":" + username;
        if (WEB && RequestCache.get(key) instanceof AccessSubject.User cached) {
            return cached;
        }
        AccessSubject.User subject = build(username, access.roles(principal));
        if (WEB) {
            RequestCache.put(key, subject);
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

    /** spring-web is optional here: a non-web app (a worker, an MCP-only node) simply skips the cache. */
    private static final boolean WEB = ClassUtils.isPresent(
            "org.springframework.web.context.request.RequestContextHolder",
            AccessSubjectResolver.class.getClassLoader());

    /** Request-scoped cache; only loaded when spring-web is present. */
    private static final class RequestCache {
        static Object get(String key) {
            var request = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            return request == null ? null
                    : request.getAttribute(key, org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
        }

        static void put(String key, Object value) {
            var request = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (request != null) {
                request.setAttribute(key, value, org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
            }
        }
    }
}
