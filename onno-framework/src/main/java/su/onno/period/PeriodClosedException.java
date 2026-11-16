package su.onno.period;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A change was rejected because the document falls inside a closed period. The message names the
 * closed period and how to proceed.
 */
public class PeriodClosedException extends RuntimeException {

    private final transient PeriodLock lock;
    private final PeriodOperation operation;
    private final String documentType;
    private final UUID documentId;
    private final LocalDateTime documentDate;

    public PeriodClosedException(PeriodLock lock, PeriodOperation operation, String documentType,
                                 UUID documentId, LocalDateTime documentDate) {
        super(message(lock, operation, documentType, documentDate));
        this.lock = lock;
        this.operation = operation;
        this.documentType = documentType;
        this.documentId = documentId;
        this.documentDate = documentDate;
    }

    private static String message(PeriodLock lock, PeriodOperation operation, String documentType,
                                  LocalDateTime documentDate) {
        return "Period is closed through " + lock.closedThrough() + " for " + lock.scope().describe()
                + ": cannot " + operation.verb() + " " + documentType + " dated "
                + documentDate.toLocalDate()
                + ". Use a date after " + lock.closedThrough()
                + ", or ask an administrator to reopen the period.";
    }

    /** The close that rejected the change. */
    public PeriodLock lock() {
        return lock;
    }

    public PeriodOperation operation() {
        return operation;
    }

    /** The document's logical name. */
    public String documentType() {
        return documentType;
    }

    public UUID documentId() {
        return documentId;
    }

    /** The document date that falls inside the closed period. */
    public LocalDateTime documentDate() {
        return documentDate;
    }
}
