package su.onno.period;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One durable record of a close, a reopen, or an override.
 *
 * @param id            the entry id
 * @param at            when it happened
 * @param action        close, reopen or override
 * @param scope         the scope it concerns
 * @param previous      the close date before the change ({@code null} when the scope was open)
 * @param closedThrough the close date after the change ({@code null} when the scope was fully
 *                      reopened); for an override, the close date that was bypassed
 * @param actor         who did it
 * @param reason        why (always present for a reopen)
 * @param operation     for an override, the bypassed operation
 * @param documentType  for an override, the document's logical name
 * @param documentId    for an override, the document id
 * @param documentDate  for an override, the document date inside the closed period
 * @param hard          for a close, whether it is a hard close
 */
public record PeriodCloseAuditEntry(UUID id, LocalDateTime at, PeriodCloseAction action, PeriodScope scope,
                                    LocalDate previous, LocalDate closedThrough, String actor, String reason,
                                    PeriodOperation operation, String documentType, UUID documentId,
                                    LocalDateTime documentDate, boolean hard) {
}
