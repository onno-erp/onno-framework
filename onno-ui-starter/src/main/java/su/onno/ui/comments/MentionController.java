package su.onno.ui.comments;

import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.ui.CatalogQueryService;
import su.onno.ui.DocumentQueryService;
import su.onno.ui.UiAccessService;
import su.onno.ui.UiLayout;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The comment mention/reference typeahead source: {@code GET /api/mentions?q=…&kind=…} fans the query
 * across readable catalogs and/or documents and returns a unified, ranked suggestion list. It is the
 * cross-entity sibling of the per-entity {@code /api/list/{kind}/{name}?q=} ref picker — the same
 * case-insensitive search, just spread over all readable entities with a per-entity and a total cap
 * so one keystroke can never fan into an unbounded scan.
 *
 * <p>Read access is the existing per-entity gate ({@link UiAccessService}); deny-by-default means a
 * caller only ever sees suggestions from entities they could already open, so the typeahead can't be
 * used to enumerate hidden records. The optional {@code kind=people|catalogs|documents} filter lets
 * the UI use {@code @} for people mentions and {@code #} for document references: {@code people}
 * narrows to the identity catalog (the one {@code Layout.identity(...)} links a login to — the same
 * catalog whose mentions raise notifications), falling back to all catalogs when no identity link is
 * configured. Gated on {@code onno.comments.mentions.enabled}.
 */
@RestController
public class MentionController {

    private final MetadataRegistry registry;
    private final CatalogQueryService catalogQuery;
    private final DocumentQueryService documentQuery;
    private final UiAccessService access;
    private final CommentProperties properties;
    private final UiLayout layout;
    private final MentionResolver resolver;

    public MentionController(MetadataRegistry registry, CatalogQueryService catalogQuery,
                             DocumentQueryService documentQuery, UiAccessService access,
                             CommentProperties properties, UiLayout layout, MentionResolver resolver) {
        this.registry = registry;
        this.catalogQuery = catalogQuery;
        this.documentQuery = documentQuery;
        this.access = access;
        this.properties = properties;
        this.layout = layout;
        this.resolver = resolver;
    }

    @GetMapping("/api/mentions")
    public List<Map<String, Object>> search(@RequestParam(name = "q", required = false) String q,
                                            @RequestParam(name = "kind", required = false) String kind,
                                            Principal principal) {
        if (!properties.getMentions().isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Mentions are not enabled");
        }
        String query = q == null ? "" : q.trim();
        String lower = query.toLowerCase(Locale.ROOT);
        int perEntity = Math.max(1, properties.getMentions().getPerEntityLimit());
        int total = Math.max(1, properties.getMentions().getSuggestionLimit());

        // `people` narrows the catalog sweep to the identity catalog; without an identity link every
        // catalog record is potentially "someone", so degrade to the plain catalogs behaviour.
        Class<?> identityClass = layout == null || layout.identity() == null ? null : layout.identity().javaClass();
        boolean peopleOnly = "people".equals(kind) && identityClass != null;

        List<Suggestion> hits = new ArrayList<>();
        if (kind == null || kind.isBlank() || "catalogs".equals(kind) || "people".equals(kind)) {
            for (CatalogDescriptor desc : registry.allCatalogs()) {
                if (peopleOnly && !desc.javaClass().equals(identityClass)) {
                    continue;
                }
                if (!access.canRead(principal, desc)) {
                    continue;
                }
                String entity = desc.logicalName();
                String name = Mentions.routeName(entity);
                String avatarColumn = avatarColumn(desc);
                String hintColumn = column(desc, "email");
                for (Map<String, Object> row : catalogQuery.search(desc, query, perEntity)) {
                    String display = firstNonBlank(str(row, "_description"), str(row, "_code"));
                    if (display == null) {
                        continue;
                    }
                    // Secondary line: the code when it's what the query matched (typing "SUP-…" must
                    // show which row matched), else an email when the catalog models one, else the code.
                    String code = str(row, "_code");
                    String codeHint = code == null || code.equals(display) ? null : code;
                    boolean codeMatched = codeHint != null && !lower.isBlank()
                            && codeHint.toLowerCase(Locale.ROOT).contains(lower);
                    String hint = codeMatched ? codeHint
                            : firstNonBlank(hintColumn == null ? null : str(row, hintColumn), codeHint);
                    hits.add(new Suggestion("catalogs", name, entity, str(row, "_id"), display,
                            avatarColumn == null ? null : str(row, avatarColumn), hint, code));
                }
            }
        }
        if (kind == null || kind.isBlank() || "documents".equals(kind)) {
            for (DocumentDescriptor desc : registry.allDocuments()) {
                if (!access.canRead(principal, desc)) {
                    continue;
                }
                String entity = desc.logicalName();
                String name = Mentions.routeName(entity);
                for (Map<String, Object> row : documentQuery.search(desc, query, perEntity)) {
                    String display = str(row, "_number");
                    if (display == null) {
                        continue;
                    }
                    hits.add(new Suggestion("documents", name, entity, str(row, "_id"), display, null,
                            isoDate(str(row, "_date")), null));
                }
            }
        }

        // Rank: display prefix > code prefix (a record found by its "SUP-…" code must beat records
        // that merely match through a referencing attribute) > alphabetical.
        hits.sort(Comparator
                .comparingInt((Suggestion s) -> {
                    if (s.display().toLowerCase(Locale.ROOT).startsWith(lower)) {
                        return 0;
                    }
                    if (s.code() != null && s.code().toLowerCase(Locale.ROOT).startsWith(lower)) {
                        return 1;
                    }
                    return 2;
                })
                .thenComparing(s -> s.display().toLowerCase(Locale.ROOT)));

        return hits.stream().limit(total).map(Suggestion::toJson).toList();
    }

    /**
     * Resolve one {@code (kind, name, id)} triple to its live display — what the compose box calls
     * when an internal record URL is pasted, to swap the link for a mention chip. The same
     * per-viewer read gate as thread rendering applies ({@link MentionResolver}): an unreadable or
     * unknown record comes back {@code readable=false} with no display, and the client leaves the
     * pasted text alone. {@code person} marks records of the identity catalog, so the client can
     * pick the {@code @} marker for people and {@code #} for everything else.
     */
    @GetMapping("/api/mentions/resolve")
    public Map<String, Object> resolve(@RequestParam("kind") String kind,
                                       @RequestParam("name") String name,
                                       @RequestParam("id") String id,
                                       Principal principal) {
        if (!properties.getMentions().isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Mentions are not enabled");
        }
        if (!"catalogs".equals(kind) && !"documents".equals(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "kind must be catalogs or documents");
        }
        java.util.UUID uuid;
        try {
            uuid = java.util.UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id must be a UUID");
        }
        MentionRef ref = new MentionRef(kind, name, uuid);
        Map<String, Object> out = resolver.resolve(List.of(ref), principal).get(0).toJson();
        out.put("person", "catalogs".equals(kind) && isIdentityRoute(name));
        return out;
    }

    /** True when {@code name} is the route of the identity catalog ({@code Layout.identity(...)}). */
    private boolean isIdentityRoute(String name) {
        Class<?> identityClass = layout == null || layout.identity() == null ? null : layout.identity().javaClass();
        if (identityClass == null) {
            return false; // no identity link — nothing is "a person"
        }
        return registry.allCatalogs().stream()
                .filter(c -> c.javaClass().equals(identityClass))
                .findFirst()
                .map(c -> Mentions.routeName(c.logicalName()).equalsIgnoreCase(name))
                .orElse(false);
    }

    /** {@code code} is ranking-only (the catalog {@code _code} the query may have matched) — not serialized. */
    private record Suggestion(String kind, String name, String entity, String id, String display,
                              String avatarUrl, String hint, String code) {
        Map<String, Object> toJson() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("kind", kind);
            out.put("name", name);
            out.put("entity", entity);
            out.put("id", id);
            out.put("display", display);
            out.put("avatarUrl", avatarUrl == null || avatarUrl.isBlank() ? null : avatarUrl);
            out.put("hint", hint == null || hint.isBlank() ? null : hint);
            return out;
        }
    }

    private static String avatarColumn(CatalogDescriptor desc) {
        return column(desc, "avatar_url");
    }

    /** A catalog's column with this exact (case-insensitive) name, or null when the app didn't model one. */
    private static String column(CatalogDescriptor desc, String name) {
        return desc.attributes().stream()
                .map(AttributeDescriptor::columnName)
                .filter(c -> c.equalsIgnoreCase(name))
                .findFirst().orElse(null);
    }

    /** The {@code yyyy-MM-dd} prefix of a document's {@code _date} value, or null when absent/odd-shaped. */
    private static String isoDate(String timestamp) {
        if (timestamp == null || timestamp.length() < 10) {
            return null;
        }
        String date = timestamp.substring(0, 10);
        return date.matches("\\d{4}-\\d{2}-\\d{2}") ? date : null;
    }

    /** Case-tolerant row accessor (jdbi map keys can arrive in either case across drivers). */
    private static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v == null) {
            v = row.get(key.toUpperCase(Locale.ROOT));
        }
        if (v == null) {
            v = row.get(key.toLowerCase(Locale.ROOT));
        }
        return v == null ? null : v.toString();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b != null && !b.isBlank() ? b : null;
    }
}
