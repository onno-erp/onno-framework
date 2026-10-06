package su.onno.access;

import java.util.Map;

/**
 * Adds app-defined attributes to a signed-in {@link AccessSubject.User} — resolved once per request,
 * then available to {@link RecordScope}s as {@link Subject#attribute(String)}.
 *
 * <pre>{@code
 * @Bean
 * AccessSubjectContributor ownerId(OwnerDirectory owners) {
 *     return user -> Map.of("ownerId", owners.ownerIdFor(user.username()));
 * }
 * }</pre>
 *
 * <p>A contributor sees the user built so far (username, roles, identity record id, attributes of
 * earlier contributors). Returning {@code null} or an empty map contributes nothing; an attribute
 * that is never contributed makes every predicate that reads it false (fail closed).
 */
@FunctionalInterface
public interface AccessSubjectContributor {

    Map<String, ?> contribute(AccessSubject.User user);
}
