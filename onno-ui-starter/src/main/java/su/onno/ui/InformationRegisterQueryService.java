package su.onno.ui;

import su.onno.access.AccessMode;
import su.onno.access.AccessSubject;
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
 * register junction (see {@link RelatedList}, {@link Junctions}). Entity-level RBAC stays with the
 * callers; the subject's record scope (by dimension) is applied here. Read-only by design for now: a
 * register-backed relationship
 * renders both-direction panels but isn't edited inline (info registers have no generic write
 * REST surface yet).
 */
public class InformationRegisterQueryService {

    private final Jdbi jdbi;
    private final RefResolver refResolver;
    private final RecordScopeCompiler scopes;

    /** A service without record policies (every subject unscoped) — for tests and tools. */
    public InformationRegisterQueryService(MetadataRegistry registry, Jdbi jdbi) {
        this(registry, jdbi, RecordScopeCompiler.unrestricted(registry), new UiAccessService(registry));
    }

    public InformationRegisterQueryService(MetadataRegistry registry, Jdbi jdbi, RecordScopeCompiler scopes,
                                           UiAccessService access) {
        this.jdbi = jdbi;
        this.scopes = scopes;
        this.refResolver = new RefResolver(registry, jdbi, scopes, access);
    }

    /**
     * Register rows whose {@code viaColumn} ref dimension points at {@code parentId} — the read
     * side of a register-backed related-list panel. Ordered by {@code _id} for a stable roster.
     * Refs are resolved (so the {@code display} dimension shows its description), exactly like the
     * catalog join read. {@code viaColumn} must be a real column on {@code desc} (the caller
     * resolves it from the register's scanned dimensions, never from user input) so this stays
     * injection-safe. Information registers carry no soft-delete flag, so every stored row counts.
     */
    public List<Map<String, Object>> relatedRows(AccessSubject subject, InformationRegisterDescriptor desc,
                                                  String viaColumn, UUID parentId) {
        return relatedPage(subject, desc, viaColumn, parentId, null, 100).rows();
    }

    public su.onno.query.KeysetWindow.Page<Map<String, Object>> relatedPage(AccessSubject subject,
            InformationRegisterDescriptor desc, String viaColumn, UUID parentId, String cursor, int limit) {
        ScopeClause scope = scopes.clause(desc.javaClass(), subject, AccessMode.READ, desc.tableName());
        var page = KeysetWindow.collection(jdbi, desc.tableName(), "*", viaColumn + " = :parent" + scope.and(),
                q -> { scope.bind(q); q.bind("parent", parentId); },
                List.of(new su.onno.query.KeysetWindow.Order("_id", false)), cursor, limit,
                "related-info:" + desc.tableName() + ":" + viaColumn + ":" + parentId + ":" + scopes.policies().fingerprint(subject) + ":" + EntityQuerySupport.cursorSubject(subject));
        refResolver.resolveAttributes(page.rows(), allFields(desc), subject);
        return page;
    }

    private static List<AttributeDescriptor> allFields(InformationRegisterDescriptor desc) {
        List<AttributeDescriptor> all = new ArrayList<>(desc.dimensions());
        all.addAll(desc.resources());
        all.addAll(desc.attributes());
        return all;
    }
}
