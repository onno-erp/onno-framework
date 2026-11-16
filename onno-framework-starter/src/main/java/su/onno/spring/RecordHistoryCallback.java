package su.onno.spring;

import su.onno.history.RecordHistory;
import su.onno.history.RecordImage;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;

import org.jdbi.v3.core.Jdbi;
import org.springframework.core.Ordered;
import org.springframework.data.relational.core.conversion.MutableAggregateChange;
import org.springframework.data.relational.core.mapping.event.AfterSaveCallback;
import org.springframework.data.relational.core.mapping.event.BeforeSaveCallback;

import java.util.UUID;

/**
 * Records the change history of typed {@code repository.save(...)}/{@code delete(...)} calls for
 * entities that opted in with {@code history = true} (a repository delete is a deletion-mark save,
 * so it flows through here too).
 *
 * <p>{@link BeforeSaveCallback} runs after the write lifecycle but before any SQL, so it captures
 * the stored row as the "before" image; {@link AfterSaveCallback} runs once the statements have
 * executed and lets {@link RecordHistory} diff the stored result. Both use the JDBI handle bound to
 * the repository's Spring transaction, so the history rows commit or roll back with the save.
 *
 * <p>Ordered first so the after-save half reads its captured image before
 * {@link OnnoAfterSaveCallback} runs {@code afterWrite()} hooks, which may save other aggregates on
 * this thread.
 */
public class RecordHistoryCallback implements BeforeSaveCallback<Object>, AfterSaveCallback<Object>, Ordered {

    private record Pending(Class<?> type, UUID id, RecordImage before) {
    }

    private final RecordHistory history;
    private final Jdbi jdbi;
    private final ThreadLocal<Pending> pending = new ThreadLocal<>();

    public RecordHistoryCallback(RecordHistory history, Jdbi jdbi) {
        this.history = history;
        this.jdbi = jdbi;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Object onBeforeSave(Object aggregate, MutableAggregateChange<Object> aggregateChange) {
        pending.remove();
        UUID id = idOf(aggregate);
        if (id == null || !history.tracks(aggregate.getClass())) {
            return aggregate;
        }
        RecordImage before = isNew(aggregate)
                ? RecordImage.ABSENT
                : jdbi.withHandle(h -> history.capture(h, aggregate.getClass(), id));
        pending.set(new Pending(aggregate.getClass(), id, before));
        return aggregate;
    }

    @Override
    public Object onAfterSave(Object aggregate) {
        Pending captured = pending.get();
        pending.remove();
        if (captured == null || !captured.type().equals(aggregate.getClass())
                || !captured.id().equals(idOf(aggregate))) {
            return aggregate;
        }
        jdbi.useHandle(h -> history.record(h, captured.type(), captured.id(), captured.before(), null));
        return aggregate;
    }

    private static UUID idOf(Object aggregate) {
        if (aggregate instanceof CatalogObject catalog) {
            return catalog.getId();
        }
        if (aggregate instanceof DocumentObject document) {
            return document.getId();
        }
        return null;
    }

    private static boolean isNew(Object aggregate) {
        return aggregate instanceof CatalogObject catalog && catalog.isNew()
                || aggregate instanceof DocumentObject document && document.isNew();
    }
}
