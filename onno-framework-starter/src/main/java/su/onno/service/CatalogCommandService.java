package su.onno.service;

import su.onno.access.AccessMode;
import su.onno.access.AccessSubject;
import su.onno.history.HistoryActor;
import su.onno.history.HistoryActors;
import su.onno.lifecycle.BeforeDeleteHandler;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.model.CatalogObject;
import su.onno.validation.AttributeValidator;
import su.onno.validation.ValidationErrors;

import org.jdbi.v3.core.Jdbi;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.Map;
import java.util.UUID;

/**
 * Write-side commands for catalogs — create, update, delete, validate — shared by the REST API, MCP,
 * import, batch and actions, so read-only mode, {@link AccessService} write checks and record
 * policies are enforced in one place against the caller's {@link AccessSubject}: an existing record
 * must be inside the write scope (404 otherwise), the written record must still satisfy it (403,
 * rolled back), and every reference written must be readable (422). See {@link RecordWriteGuard}.
 *
 * <p>The record itself is written through {@link EntityStore}, the same Spring Data JDBC path as a
 * typed repository, so numbering, lifecycle hooks, validation, secrets, history and change events
 * behave identically however a record is written. This class only maps the request body onto the
 * typed entity and wraps the save in the caller's access checks. Trusted code writes through the
 * typed repositories, which are unscoped by design.</p>
 */
public class CatalogCommandService {

    private final Jdbi jdbi;
    private final WebProperties properties;
    private final CatalogQueryService query;
    private final AccessService access;
    private final EntityStore store;
    private final AttributeValidator attributeValidator = new AttributeValidator();
    private final EntityBodyMapper mapper;
    private final RecordWriteGuard guard;

    public CatalogCommandService(MetadataRegistry registry, Jdbi jdbi, WebProperties properties,
                                 CatalogQueryService query, AccessService access, EntityStore store) {
        this.jdbi = jdbi;
        this.properties = properties;
        this.query = query;
        this.access = access;
        this.store = store;
        this.mapper = new EntityBodyMapper(registry);
        this.guard = new RecordWriteGuard(registry, query.scopes(), access, jdbi);
    }

    // Each command binds the caller as the history actor for its duration, so the write and anything
    // it triggers on this thread (lifecycle hooks saving other records) is attributed to them.

    public Map<String, Object> create(CatalogDescriptor desc, Map<String, Object> requestBody, AccessSubject subject) {
        return HistoryActors.callAs(HistoryActor.of(subject), () -> createRecord(desc, requestBody, subject));
    }

    public Map<String, Object> update(CatalogDescriptor desc, UUID id, Map<String, Object> requestBody,
                                       AccessSubject subject) {
        return HistoryActors.callAs(HistoryActor.of(subject), () -> updateRecord(desc, id, requestBody, subject));
    }

    public void delete(CatalogDescriptor desc, UUID id, AccessSubject subject) {
        HistoryActors.runAs(HistoryActor.of(subject), () -> deleteRecord(desc, id, subject));
    }

    private Map<String, Object> createRecord(CatalogDescriptor desc, Map<String, Object> requestBody, AccessSubject subject) {
        EntityWriteSupport.requireWritable(properties);
        access.requireWrite(subject, desc);
        Map<String, Object> body = EntityWriteAliases.catalog(desc, requestBody);
        guard.applyDefaults(desc.javaClass(), body, subject);
        requireReadableRefs(desc, body, subject);

        ValidationErrors errors = new ValidationErrors();
        attributeValidator.validate(body, desc.attributes(), errors);
        TabularSectionWriteSupport.validate(desc.tabularSections(), body, errors);
        CatalogObject entity = (CatalogObject) EntityBodyMapper.instantiate(desc.javaClass());
        entity.setId(UUID.randomUUID());
        Object code = body.get("code");
        // Blank leaves an auto-numbered catalog to the numbering callback; otherwise "" is stored.
        entity.setCode(code == null || code.toString().isBlank() ? (desc.autoNumber() ? null : "") : code.toString());
        entity.setDescription(asString(body.getOrDefault("description", "")));
        entity.setFolder(Boolean.TRUE.equals(body.get("folder")));
        entity.setParent(parseUuid(body.get("parent")));
        mapBody(desc, entity, body, true, errors);

        UUID id = save(desc, entity, subject);
        return query.get(subject, desc, id);
    }

    private Map<String, Object> updateRecord(CatalogDescriptor desc, UUID id, Map<String, Object> requestBody,
                                             AccessSubject subject) {
        EntityWriteSupport.requireWritable(properties);
        access.requireWrite(subject, desc);
        guard.requireInScope(desc.javaClass(), desc.tableName(), id, subject, AccessMode.WRITE);
        Map<String, Object> body = EntityWriteAliases.catalog(desc, requestBody);
        guard.preserveRestrictedRefs(desc.tableName(), desc.attributes(), id, body, subject,
                desc.hierarchical() ? desc.javaClass() : null);
        requireReadableRefs(desc, body, subject);
        if (!changesAnything(desc, body)) {
            return query.get(subject, desc, id);
        }

        ValidationErrors errors = new ValidationErrors();
        attributeValidator.validatePartial(body, desc.attributes(), errors);
        TabularSectionWriteSupport.validate(desc.tabularSections(), body, errors);
        CatalogObject entity = load(desc, id);
        if (body.containsKey("code")) entity.setCode(asString(body.get("code")));
        if (body.containsKey("description")) entity.setDescription(asString(body.get("description")));
        if (body.containsKey("folder")) entity.setFolder(Boolean.TRUE.equals(body.get("folder")));
        if (body.containsKey("parent")) entity.setParent(parseUuid(body.get("parent")));
        Object expectedVersion = body.containsKey("version") ? body.get("version") : body.get("_version");
        if (expectedVersion != null) entity.setVersion(parseInt(expectedVersion));
        mapBody(desc, entity, body, false, errors);

        try {
            save(desc, entity, subject);
        } catch (OptimisticLockingFailureException stale) {
            throw new ServiceException(ServiceException.Status.CONFLICT,
                    "Catalog item was changed by another transaction: " + id);
        }
        return query.get(subject, desc, id);
    }

    /**
     * Dry-run the write lifecycle against a form's current values and report every failure without
     * persisting anything — the backend of the form's live (as-you-type) validation. Runs the same
     * checks as {@link #create}/{@link #update}: declarative attribute constraints, then the typed
     * entity's {@code onFilling}/{@code beforeWrite} hooks and {@link su.onno.rules.HasBusinessRules} rules,
     * so a conflict check written in Java surfaces while the user is still on the field. No code is
     * consumed from the numbering sequence and no events fire. Always returns 200 — the outcome is
     * the {@code {valid, fieldErrors, formErrors}} payload.
     */
    public Map<String, Object> validate(CatalogDescriptor desc, UUID id, Map<String, Object> requestBody,
                                        AccessSubject subject) {
        access.requireWrite(subject, desc);
        if (id != null) guard.requireInScope(desc.javaClass(), desc.tableName(), id, subject, AccessMode.WRITE);
        Map<String, Object> body = EntityWriteAliases.catalog(desc, requestBody);
        if (id == null) guard.applyDefaults(desc.javaClass(), body, subject);

        ValidationErrors errors = new ValidationErrors();
        boolean isNew = id == null;
        if (isNew) {
            attributeValidator.validate(body, desc.attributes(), errors);
        } else {
            attributeValidator.validatePartial(body, desc.attributes(), errors);
        }
        CatalogObject entity = isNew ? (CatalogObject) EntityBodyMapper.instantiate(desc.javaClass()) : load(desc, id);
        if (isNew) {
            entity.setId(UUID.randomUUID());
            entity.setCode(asString(body.getOrDefault("code", "")));
            entity.setDescription(asString(body.getOrDefault("description", "")));
            entity.setFolder(Boolean.TRUE.equals(body.get("folder")));
            entity.setParent(parseUuid(body.get("parent")));
        } else {
            if (body.containsKey("code")) entity.setCode(asString(body.get("code")));
            if (body.containsKey("description")) entity.setDescription(asString(body.get("description")));
            if (body.containsKey("folder")) entity.setFolder(Boolean.TRUE.equals(body.get("folder")));
            if (body.containsKey("parent")) entity.setParent(parseUuid(body.get("parent")));
        }
        mapper.applyBody(entity, desc.attributes(), body, errors);
        mapper.applySections(entity, desc.tabularSections(), body, errors);
        mapper.dryRun(entity, isNew, errors);
        TabularSectionWriteSupport.validate(desc.tabularSections(), body, errors);
        return EntityWriteSupport.validationReport(errors);
    }

    /**
     * Whether {@code subject} may change existing record {@code id} — inside its record-policy
     * write scope (entity-level write access is checked by the write itself). Import uses this to
     * turn an upsert onto someone else's record into a row error instead of an overwrite.
     */
    public boolean isWritable(CatalogDescriptor desc, UUID id, AccessSubject subject) {
        return query.inScope(subject, desc, id, AccessMode.WRITE);
    }

    private void deleteRecord(CatalogDescriptor desc, UUID id, AccessSubject subject) {
        EntityWriteSupport.requireWritable(properties);
        access.requireWrite(subject, desc);
        guard.requireInScope(desc.javaClass(), desc.tableName(), id, subject, AccessMode.WRITE);
        // A soft delete, exactly as a repository delete: the domain's BeforeDeleteHandler may veto it
        // (a ValidationException maps to the standard 4xx), then the deletion mark is saved.
        CatalogObject stored = load(desc, id);
        if (stored instanceof BeforeDeleteHandler handler) {
            handler.beforeDelete();
        }
        stored.setDeletionMark(true);
        store.inTransaction(() -> store.save(stored));
    }

    /**
     * Map the body onto the entity. Validation failures found so far are reported together with the
     * entity's business rules, as one response; the save itself re-runs hooks and validation.
     */
    private void mapBody(CatalogDescriptor desc, CatalogObject entity, Map<String, Object> body,
                         boolean isNew, ValidationErrors errors) {
        mapper.applyBody(entity, desc.attributes(), body, errors);
        mapper.applySections(entity, desc.tabularSections(), body, errors);
        if (!errors.isEmpty()) {
            mapper.dryRun(entity, isNew, errors);
            errors.throwIfAny();
        }
    }

    /** Save inside one transaction with the post-write record-scope check, so a violation rolls back. */
    private UUID save(CatalogDescriptor desc, CatalogObject entity, AccessSubject subject) {
        return store.inTransaction(() -> {
            CatalogObject saved = store.save(entity);
            jdbi.useHandle(h -> guard.requirePostImage(h, desc.javaClass(), desc.tableName(), saved.getId(), subject));
            return saved.getId();
        });
    }

    private CatalogObject load(CatalogDescriptor desc, UUID id) {
        CatalogObject entity = (CatalogObject) store.find(desc.javaClass(), id);
        if (entity == null) {
            throw new ServiceException(ServiceException.Status.NOT_FOUND);
        }
        return entity;
    }

    private static boolean changesAnything(CatalogDescriptor desc, Map<String, Object> body) {
        return body.containsKey("code") || body.containsKey("description") || body.containsKey("folder")
                || body.containsKey("parent")
                || desc.attributes().stream().anyMatch(a -> body.containsKey(a.fieldName()))
                || desc.tabularSections().stream().anyMatch(ts -> body.containsKey(ts.name()));
    }

    /** Header refs, line refs and a hierarchical parent must all be readable by the subject. */
    private void requireReadableRefs(CatalogDescriptor desc, Map<String, Object> body, AccessSubject subject) {
        guard.requireReadableRefs(desc.attributes(), body, subject);
        TabularSectionWriteSupport.requireReadableRefs(desc.tabularSections(), body, guard, subject);
        requireReadableParent(desc, body, subject);
    }

    /** A record-scoped subject may only file a record under a parent it can read. */
    private void requireReadableParent(CatalogDescriptor desc, Map<String, Object> body, AccessSubject subject) {
        if (!desc.hierarchical() || !body.containsKey("parent")) return;
        UUID parent = parseUuid(body.get("parent"));
        if (parent != null && query.scopes().isScoped(desc.javaClass(), subject, AccessMode.READ)
                && !query.inScope(subject, desc, parent, AccessMode.READ)) {
            throw new ServiceException(ServiceException.Status.UNPROCESSABLE_ENTITY,
                    "Unknown or inaccessible reference for parent");
        }
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static UUID parseUuid(Object value) {
        if (value == null || "".equals(value)) return null;
        return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }

    private static int parseInt(Object value) {
        return value instanceof Number n ? n.intValue() : Integer.parseInt(value.toString());
    }
}
