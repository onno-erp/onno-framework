package com.onec.ui;

import com.onec.metadata.AttributeDescriptor;
import com.onec.metadata.MetadataRegistry;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
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

    public RelatedListReader(FieldHintResolver fieldHints, MetadataRegistry registry,
                             CatalogQueryService catalogQuery, InformationRegisterQueryService registerQuery,
                             UiAccessService access) {
        this.fieldHints = fieldHints;
        this.registry = registry;
        this.catalogQuery = catalogQuery;
        this.registerQuery = registerQuery;
        this.access = access;
    }

    /**
     * Live rows of the panel {@code relatedName} declared on {@code parentClass}, scoped to record
     * {@code parentId} — the REST read path the form widget drives. Throws {@code 404} when no such
     * panel exists, the junction is unregistered, or its {@code via} ref is gone; {@code 403} when
     * the caller may not read the junction. The owning entity's own read access is enforced by the
     * controller before this is called.
     */
    public List<Map<String, Object>> rows(Class<?> parentClass, String parentLogicalName,
                                           String relatedName, UUID parentId, Principal principal) {
        RelatedList rl = fieldHints.relatedList(parentClass, relatedName);
        if (rl == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No related list '" + relatedName + "' on " + parentLogicalName);
        }
        Junctions.Junction junction = Junctions.resolve(registry, rl.joinCatalog());
        if (junction == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Related list '" + relatedName + "' points at an unregistered catalog or register");
        }
        AttributeDescriptor via = Junctions.refField(junction, rl.via());
        if (via == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Related list '" + relatedName + "' has no via ref '" + rl.via() + "'");
        }
        requireRead(principal, junction);
        return read(junction, via.columnName(), parentId);
    }

    /**
     * Preloads the read-only rows for every panel that should render on {@code parentClass}'s
     * detail surface, keyed by panel name. Mirrors {@link #rows} but degrades gracefully — a panel
     * that opts out of detail ({@code hideInDetail}), names a junction that vanished, has no
     * {@code via} ref, or the caller may not read is simply skipped, never breaking the surface.
     */
    public Map<String, List<Map<String, Object>>> preloadForDetail(Class<?> parentClass, UUID parentId,
                                                                    Principal principal) {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        for (RelatedList rl : fieldHints.relatedListsFor(parentClass)) {
            if (rl.hideInDetail()) {
                continue;
            }
            Junctions.Junction junction = Junctions.resolve(registry, rl.joinCatalog());
            if (junction == null) {
                continue;
            }
            AttributeDescriptor via = Junctions.refField(junction, rl.via());
            if (via == null || !canRead(principal, junction)) {
                continue;
            }
            out.put(rl.name(), read(junction, via.columnName(), parentId));
        }
        return out;
    }

    private List<Map<String, Object>> read(Junctions.Junction junction, String viaColumn, UUID parentId) {
        return junction.isRegister()
                ? registerQuery.relatedRows(junction.register(), viaColumn, parentId)
                : catalogQuery.relatedRows(junction.catalog(), viaColumn, parentId);
    }

    private boolean canRead(Principal principal, Junctions.Junction junction) {
        return junction.isRegister()
                ? access.canRead(principal, junction.register())
                : access.canRead(principal, junction.catalog());
    }

    private void requireRead(Principal principal, Junctions.Junction junction) {
        if (junction.isRegister()) {
            access.requireRead(principal, junction.register());
        } else {
            access.requireRead(principal, junction.catalog());
        }
    }
}
