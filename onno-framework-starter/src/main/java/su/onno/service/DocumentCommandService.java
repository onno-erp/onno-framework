package su.onno.service;

import su.onno.access.AccessMode;
import su.onno.access.AccessSubject;
import su.onno.history.HistoryActor;
import su.onno.history.HistoryActors;
import su.onno.lifecycle.BeforeDeleteHandler;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.model.DocumentObject;
import su.onno.period.PeriodCloseService;
import su.onno.period.PeriodOperation;
import su.onno.posting.PostingPreview;
import su.onno.posting.PostingService;
import su.onno.validation.AttributeValidator;
import su.onno.validation.ValidationErrors;

import org.jdbi.v3.core.Jdbi;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Write-side commands for documents — create, update, delete, post, unpost, validate — shared by the
 * REST API, MCP, import and actions, so access control and read-only checks are enforced in one
 * place.
 *
 * <p>Every mutating method enforces {@code requireWritable()} (global read-only mode),
 * {@link AccessService#requireWrite} (posting and unposting additionally the document's
 * {@code @AccessControl(postRoles/unpostRoles)} gate) and the record policies against the caller's
 * {@link AccessSubject}: an existing document must be inside the write scope (404 otherwise), the
 * written document must still satisfy it (403, rolled back), and every reference written — header
 * or line — must be readable (422). Tabular sections inherit the document's scope. See
 * {@link RecordWriteGuard}.
 *
 * <p>The document is written and loaded through {@link EntityStore}, the same Spring Data JDBC path as
 * a typed repository, so numbering, lifecycle hooks, validation, secrets, period close, history and
 * change events behave identically however a document is written. Trusted code writes through the
 * typed repositories, which are unscoped by design.</p>
 */
public class DocumentCommandService {

    private final Jdbi jdbi;
    private final WebProperties properties;
    private final PostingService postingService;
    private final DocumentQueryService query;
    private final AccessService access;
    private final EntityStore store;
    private final AttributeValidator attributeValidator = new AttributeValidator();
    private final EntityBodyMapper mapper;
    private final RecordWriteGuard guard;
    private PeriodCloseService periodClose;

    public DocumentCommandService(MetadataRegistry registry, Jdbi jdbi, WebProperties properties,
                                  PostingService postingService, DocumentQueryService query,
                                  AccessService access, EntityStore store) {
        this.jdbi = jdbi;
        this.properties = properties;
        this.postingService = postingService;
        this.query = query;
        this.access = access;
        this.store = store;
        this.mapper = new EntityBodyMapper(registry);
        this.guard = new RecordWriteGuard(registry, query.scopes(), access, jdbi);
    }

    /**
     * Checks a delete against period close before a posted document is unposted. Create and update
     * are checked by the save itself (the store's before-convert callback).
     */
    public void setPeriodCloseService(PeriodCloseService periodClose) {
        this.periodClose = periodClose;
    }

    // Each command binds the caller as the history actor for its duration, so the write, the
    // post/unpost events and anything a lifecycle hook saves on this thread is attributed to them.

    public Map<String, Object> create(DocumentDescriptor desc, Map<String, Object> requestBody, AccessSubject subject) {
        return HistoryActors.callAs(HistoryActor.of(subject), () -> createRecord(desc, requestBody, subject));
    }

    public Map<String, Object> update(DocumentDescriptor desc, UUID id, Map<String, Object> requestBody,
                                       AccessSubject subject) {
        return HistoryActors.callAs(HistoryActor.of(subject), () -> updateRecord(desc, id, requestBody, subject));
    }

    public Map<String, Object> post(DocumentDescriptor desc, UUID id, AccessSubject subject) {
        return HistoryActors.callAs(HistoryActor.of(subject), () -> postRecord(desc, id, subject));
    }

    public Map<String, Object> unpost(DocumentDescriptor desc, UUID id, AccessSubject subject) {
        return HistoryActors.callAs(HistoryActor.of(subject), () -> unpostRecord(desc, id, subject));
    }

    public void delete(DocumentDescriptor desc, UUID id, AccessSubject subject) {
        HistoryActors.runAs(HistoryActor.of(subject), () -> deleteRecord(desc, id, subject));
    }

    private Map<String, Object> createRecord(DocumentDescriptor desc, Map<String, Object> requestBody, AccessSubject subject) {
        EntityWriteSupport.requireWritable(properties);
        access.requireWrite(subject, desc);
        Map<String, Object> body = EntityWriteAliases.document(desc, requestBody);
        guard.applyDefaults(desc.javaClass(), body, subject);
        requireReadableRefs(desc, body, subject);

        ValidationErrors errors = new ValidationErrors();
        Object date = body.get("date");
        LocalDateTime parsedDate = date == null ? LocalDateTime.now() : parseWriteDate(date, errors);
        attributeValidator.validate(body, desc.attributes(), errors);
        DocumentObject doc = (DocumentObject) EntityBodyMapper.instantiate(desc.javaClass());
        doc.setId(UUID.randomUUID());
        Object number = body.get("number");
        // Blank leaves an auto-numbered document to the numbering callback; otherwise "" is stored.
        doc.setNumber(number == null || number.toString().isBlank()
                ? (desc.autoNumber() ? null : "") : number.toString());
        if (parsedDate != null) doc.setDate(parsedDate);
        mapBody(desc, doc, body, true, errors);

        UUID id = save(desc, doc, subject);
        return query.get(subject, desc, id);
    }

    private Map<String, Object> updateRecord(DocumentDescriptor desc, UUID id, Map<String, Object> requestBody,
                                             AccessSubject subject) {
        EntityWriteSupport.requireWritable(properties);
        access.requireWrite(subject, desc);
        guard.requireInScope(desc.javaClass(), desc.tableName(), id, subject, AccessMode.WRITE);
        Map<String, Object> body = EntityWriteAliases.document(desc, requestBody);
        guard.preserveRestrictedRefs(desc.tableName(), desc.attributes(), id, body, subject, null);
        requireReadableRefs(desc, body, subject);
        if (!changesAnything(desc, body)) {
            return query.get(subject, desc, id);
        }

        ValidationErrors errors = new ValidationErrors();
        LocalDateTime parsedDate = body.containsKey("date") ? parseWriteDate(body.get("date"), errors) : null;
        attributeValidator.validatePartial(body, desc.attributes(), errors);
        DocumentObject doc = load(desc, id);
        if (body.containsKey("number")) doc.setNumber(asString(body.get("number")));
        if (parsedDate != null) doc.setDate(parsedDate);
        Object expectedVersion = body.containsKey("version") ? body.get("version") : body.get("_version");
        if (expectedVersion != null) doc.setVersion(parseInt(expectedVersion));
        mapBody(desc, doc, body, false, errors);

        try {
            save(desc, doc, subject);
        } catch (OptimisticLockingFailureException stale) {
            throw new ServiceException(ServiceException.Status.CONFLICT,
                    "Document was changed by another transaction: " + id);
        }
        return query.get(subject, desc, id);
    }

    /**
     * Dry-run the write lifecycle against a form's current values and report every failure without
     * persisting anything — the backend of the form's live (as-you-type) validation. Runs the same
     * checks as {@link #create}/{@link #update}: declarative attribute constraints, then the typed
     * document's {@code onFilling}/{@code beforeWrite} hooks and {@link su.onno.rules.HasBusinessRules}
     * rules on the merged state (tabular rows included), so a conflict check written in Java
     * surfaces while the user is still on the field. No number is consumed from the sequence and no
     * events fire. Always returns 200 — the outcome is the {@code {valid, fieldErrors, formErrors}}
     * payload.
     */
    public Map<String, Object> validate(DocumentDescriptor desc, UUID id, Map<String, Object> requestBody,
                                        AccessSubject subject) {
        access.requireWrite(subject, desc);
        if (id != null) guard.requireInScope(desc.javaClass(), desc.tableName(), id, subject, AccessMode.WRITE);
        Map<String, Object> body = EntityWriteAliases.document(desc, requestBody);
        if (id == null) guard.applyDefaults(desc.javaClass(), body, subject);

        ValidationErrors errors = new ValidationErrors();
        boolean isNew = id == null;
        if (isNew) {
            attributeValidator.validate(body, desc.attributes(), errors);
        } else {
            attributeValidator.validatePartial(body, desc.attributes(), errors);
        }
        DocumentObject doc = isNew ? (DocumentObject) EntityBodyMapper.instantiate(desc.javaClass()) : load(desc, id);
        // A half-edited form can carry a blank date; keep the stored (or current) one rather than fail.
        Object date = body.get("date");
        if (date != null && !"".equals(date)) {
            LocalDateTime parsedDate = parseWriteDate(date, errors);
            if (parsedDate != null) doc.setDate(parsedDate);
        }
        if (isNew) {
            doc.setId(UUID.randomUUID());
            doc.setNumber(asString(body.getOrDefault("number", "")));
            if (doc.getDate() == null) doc.setDate(LocalDateTime.now());
        } else if (body.containsKey("number")) {
            doc.setNumber(asString(body.get("number")));
        }
        mapper.applyBody(doc, desc.attributes(), body, errors);
        mapper.applySections(doc, desc.tabularSections(), body, errors);
        mapper.dryRun(doc, isNew, errors);
        return EntityWriteSupport.validationReport(errors);
    }

    private Map<String, Object> postRecord(DocumentDescriptor desc, UUID id, AccessSubject subject) {
        EntityWriteSupport.requireWritable(properties);
        access.requirePost(subject, desc);
        guard.requireInScope(desc.javaClass(), desc.tableName(), id, subject, AccessMode.WRITE);
        DocumentObject doc = loadLive(desc, id);
        if (doc.isPosted()) {
            postingService.repost(doc);
        } else {
            postingService.post(doc);
        }
        return query.get(subject, desc, id);
    }

    public PostingPreview postingPreview(DocumentDescriptor desc, UUID id, AccessSubject subject) {
        access.requireRead(subject, desc);
        guard.requireInScope(desc.javaClass(), desc.tableName(), id, subject, AccessMode.READ);
        return postingService.preview(loadLive(desc, id));
    }

    private Map<String, Object> unpostRecord(DocumentDescriptor desc, UUID id, AccessSubject subject) {
        EntityWriteSupport.requireWritable(properties);
        access.requireUnpost(subject, desc);
        guard.requireInScope(desc.javaClass(), desc.tableName(), id, subject, AccessMode.WRITE);
        postingService.unpost(loadLive(desc, id));
        return query.get(subject, desc, id);
    }

    /**
     * Whether {@code subject} may change existing record {@code id} — inside its record-policy
     * write scope (entity-level write access is checked by the write itself). Import uses this to
     * turn an upsert onto someone else's record into a row error instead of an overwrite.
     */
    public boolean isWritable(DocumentDescriptor desc, UUID id, AccessSubject subject) {
        return query.inScope(subject, desc, id, AccessMode.WRITE);
    }

    private void deleteRecord(DocumentDescriptor desc, UUID id, AccessSubject subject) {
        EntityWriteSupport.requireWritable(properties);
        access.requireWrite(subject, desc);
        guard.requireInScope(desc.javaClass(), desc.tableName(), id, subject, AccessMode.WRITE);

        DocumentObject stored = loadLive(desc, id);
        // Deleting a posted document unposts it, so it needs the unpost right too (#546) — checked
        // before any hook runs so a caller without it changes nothing.
        if (stored.isPosted()) {
            access.requireUnpost(subject, desc);
        }
        // The domain's BeforeDeleteHandler may veto the soft delete (a ValidationException maps to
        // the standard 4xx), as on a repository delete.
        if (stored instanceof BeforeDeleteHandler handler) {
            handler.beforeDelete();
        }
        // Checked before unposting so a closed period changes nothing; the save re-checks it.
        if (periodClose != null) {
            periodClose.requireDocumentOpen(desc, id, stored.getDate(), periodClose.organizationOf(stored),
                    PeriodOperation.DELETE);
        }
        DocumentObject toDelete = stored;
        if (stored.isPosted()) {
            postingService.unpost(stored);
            toDelete = loadLive(desc, id); // unposting bumped the stored row; delete the current one
        }
        toDelete.setDeletionMark(true);
        DocumentObject marked = toDelete;
        store.inTransaction(() -> store.save(marked));
    }

    /**
     * Map the body onto the document. Validation failures found so far are reported together with
     * the document's business rules, as one response; the save itself re-runs hooks and validation.
     */
    private void mapBody(DocumentDescriptor desc, DocumentObject doc, Map<String, Object> body,
                         boolean isNew, ValidationErrors errors) {
        mapper.applyBody(doc, desc.attributes(), body, errors);
        mapper.applySections(doc, desc.tabularSections(), body, errors);
        if (!errors.isEmpty()) {
            mapper.dryRun(doc, isNew, errors);
            errors.throwIfAny();
        }
    }

    /** Save inside one transaction with the post-write record-scope check, so a violation rolls back. */
    private UUID save(DocumentDescriptor desc, DocumentObject doc, AccessSubject subject) {
        return store.inTransaction(() -> {
            DocumentObject saved = store.save(doc);
            jdbi.useHandle(h -> guard.requirePostImage(h, desc.javaClass(), desc.tableName(), saved.getId(), subject));
            return saved.getId();
        });
    }

    private DocumentObject load(DocumentDescriptor desc, UUID id) {
        DocumentObject doc = (DocumentObject) store.find(desc.javaClass(), id);
        if (doc == null) {
            throw new ServiceException(ServiceException.Status.NOT_FOUND);
        }
        return doc;
    }

    /** The stored document, 404 when it is missing or deletion-marked. */
    private DocumentObject loadLive(DocumentDescriptor desc, UUID id) {
        DocumentObject doc = load(desc, id);
        if (doc.isDeletionMark()) {
            throw new ServiceException(ServiceException.Status.NOT_FOUND);
        }
        return doc;
    }

    private static boolean changesAnything(DocumentDescriptor desc, Map<String, Object> body) {
        return body.containsKey("number") || body.containsKey("date")
                || desc.attributes().stream().anyMatch(a -> body.containsKey(a.fieldName()))
                || desc.tabularSections().stream().anyMatch(ts -> body.containsKey(ts.name()));
    }

    /** Header refs and the refs of every submitted line must point at records the subject may read. */
    private void requireReadableRefs(DocumentDescriptor desc, Map<String, Object> body, AccessSubject subject) {
        guard.requireReadableRefs(desc.attributes(), body, subject);
        TabularSectionWriteSupport.requireReadableRefs(desc.tabularSections(), body, guard, subject);
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static int parseInt(Object value) {
        return value instanceof Number n ? n.intValue() : Integer.parseInt(value.toString());
    }

    private static LocalDateTime parseWriteDate(Object value, ValidationErrors errors) {
        try {
            return TemporalValues.toWriteLocalDateTime(value);
        } catch (RuntimeException invalid) {
            errors.field("date", "Date " + TemporalValues.LOCAL_DATE_TIME_WRITE_MESSAGE);
            return null;
        }
    }
}
