package su.onno.web;

import su.onno.identity.IdentityLink;

/**
 * The application's identity binding, resolved once: which catalog record a login is (see
 * {@link IdentityLink}). Declared by the app as an optional {@code @Bean IdentityLink}; absent
 * means logins are not linked to a domain record. Consumers ask {@link #identity()}, which may be
 * {@code null}.
 */
public record IdentityBinding(IdentityLink identity) {

    public static IdentityBinding none() {
        return new IdentityBinding(null);
    }
}
