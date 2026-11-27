package su.onno.posting;

import su.onno.events.EntityChangedEvent;
import su.onno.lifecycle.AfterPostHandler;
import su.onno.lifecycle.BeforePostHandler;
import su.onno.lifecycle.BeforeUnpostHandler;
import su.onno.lifecycle.Postable;
import su.onno.annotations.DomainEvent;
import su.onno.annotations.EventTiming;
import su.onno.metadata.AccumulationRegisterDescriptor;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.messaging.OutboxWriter;
import su.onno.model.AccumulationRecord;
import su.onno.model.AccumulationType;
import su.onno.model.DocumentObject;
import su.onno.model.PostingOrder;
import su.onno.performance.OnnoPerformance;
import su.onno.period.PeriodCloseService;
import su.onno.period.PeriodOperation;
import su.onno.repository.RegisterRepositoryImpl;
import su.onno.rules.BusinessRuleValidator;
import su.onno.types.Ref;
import su.onno.types.PolyRef;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.transaction.TransactionIsolationLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Posts documents: runs their {@link Postable} logic, writes the resulting register movements and
 * totals, enforces non-negative balances, then flips {@code _posted}.
 * Posting, re-posting, preview and chronological restoration do not call
 * {@code BeforeWriteHandler.beforeWrite()} or save document attributes or tabular-section rows.
 * Save derived document fields through the ordinary write pipeline before posting; values
 * computed by {@code beforePost()} or {@code handlePosting()} are for movement calculation only.
 *
 * <h2>Transaction boundary — important</h2>
 * Posting runs inside its <em>own</em> JDBI transaction ({@link Jdbi#useTransaction}) on a connection
 * obtained directly from the {@code DataSource}. It is <strong>not</strong> enlisted in any ambient
 * Spring {@code @Transactional} that the caller may have opened. Two consequences follow:
 * <ul>
 *   <li>Do <strong>not</strong> wrap "save the document, then post it" in a single
 *       {@code @Transactional} method. The {@code save()} row is not yet committed, so JDBI — on a
 *       separate connection — cannot see it, the {@code UPDATE ... SET _posted = TRUE} matches zero
 *       rows, and you silently get register movements with {@code _posted} still {@code false}.
 *       Save (and let it commit) first, then call {@link PostingService}/{@code post(...)}.</li>
 *   <li>Posting is atomic in itself (movements, totals, balance checks and the {@code _posted} flag
 *       all commit or roll back together), but it is a distinct transaction from the document write.</li>
 * </ul>
 */
public class PostingEngine {

    private enum PostingMutation {
        POST,
        REPOST,
        UNPOST
    }

    private static final Logger log = LoggerFactory.getLogger(PostingEngine.class);

    private final Jdbi jdbi;
    private final MetadataRegistry registry;
    private final Map<Class<?>, RegisterRepositoryImpl<?>> repositoryMap;
    private final BusinessRuleValidator businessRuleValidator = new BusinessRuleValidator();
    private final OutboxWriter outboxWriter;
    private final PostEventPublisher eventPublisher;
    private final PostedDocumentLoader documentLoader;
    private PeriodCloseService periodClose;
    private su.onno.history.RecordHistory history = su.onno.history.RecordHistory.disabled();

    public PostingEngine(Jdbi jdbi, MetadataRegistry registry,
                         Map<Class<?>, RegisterRepositoryImpl<?>> repositoryMap) {
        this(jdbi, registry, repositoryMap, null);
    }

    public PostingEngine(Jdbi jdbi, MetadataRegistry registry,
                         Map<Class<?>, RegisterRepositoryImpl<?>> repositoryMap,
                         OutboxWriter outboxWriter) {
        this(jdbi, registry, repositoryMap, outboxWriter, null);
    }

    public PostingEngine(Jdbi jdbi, MetadataRegistry registry,
                         Map<Class<?>, RegisterRepositoryImpl<?>> repositoryMap,
                         OutboxWriter outboxWriter,
                         PostEventPublisher eventPublisher) {
        this(jdbi, registry, repositoryMap, outboxWriter, eventPublisher, null);
    }

    public PostingEngine(Jdbi jdbi, MetadataRegistry registry,
                         Map<Class<?>, RegisterRepositoryImpl<?>> repositoryMap,
                         OutboxWriter outboxWriter,
                         PostEventPublisher eventPublisher,
                         PostedDocumentLoader documentLoader) {
        this.jdbi = jdbi;
        this.registry = registry;
        this.repositoryMap = repositoryMap;
        this.outboxWriter = outboxWriter;
        this.eventPublisher = eventPublisher;
        this.documentLoader = documentLoader;
    }

    /**
     * Enables period-close enforcement: post, repost, unpost and every document a chronological
     * restoration would repost are rejected inside a closed period.
     */
    public void setPeriodCloseService(PeriodCloseService periodClose) {
        this.periodClose = periodClose;
    }

    /**
     * The change-history recorder: documents with {@code @Document(history = true)} get a
     * {@code POSTED}/{@code UNPOSTED} entry written inside the posting transaction, so the entry and
     * the {@code _posted} flip commit or roll back together.
     */
    public void setHistory(su.onno.history.RecordHistory history) {
        this.history = history == null ? su.onno.history.RecordHistory.disabled() : history;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void post(DocumentObject document) {
        try {
            OnnoPerformance.record("onno.document.post", 1, () -> doPost(document));
            log.debug("Posted {} {}", document.getClass().getSimpleName(), document.getId());
        } catch (RuntimeException e) {
            log.warn("Posting failed for {} {}: {}",
                    document.getClass().getSimpleName(), document.getId(), e.getMessage());
            throw e;
        }
    }

    /**
     * Replaces the active movements of an already-posted document and writes the
     * freshly calculated movements in one database transaction.
     */
    public void repost(DocumentObject document) {
        try {
            OnnoPerformance.record("onno.document.repost", 1, () -> doRepost(document));
            log.debug("Re-posted {} {}", document.getClass().getSimpleName(), document.getId());
        } catch (RuntimeException e) {
            log.warn("Re-posting failed for {} {}: {}",
                    document.getClass().getSimpleName(), document.getId(), e.getMessage());
            throw e;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void doPost(DocumentObject document) {
        if (!(document instanceof Postable)) {
            throw new IllegalArgumentException(
                    document.getClass().getName() + " does not implement Postable");
        }

        if (document instanceof BeforePostHandler handler) {
            OnnoPerformance.record("onno.document.before-post", 1, handler::beforePost);
        }
        OnnoPerformance.record("onno.document.validate", 1, () -> businessRuleValidator.validate(document));

        DocumentDescriptor docDescriptor = registry.getDocumentDescriptor(document.getClass());

        PostingContext context = buildPostingContext(document);
        Set<Class<?>> chronologicalRegisters = chronologicalRegisters(context.touchedRepositories());
        if (!chronologicalRegisters.isEmpty()) {
            restoreChronologically(document, context, chronologicalRegisters, PostingMutation.POST);
            afterSuccessfulPost(document);
            return;
        }

        OnnoPerformance.record("onno.document.post.transaction", 1, () -> jdbi.useTransaction(handle -> {
            claimUnpostedDocument(handle, docDescriptor, document.getId());
            requirePeriodOpen(handle, document, context, PeriodOperation.POST);
            persistPosting(handle, document, context);
            publishDomainEvents(handle, document, EventTiming.AFTER_POST);
        }));

        afterSuccessfulPost(document);
    }

    private void doRepost(DocumentObject document) {
        PostingContext context = preparePosting(document);
        Set<Class<?>> chronologicalRegisters = new LinkedHashSet<>(
                chronologicalRegistersForDocument(document.getId()));
        chronologicalRegisters.addAll(chronologicalRegisters(context.touchedRepositories()));
        if (!chronologicalRegisters.isEmpty()) {
            restoreChronologically(document, context, chronologicalRegisters, PostingMutation.REPOST);
            afterSuccessfulPost(document);
            return;
        }

        DocumentDescriptor descriptor = registry.getDocumentDescriptor(document.getClass());
        OnnoPerformance.record("onno.document.repost.transaction", 1, () -> jdbi.useTransaction(handle -> {
            claimPostedDocument(handle, descriptor, document.getId(), false);
            requirePeriodOpen(handle, document, context, PeriodOperation.REPOST);
            unpostMovements(handle, document.getId());
            persistPosting(handle, document, context);
            publishDomainEvents(handle, document, EventTiming.AFTER_POST);
        }));
        afterSuccessfulPost(document);
    }

    public PostingPreview preview(DocumentObject document) {
        return OnnoPerformance.record("onno.document.post.preview", 1, () -> doPreview(document));
    }

    private PostingPreview doPreview(DocumentObject document) {
        if (!(document instanceof Postable)) {
            throw new IllegalArgumentException(
                    document.getClass().getName() + " does not implement Postable");
        }
        if (document instanceof BeforePostHandler handler) {
            OnnoPerformance.record("onno.document.before-post", 1, handler::beforePost);
        }
        OnnoPerformance.record("onno.document.validate", 1, () -> businessRuleValidator.validate(document));

        DocumentDescriptor docDescriptor = registry.getDocumentDescriptor(document.getClass());
        PostingContext context = buildPostingContext(document);
        requirePeriodOpenForPreview(document, context);

        List<PostingPreview.RegisterPreview> registers = context.touchedRepositories().stream()
                .map(repo -> {
                    AccumulationRegisterDescriptor desc = repo.getPersistence().getDescriptor();
                    @SuppressWarnings("unchecked")
                    List<AccumulationRecord> movements = (List<AccumulationRecord>) (List<?>) repo.getPendingMovements();
                    List<Map<String, Object>> rows = movements.stream()
                            .map(record -> movementMap(desc, record))
                            .toList();
                    return new PostingPreview.RegisterPreview(
                            desc.logicalName(),
                            desc.tableName(),
                            desc.accumulationType().name(),
                            rows);
                })
                .toList();

        clearPending(context);
        return new PostingPreview(
                docDescriptor.logicalName(),
                document.getId() == null ? null : document.getId().toString(),
                registers);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void unpost(DocumentObject document) {
        try {
            OnnoPerformance.record("onno.document.unpost", 1, () -> doUnpost(document));
            log.debug("Unposted {} {}", document.getClass().getSimpleName(), document.getId());
        } catch (RuntimeException e) {
            log.warn("Unposting failed for {} {}: {}",
                    document.getClass().getSimpleName(), document.getId(), e.getMessage());
            throw e;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void doUnpost(DocumentObject document) {
        if (document instanceof BeforeUnpostHandler handler) {
            OnnoPerformance.record("onno.document.before-unpost", 1, handler::beforeUnpost);
        }
        DocumentDescriptor docDescriptor = registry.getDocumentDescriptor(document.getClass());
        Set<Class<?>> chronologicalRegisters = chronologicalRegistersForDocument(document.getId());
        if (!chronologicalRegisters.isEmpty()) {
            restoreChronologically(document, null, chronologicalRegisters, PostingMutation.UNPOST);
            afterSuccessfulUnpost(document);
            return;
        }

        OnnoPerformance.record("onno.document.unpost.transaction", 1, () -> jdbi.useTransaction(handle -> {
            unpostInTransaction(handle, docDescriptor, document.getId());
        }));

        afterSuccessfulUnpost(document);
    }

    /*
     * Everything below runs after the posting transaction has committed. The post is done and
     * cannot be rolled back, so a failing callback (an AfterPostHandler, or an @EventListener
     * behind the publisher) must not surface as a posting failure: the caller would report
     * "posting failed" for a posted document, and the remaining callbacks would be skipped.
     * Each callback is isolated and a failure is logged at ERROR. Work that must not be lost
     * belongs in a @DomainEvent outbox row, which commits with the posting.
     */
    private void afterSuccessfulPost(DocumentObject document) {
        document.setPosted(true);
        publishChangeEvents(document, EntityChangedEvent.POSTED);
        if (document instanceof AfterPostHandler handler) {
            afterCommit(document, "AfterPostHandler.afterPost()", () ->
                    OnnoPerformance.record("onno.document.after-post", 1, handler::afterPost));
        }
        afterCommit(document, "DocumentPostedEvent listener", () ->
                publishApplicationEvent(new DocumentPostedEvent(document)));
    }

    private void afterSuccessfulUnpost(DocumentObject document) {
        document.setPosted(false);
        publishChangeEvents(document, EntityChangedEvent.UNPOSTED);
        afterCommit(document, "DocumentUnpostedEvent listener", () ->
                publishApplicationEvent(new DocumentUnpostedEvent(document)));
    }

    /**
     * The document change ({@code POSTED}/{@code UNPOSTED}) plus a wildcard register change: posting
     * rewrote register movements, so open register views (balances, turnovers, movements) refresh
     * whichever entry point posted — UI, REST, MCP, import, a job or direct Java.
     */
    private void publishChangeEvents(DocumentObject document, String changeType) {
        afterCommit(document, "EntityChangedEvent listener", () -> {
            publishApplicationEvent(new EntityChangedEvent(
                    changeType,
                    EntityChangedEvent.DOCUMENT,
                    registry.getDocumentDescriptor(document.getClass()).logicalName(),
                    document.getId(),
                    document.getNumber()));
        });
        afterCommit(document, "EntityChangedEvent listener", () ->
                publishApplicationEvent(new EntityChangedEvent(
                        EntityChangedEvent.CHANGED,
                        EntityChangedEvent.REGISTER,
                        "*",
                        document.getId(),
                        null)));
    }

    private void afterCommit(DocumentObject document, String callback, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.error("{} failed after {} {} was committed; the posting stands",
                    callback, document.getClass().getSimpleName(), document.getId(), e);
        }
    }

    private void restoreChronologically(DocumentObject requested,
                                        PostingContext requestedContext,
                                        Set<Class<?>> chronologicalRegisters,
                                        PostingMutation mutation) {
        OnnoPerformance.record("onno.document.restore-sequence", 1,
                () -> jdbi.useTransaction(TransactionIsolationLevel.SERIALIZABLE, handle ->
                        withBoundRegisterReads(handle, () -> {
                            DocumentDescriptor requestedDescriptor =
                                    registry.getDocumentDescriptor(requested.getClass());
                            switch (mutation) {
                                case POST -> claimUnpostedDocument(
                                        handle, requestedDescriptor, requested.getId());
                                case REPOST -> claimPostedDocument(
                                        handle, requestedDescriptor, requested.getId(), false);
                                case UNPOST -> claimPostedDocument(
                                        handle, requestedDescriptor, requested.getId(), true);
                            }
                            requirePeriodOpen(handle, requested, requestedContext, switch (mutation) {
                                case POST -> PeriodOperation.POST;
                                case REPOST -> PeriodOperation.REPOST;
                                case UNPOST -> PeriodOperation.UNPOST;
                            });
                            List<DocumentObject> laterDocuments =
                                    loadLaterDocuments(handle, requested, chronologicalRegisters);
                            for (DocumentObject later : laterDocuments) {
                                requirePeriodOpen(handle, later, null, PeriodOperation.RESTORE);
                            }
                            List<DocumentObject> reverse = new ArrayList<>(laterDocuments);
                            reverse.sort(documentOrder().reversed());
                            for (DocumentObject later : reverse) {
                                unpostMovements(handle, later.getId());
                            }

                            if (mutation == PostingMutation.UNPOST) {
                                unpostMovements(handle, requested.getId());
                            } else {
                                if (mutation == PostingMutation.REPOST) {
                                    unpostMovements(handle, requested.getId());
                                }
                                // The caller staged these movements before this transaction, from
                                // balances that still included every later-dated document. With
                                // those reversed the register now stands exactly as of the
                                // requested date, so recompute: a backdated post must see what a
                                // date-ordered post would have seen.
                                clearPending(requestedContext);
                                PostingContext asOfDate = buildPostingContext(requested);
                                persistPosting(handle, requested, asOfDate);
                            }

                            for (DocumentObject later : laterDocuments) {
                                PostingContext context = preparePosting(later);
                                persistPosting(handle, later, context);
                                later.setPosted(true);
                            }
                            if (mutation != PostingMutation.UNPOST) {
                                publishDomainEvents(
                                        handle, requested, EventTiming.AFTER_POST);
                            }
                        })));
    }

    private PostingContext preparePosting(DocumentObject document) {
        if (!(document instanceof Postable)) {
            throw new IllegalArgumentException(
                    document.getClass().getName() + " does not implement Postable");
        }
        if (document instanceof BeforePostHandler handler) {
            OnnoPerformance.record("onno.document.before-post", 1, handler::beforePost);
        }
        OnnoPerformance.record("onno.document.validate", 1,
                () -> businessRuleValidator.validate(document));
        return buildPostingContext(document);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void persistPosting(Handle handle,
                                DocumentObject document,
                                PostingContext context) {
        for (RegisterRepositoryImpl<?> repo : context.touchedRepositories()) {
            RegisterPersistence persistence = repo.getPersistence();
            persistence.insertRecords(handle, repo.getPendingMovements(),
                    document.getId(), document.getDate());
            persistence.updateTotals(handle, repo.getPendingMovements());
            checkNonNegativeBalances(handle, persistence.getDescriptor(), document.getId());
            repo.clearPending();
        }
    }

    private void claimUnpostedDocument(Handle handle,
                                       DocumentDescriptor descriptor,
                                       UUID documentId) {
        int updated = handle.createUpdate("UPDATE " + descriptor.tableName() +
                        " SET _posted = TRUE WHERE _id = :id AND _posted = FALSE" +
                        " AND _deletion_mark = FALSE")
                .bind("id", documentId)
                .execute();
        if (updated != 1) {
            throw new IllegalStateException(
                    "Document " + documentId +
                            " does not exist, is deleted, or is already posted");
        }
        history.recordEvent(handle, descriptor.javaClass(), documentId, su.onno.history.RecordHistory.POSTED, null);
    }

    private void claimPostedDocument(Handle handle,
                                     DocumentDescriptor descriptor,
                                     UUID documentId,
                                     boolean clearPosted) {
        int updated = handle.createUpdate("UPDATE " + descriptor.tableName() +
                        " SET _posted = :posted WHERE _id = :id AND _posted = TRUE" +
                        " AND _deletion_mark = FALSE")
                .bind("posted", !clearPosted)
                .bind("id", documentId)
                .execute();
        if (updated != 1) {
            throw new IllegalStateException(
                    "Document " + documentId +
                            " does not exist, is deleted, or is not posted");
        }
        // A repost (clearPosted = false) re-applies the posting, so it is recorded as POSTED again.
        history.recordEvent(handle, descriptor.javaClass(), documentId,
                clearPosted ? su.onno.history.RecordHistory.UNPOSTED : su.onno.history.RecordHistory.POSTED, null);
    }

    private void unpostInTransaction(Handle handle,
                                     DocumentDescriptor descriptor,
                                     UUID documentId) {
        claimPostedDocument(handle, descriptor, documentId, true);
        if (periodClose != null) {
            periodClose.requirePostingOpen(handle, descriptor, documentId, null, List.of(),
                    PeriodOperation.UNPOST, true);
        }
        // Unlike a repost, standalone unposting has no replacement movements to restore a
        // constrained balance. Check while the original rows still identify the touched keys.
        for (RegisterRepositoryImpl<?> repo : repositoryMap.values()) {
            RegisterPersistence<?> persistence = repo.getPersistence();
            persistence.reverseTotals(handle, documentId);
            checkNonNegativeBalances(handle, persistence.getDescriptor(), documentId);
            persistence.deactivateRecords(handle, documentId);
        }
    }

    /**
     * Period-close guard for {@code document} on the posting transaction: its own and stored date
     * against the closes of its context and of every register it stages or already moved.
     */
    private void requirePeriodOpen(Handle handle, DocumentObject document, PostingContext context,
                                   PeriodOperation operation) {
        if (periodClose == null) return;
        periodClose.requirePostingOpen(handle, registry.getDocumentDescriptor(document.getClass()),
                document.getId(), document.getDate(), periodClose.organizationOf(document),
                stagedRegisters(context), operation, true);
    }

    /** A preview changes nothing, so a granted override is not audited. */
    private void requirePeriodOpenForPreview(DocumentObject document, PostingContext context) {
        if (periodClose == null) return;
        jdbi.useHandle(handle -> periodClose.requirePostingOpen(handle,
                registry.getDocumentDescriptor(document.getClass()), document.getId(),
                document.getDate(), periodClose.organizationOf(document), stagedRegisters(context),
                document.isPosted() ? PeriodOperation.REPOST : PeriodOperation.POST, false));
    }

    private static List<AccumulationRegisterDescriptor> stagedRegisters(PostingContext context) {
        if (context == null) return List.of();
        List<AccumulationRegisterDescriptor> staged = new ArrayList<>();
        for (RegisterRepositoryImpl<?> repository : context.touchedRepositories()) {
            staged.add(repository.getPersistence().getDescriptor());
        }
        return staged;
    }

    private void unpostMovements(Handle handle, UUID documentId) {
        for (RegisterRepositoryImpl<?> repo : repositoryMap.values()) {
            RegisterPersistence<?> persistence = repo.getPersistence();
            persistence.reverseTotals(handle, documentId);
            persistence.deactivateRecords(handle, documentId);
        }
    }

    private Set<Class<?>> chronologicalRegisters(
            Collection<RegisterRepositoryImpl<?>> repositories) {
        Set<Class<?>> result = new LinkedHashSet<>();
        for (RegisterRepositoryImpl<?> repository : repositories) {
            AccumulationRegisterDescriptor descriptor =
                    repository.getPersistence().getDescriptor();
            if (descriptor.postingOrder() == PostingOrder.CHRONOLOGICAL) {
                result.add(descriptor.javaClass());
            }
        }
        return result;
    }

    private Set<Class<?>> chronologicalRegistersForDocument(UUID documentId) {
        Set<Class<?>> result = new LinkedHashSet<>();
        for (AccumulationRegisterDescriptor descriptor : registry.allRegisters()) {
            if (descriptor.postingOrder() != PostingOrder.CHRONOLOGICAL) continue;
            boolean touched = jdbi.withHandle(handle -> handle.createQuery(
                            "SELECT COUNT(*) FROM " + descriptor.tableName() +
                                    " WHERE _document_ref = :documentId AND _active = TRUE")
                    .bind("documentId", documentId)
                    .mapTo(Integer.class)
                    .one() > 0);
            if (touched) result.add(descriptor.javaClass());
        }
        return result;
    }

    private List<DocumentObject> loadLaterDocuments(Handle handle,
                                                     DocumentObject requested,
                                                     Set<Class<?>> initialRegisters) {
        if (initialRegisters.isEmpty()) return List.of();
        Set<Class<?>> affectedRegisters = new LinkedHashSet<>(initialRegisters);
        Set<UUID> documentIds = new LinkedHashSet<>();

        boolean changed;
        do {
            changed = false;
            for (Class<?> registerClass : List.copyOf(affectedRegisters)) {
                AccumulationRegisterDescriptor descriptor =
                        registry.getRegisterDescriptor(registerClass);
                List<UUID> ids = handle.createQuery(
                                "SELECT DISTINCT _document_ref FROM " + descriptor.tableName()
                                        + " WHERE _active = TRUE AND _period > :cutoff"
                                        + " AND _document_ref <> :requestedId")
                        .bind("cutoff", requested.getDate())
                        .bind("requestedId", requested.getId())
                        .mapTo(UUID.class)
                        .list();
                if (documentIds.addAll(ids)) changed = true;
            }
            if (!documentIds.isEmpty()) {
                for (AccumulationRegisterDescriptor descriptor : registry.allRegisters()) {
                    if (descriptor.postingOrder() != PostingOrder.CHRONOLOGICAL
                            || affectedRegisters.contains(descriptor.javaClass())) {
                        continue;
                    }
                    boolean linked = handle.createQuery(
                                    "SELECT COUNT(*) FROM " + descriptor.tableName()
                                            + " WHERE _active = TRUE AND _document_ref IN (<ids>)")
                            .bindList("ids", documentIds)
                            .mapTo(Integer.class)
                            .one() > 0;
                    if (linked && affectedRegisters.add(descriptor.javaClass())) changed = true;
                }
            }
        } while (changed);

        if (documentIds.isEmpty()) return List.of();
        if (documentLoader == null) {
            throw new IllegalStateException(
                    "Chronological posting found later documents, but no PostedDocumentLoader is configured");
        }
        List<DocumentObject> loaded = documentLoader.load(documentIds);
        Set<UUID> loadedIds = new HashSet<>();
        List<DocumentObject> result = loaded.stream()
                .filter(doc -> documentIds.contains(doc.getId()))
                .filter(DocumentObject::isPosted)
                .filter(doc -> !doc.isDeletionMark())
                .filter(doc -> doc.getDate() != null && doc.getDate().isAfter(requested.getDate()))
                .sorted(documentOrder())
                .peek(doc -> loadedIds.add(doc.getId()))
                .toList();
        Set<UUID> missing = new LinkedHashSet<>(documentIds);
        missing.removeAll(loadedIds);
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Could not load posted documents required for chronological restoration: " + missing);
        }
        return result;
    }

    private Comparator<DocumentObject> documentOrder() {
        return Comparator.comparing(DocumentObject::getDate)
                .thenComparing(DocumentObject::getId);
    }

    private void withBoundRegisterReads(Handle handle, Runnable action) {
        Map<RegisterPersistence<?>, Handle> previous = new HashMap<>();
        try {
            for (RegisterRepositoryImpl<?> repository : repositoryMap.values()) {
                RegisterPersistence<?> persistence = repository.getPersistence();
                previous.put(persistence, persistence.bindHandle(handle));
            }
            action.run();
        } finally {
            for (Map.Entry<RegisterPersistence<?>, Handle> entry : previous.entrySet()) {
                entry.getKey().restoreHandle(entry.getValue());
            }
        }
    }

    private void checkNonNegativeBalances(
            Handle handle,
            AccumulationRegisterDescriptor desc,
            UUID documentId) {
        if (desc.accumulationType() != AccumulationType.BALANCE || desc.allowNegative()) return;

        String touchedPredicate = touchedDimensionPredicate(desc);

        for (AttributeDescriptor res : desc.resources()) {
            String sql = "SELECT COUNT(*) FROM " + desc.totalsTableName() +
                    " WHERE " + res.columnName() + " < 0" + touchedPredicate;
            var query = handle.createQuery(sql);
            if (!desc.dimensions().isEmpty()) {
                query.bind("documentId", documentId);
            }
            int count = query.mapTo(Integer.class).one();
            if (count > 0) {
                throw new IllegalStateException(
                        "Insufficient " + res.displayName().toLowerCase() +
                        " in register \"" + desc.logicalName() + "\". " +
                        "Posting would result in negative balance.");
            }
        }
    }

    private String touchedDimensionPredicate(AccumulationRegisterDescriptor desc) {
        if (desc.dimensions().isEmpty()) return "";

        String dimensions = desc.dimensions().stream()
                .map(AttributeDescriptor::columnName)
                .collect(java.util.stream.Collectors.joining(", "));
        String totalsKey = desc.dimensions().size() == 1
                ? dimensions
                : "(" + dimensions + ")";
        return " AND " + totalsKey + " IN (SELECT " + dimensions +
                " FROM " + desc.tableName() +
                " WHERE _document_ref = :documentId AND _active = TRUE)";
    }

    private PostingContext buildPostingContext(DocumentObject document) {
        PostingContext context = new PostingContext(repositoryMap);
        if (document instanceof Postable postable) {
            OnnoPerformance.record("onno.document.handle-posting", 1, () -> postable.handlePosting(context));
        }
        return context;
    }

    private Map<String, Object> movementMap(AccumulationRegisterDescriptor desc, AccumulationRecord record) {
        java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("movementType", record.getMovementType().name());
        for (AttributeDescriptor dim : desc.dimensions()) {
            map.put(dim.fieldName(), convertForDb(getFieldValue(record, dim.fieldName())));
        }
        for (AttributeDescriptor res : desc.resources()) {
            map.put(res.fieldName(), convertForDb(getFieldValue(record, res.fieldName())));
        }
        return map;
    }

    private void clearPending(PostingContext context) {
        for (RegisterRepositoryImpl<?> repo : context.touchedRepositories()) {
            repo.clearPending();
        }
    }

    private void publishApplicationEvent(Object event) {
        if (eventPublisher != null) {
            eventPublisher.publish(event);
        }
    }

    private void publishDomainEvents(Handle handle, DocumentObject document, EventTiming timing) {
        if (outboxWriter == null) return;
        for (DomainEvent event : document.getClass().getAnnotationsByType(DomainEvent.class)) {
            if (event.when() != timing) continue;
            String payload = "{\"documentType\":\"" + document.getClass().getName() +
                    "\",\"documentId\":\"" + document.getId() + "\"}";
            outboxWriter.append(handle, document.getClass().getName(),
                    document.getId() == null ? null : document.getId().toString(),
                    event.name(),
                    payload);
        }
    }

    private Object convertForDb(Object val) {
        if (val == null) return null;
        if (val instanceof Ref<?> ref) return ref.id();
        if (val instanceof PolyRef ref) return ref.externalForm();
        if (val instanceof Enum<?> e) {
            var enumDesc = registry.allEnumerations().stream()
                    .filter(ed -> ed.javaClass().equals(e.getClass()))
                    .findFirst().orElse(null);
            if (enumDesc != null) {
                return enumDesc.values().stream()
                        .filter(v -> v.name().equals(e.name()))
                        .findFirst()
                        .map(v -> (Object) v.id())
                        .orElse(null);
            }
        }
        return val;
    }

    private Object getFieldValue(Object target, String fieldName) {
        try {
            Field field = findField(target.getClass(), fieldName);
            if (field == null) return null;
            field.setAccessible(true);
            return field.get(target);
        } catch (Exception e) {
            return null;
        }
    }

    private Field findField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
