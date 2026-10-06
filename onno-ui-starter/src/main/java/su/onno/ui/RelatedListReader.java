package su.onno.ui;

import su.onno.access.AccessMode;
import su.onno.access.AccessSubject;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.MetadataRegistry;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves and reads the rows of a related-list panel for any owning entity — catalog
 * <em>or</em> document — over any junction — join catalog <em>or</em> information register.
 * Centralizes what the catalog/document REST {@code related} endpoints and the detail-surface
 * preload all need, so the resolution (which panel, which junction, which {@code via} column,
 * may the caller read it) lives in one place rather than being copied per caller.
 *
 * @see RelatedList
 * @see Junctions
 */
public class RelatedListReader {

    private final FieldHintResolver fieldHints;
    private final MetadataRegistry registry;
    private final CatalogQueryService catalogQuery;
    private final InformationRegisterQueryService registerQuery;
    private final UiAccessService access;
    private final RecordAccess recordAccess;

    public RelatedListReader(FieldHintResolver fieldHints, MetadataRegistry registry,
                             CatalogQueryService catalogQuery, InformationRegisterQueryService registerQuery,
                             UiAccessService access, RecordAccess recordAccess) {
        this.fieldHints = fieldHints;
        this.registry = registry;
        this.catalogQuery = catalogQuery;
        this.registerQuery = registerQuery;
        this.access = access;
        this.recordAccess = recordAccess;
    }

    /**
     * Live rows of the panel {@code relatedName} declared on {@code parentClass}, scoped to record
     * {@code parentId} — the REST read path the form widget drives. Throws {@code 404} when no such
     * panel exists, the junction is unregistered, or its {@code via} ref is gone; {@code 403} when
     * the caller may not read the junction. The owning entity's own read access is enforced by the
     * controller before this is called; the owning <em>record</em> must also be inside the caller's
     * record scope (404 otherwise), and junction rows are filtered by the junction's own scope.
     */
    public List<Map<String, Object>> rows(Class<?> parentClass, String parentLogicalName,
                                           String relatedName, UUID parentId, AccessSubject subject,
                                           EntityJsonRepresentation.Mode representation) {
        RelatedList rl = fieldHints.relatedList(parentClass, relatedName);
        if (rl == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No related list '" + relatedName + "' on " + parentLogicalName);
        }
        Junctions.Junction junction = Junctions.resolve(registry, rl.junction());
        if (junction == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Related list '" + relatedName + "' points at an unregistered catalog or register");
        }
        AttributeDescriptor via = Junctions.refField(junction, rl.via());
        if (via == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Related list '" + relatedName + "' has no via ref '" + rl.via() + "'");
        }
        requireRead(subject, junction);
        requireParent(subject, parentClass, parentId);
        List<Map<String, Object>> rows = read(subject, junction, via.columnName(), parentId);
        return junction.isRegister()
                ? rows
                : EntityJsonRepresentation.catalogs(junction.catalog(), rows, representation);
    }

    /**
     * Preloads the read-only rows for every panel that should render on {@code parentClass}'s
     * detail surface, keyed by panel name. Mirrors {@link #rows} but degrades gracefully — a panel
     * that opts out of detail ({@code hideInDetail}), names a junction that vanished, has no
     * {@code via} ref, or the caller may not read is simply skipped, never breaking the surface.
     */
    public Map<String, List<Map<String, Object>>> preloadForDetail(Class<?> parentClass, UUID parentId,
                                                                    AccessSubject subject) {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        for (RelatedList rl : fieldHints.relatedListsFor(parentClass)) {
            if (rl.hideInDetail()) {
                continue;
            }
            Junctions.Junction junction = Junctions.resolve(registry, rl.junction());
            if (junction == null) {
                continue;
            }
            AttributeDescriptor via = Junctions.refField(junction, rl.via());
            if (via == null || !canRead(subject, junction)) {
                continue;
            }
            out.put(rl.name(), read(subject, junction, via.columnName(), parentId));
        }
        return out;
    }

    private List<Map<String, Object>> read(AccessSubject subject, Junctions.Junction junction, String viaColumn,
                                           UUID parentId) {
        return junction.isRegister()
                ? registerQuery.relatedRows(subject, junction.register(), viaColumn, parentId)
                : catalogQuery.relatedRows(subject, junction.catalog(), viaColumn, parentId);
    }

    /** A record-scoped caller may only list the junction rows of a parent record it can read. */
    private void requireParent(AccessSubject subject, Class<?> parentClass, UUID parentId) {
        if (recordAccess != null && recordAccess.policies().entity(parentClass) != null
                && recordAccess.policies().isScoped(parentClass, subject, AccessMode.READ)) {
            recordAccess.require(subject, parentClass, parentId, AccessMode.READ);
        }
    }

    private boolean canRead(AccessSubject subject, Junctions.Junction junction) {
        return junction.isRegister()
                ? access.canRead(subject, junction.register())
                : access.canRead(subject, junction.catalog());
    }

    private void requireRead(AccessSubject subject, Junctions.Junction junction) {
        if (junction.isRegister()) {
            access.requireRead(subject, junction.register());
        } else {
            access.requireRead(subject, junction.catalog());
        }
    }
}
