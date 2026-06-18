package su.onno.ui;

import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.InformationRegisterDescriptor;
import su.onno.metadata.MetadataRegistry;

import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-side queries for information registers, used to drive related-list panels backed by a
 * register junction (see {@link RelatedList}, {@link Junctions}). Pure data access — access
 * control stays with the callers. Read-only by design for now: a register-backed relationship
 * renders both-direction panels but isn't edited inline (info registers have no generic write
 * REST surface yet).
 */
public class InformationRegisterQueryService {

    private final Jdbi jdbi;
    private final RefResolver refResolver;

    public InformationRegisterQueryService(MetadataRegistry registry, Jdbi jdbi) {
        this.jdbi = jdbi;
        this.refResolver = new RefResolver(registry, jdbi);
    }

    /**
     * Register rows whose {@code viaColumn} ref dimension points at {@code parentId} — the read
     * side of a register-backed related-list panel. Ordered by {@code _id} for a stable roster.
     * Refs are resolved (so the {@code display} dimension shows its description), exactly like the
     * catalog join read. {@code viaColumn} must be a real column on {@code desc} (the caller
     * resolves it from the register's scanned dimensions, never from user input) so this stays
     * injection-safe. Information registers carry no soft-delete flag, so every stored row counts.
     */
    public List<Map<String, Object>> relatedRows(InformationRegisterDescriptor desc, String viaColumn,
                                                  UUID parentId) {
        List<Map<String, Object>> rows = jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM " + desc.tableName() +
                                " WHERE " + viaColumn + " = :parent ORDER BY _id")
                        .bind("parent", parentId)
                        .mapToMap()
                        .list());
        refResolver.resolveAttributes(rows, allFields(desc));
        return rows;
    }

    private static List<AttributeDescriptor> allFields(InformationRegisterDescriptor desc) {
        List<AttributeDescriptor> all = new ArrayList<>(desc.dimensions());
        all.addAll(desc.resources());
        all.addAll(desc.attributes());
        return all;
    }
}
