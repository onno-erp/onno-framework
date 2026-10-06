package su.onno.ui;

import su.onno.access.AccessSubject;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Lets a controller method declare an {@link AccessSubject} (or {@link AccessSubject.User})
 * parameter and receive the request's resolved subject. A web request always resolves to a user —
 * possibly an anonymous one with no roles — never to {@link AccessSubject#system()}.
 */
final class AccessSubjectArgumentResolver implements HandlerMethodArgumentResolver {

    private final java.util.function.Function<java.security.Principal, AccessSubject> resolve;

    AccessSubjectArgumentResolver(ObjectProvider<AccessSubjectResolver> resolver) {
        this.resolve = principal -> resolver.getObject().resolve(principal);
    }

    private AccessSubjectArgumentResolver(java.util.function.Function<java.security.Principal, AccessSubject> resolve) {
        this.resolve = resolve;
    }

    /** A resolver that always yields {@code subject} — for standalone MockMvc tests. */
    static AccessSubjectArgumentResolver fixed(AccessSubject subject) {
        return new AccessSubjectArgumentResolver(principal -> subject);
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        Class<?> type = parameter.getParameterType();
        return type == AccessSubject.class || type == AccessSubject.User.class;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        return resolve.apply(webRequest.getUserPrincipal());
    }
}
