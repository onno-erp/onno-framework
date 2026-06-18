package su.onno.ui.comments;

import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.ui.CatalogQueryService;
import su.onno.ui.DocumentQueryService;
import su.onno.ui.UiAccessService;

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
 * The {@code @}-mention typeahead source: {@code GET /api/mentions?q=…} fans the query across every
 * catalog and document the caller can read and returns a unified, ranked suggestion list. It is the
 * cross-entity sibling of the per-entity {@code /api/list/{kind}/{name}?q=} ref picker — the same
 * case-insensitive search, just spread over all readable entities with a per-entity and a total cap
 * so one keystroke can never fan into an unbounded scan.
 *
 * <p>Read access is the existing per-entity gate ({@link UiAccessService}); deny-by-default means a
 * caller only ever sees suggestions from entities they could already open, so the typeahead can't be
 * used to enumerate hidden records. Gated on {@code onno.comments.mentions.enabled}.
 */
@RestController
public class MentionController {

    private final MetadataRegistry registry;
    private final CatalogQueryService catalogQuery;
    private final DocumentQueryService documentQuery;
    private final UiAccessService access;
    private final CommentProperties properties;

    public MentionController(MetadataRegistry registry, CatalogQueryService catalogQuery,
                             DocumentQueryService documentQuery, UiAccessService access,
                             CommentProperties properties) {
        this.registry = registry;
        this.catalogQuery = catalogQuery;
        this.documentQuery = documentQuery;
        this.access = access;
        this.properties = properties;
    }

    @GetMapping("/api/mentions")
    public List<Map<String, Object>> search(@RequestParam(name = "q", required = false) String q,
                                            Principal principal) {
        if (!properties.getMentions().isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Mentions are not enabled");
        }
        String query = q == null ? "" : q.trim();
        int perEntity = Math.max(1, properties.getMentions().getPerEntityLimit());
        int total = Math.max(1, properties.getMentions().getSuggestionLimit());

        List<Suggestion> hits = new ArrayList<>();
        for (CatalogDescriptor desc : registry.allCatalogs()) {
            if (!access.canRead(principal, desc)) {
                continue;
            }
            String entity = desc.logicalName();
            String name = Mentions.routeName(entity);
            String avatarColumn = avatarColumn(desc);
            for (Map<String, Object> row : catalogQuery.search(desc, query, perEntity)) {
                String display = firstNonBlank(str(row, "_description"), str(row, "_code"));
                if (display == null) {
                    continue;
                }
                hits.add(new Suggestion("catalogs", name, entity, str(row, "_id"), display,
                        avatarColumn == null ? null : str(row, avatarColumn)));
            }
        }
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
                hits.add(new Suggestion("documents", name, entity, str(row, "_id"), display, null));
            }
        }

        String lower = query.toLowerCase(Locale.ROOT);
        hits.sort(Comparator
                // Prefix matches first (what the user is most likely reaching for), then alphabetical.
                .comparing((Suggestion s) -> !s.display().toLowerCase(Locale.ROOT).startsWith(lower))
                .thenComparing(s -> s.display().toLowerCase(Locale.ROOT)));

        return hits.stream().limit(total).map(Suggestion::toJson).toList();
    }

    private record Suggestion(String kind, String name, String entity, String id, String display,
                              String avatarUrl) {
        Map<String, Object> toJson() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("kind", kind);
            out.put("name", name);
            out.put("entity", entity);
            out.put("id", id);
            out.put("display", display);
            out.put("avatarUrl", avatarUrl == null || avatarUrl.isBlank() ? null : avatarUrl);
            return out;
        }
    }

    private static String avatarColumn(CatalogDescriptor desc) {
        return desc.attributes().stream()
                .map(AttributeDescriptor::columnName)
                .filter(c -> c.equalsIgnoreCase("avatar_url"))
                .findFirst().orElse(null);
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
