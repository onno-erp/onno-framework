package su.onno.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Resolves which {@link UiLayout.Profile} a user sees, by matching their roles
 * against each named profile's target roles. Pure presentation/curation: this
 * decides what a cooperating client renders, never what data access is granted —
 * that stays enforced per data endpoint. Callers pass an already-normalized role
 * set (upper-case, {@code ROLE_} stripped); profile role tokens are normalized
 * here so the two sides compare consistently.
 */
public class UiProfileResolver {

    public record Resolution(UiLayout.Profile profile, List<UiLayout.Profile> switchable) {}

    /**
     * Pick the best profile for a user: the highest-priority named profile whose
     * roles intersect {@code roles}, falling back to the default profile when
     * none match. Also returns the full set the user may switch between.
     */
    public Resolution resolve(UiLayout layout, Set<String> roles) {
        UiLayout.Profile best = null;
        for (UiLayout.Profile p : layout.profiles()) {
            if (!matches(p, roles)) continue;
            if (best == null || p.priority() > best.priority()) {
                best = p;
            }
        }
        return new Resolution(best != null ? best : layout.defaultProfile(), switchable(layout, roles));
    }

    /**
     * The profiles a user may switch between. A user who matches one or more
     * named profiles is confined to those — they cannot fall back to the default
     * back-office. The default profile is offered only to users who match no
     * named profile at all. This is what locks a curated persona (e.g. a cleaner)
     * out of every other view: their role grants exactly one profile and nothing
     * else is switchable.
     */
    public List<UiLayout.Profile> switchable(UiLayout layout, Set<String> roles) {
        List<UiLayout.Profile> matched = new ArrayList<>();
        for (UiLayout.Profile p : layout.profiles()) {
            if (matches(p, roles)) {
                matched.add(p);
            }
        }
        return matched.isEmpty() ? List.of(layout.defaultProfile()) : matched;
    }

    /**
     * Look up a profile by id for an explicit {@code ?profile=} switch. Falls back
     * to the default profile for null/blank/"default"/unknown ids. Note: this does
     * not verify the user is eligible — callers that honor a client-supplied id
     * should intersect with {@link #switchable} first.
     */
    public UiLayout.Profile byId(UiLayout layout, String id) {
        if (id == null || id.isBlank() || "default".equals(id)) {
            return layout.defaultProfile();
        }
        return layout.profiles().stream()
                .filter(p -> p.id().equals(id))
                .findFirst()
                .orElse(layout.defaultProfile());
    }

    private static boolean matches(UiLayout.Profile profile, Set<String> roles) {
        if (profile.roles().isEmpty()) {
            return true;
        }
        return profile.roles().stream()
                .map(UiProfileResolver::normalize)
                .anyMatch(roles::contains);
    }

    private static String normalize(String role) {
        String trimmed = role == null ? "" : role.trim();
        if (trimmed.toUpperCase().startsWith("ROLE_")) {
            trimmed = trimmed.substring(5);
        }
        return trimmed.toUpperCase();
    }
}
