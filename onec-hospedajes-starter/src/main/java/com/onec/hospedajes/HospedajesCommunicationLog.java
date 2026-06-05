package com.onec.hospedajes;

import org.jdbi.v3.core.Jdbi;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Audit + reconciliation ledger for partes sent to SES.HOSPEDAJES. One row per comunicación, keyed
 * by (numeroLote, orden) — the position assigned by the service within a batch — and carrying the
 * caller's contract {@code referencia} for traceability. Tracks the async lifecycle:
 * SUBMITTED → (REGISTERED | REJECTED) and later CANCELLED.
 */
public class HospedajesCommunicationLog {

    public enum Status { SUBMITTED, REGISTERED, REJECTED, CANCELLED }

    public record Entry(String referencia, String numeroLote, int orden,
                        String codigoComunicacion, String status) {
    }

    private final Jdbi jdbi;

    public HospedajesCommunicationLog(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public void initSchema() {
        jdbi.useHandle(handle -> handle.execute(
                "CREATE TABLE IF NOT EXISTS onec_hospedajes_comunicacion (" +
                        "_id UUID PRIMARY KEY, " +
                        "_referencia VARCHAR(50) NOT NULL, " +
                        "_numero_lote VARCHAR(50), " +
                        "_orden INT NOT NULL, " +
                        "_codigo_comunicacion VARCHAR(50), " +
                        "_status VARCHAR(32) NOT NULL, " +
                        "_error VARCHAR(1000), " +
                        "_created_at TIMESTAMP NOT NULL, " +
                        "_updated_at TIMESTAMP NOT NULL)"));
    }

    /** Record one submitted comunicación at its 1-based position in the batch. */
    public void recordSubmission(String referencia, String numeroLote, int orden) {
        LocalDateTime now = LocalDateTime.now();
        jdbi.useHandle(handle -> handle.createUpdate(
                        "INSERT INTO onec_hospedajes_comunicacion " +
                                "(_id, _referencia, _numero_lote, _orden, _status, _created_at, _updated_at) " +
                                "VALUES (:id, :referencia, :lote, :orden, 'SUBMITTED', :now, :now)")
                .bind("id", UUID.randomUUID())
                .bind("referencia", referencia)
                .bind("lote", numeroLote)
                .bind("orden", orden)
                .bind("now", now)
                .execute());
    }

    /**
     * Record a parte rejected by local validation before it was ever submitted (so it has no lote
     * or orden yet). Replaces any previous local rejection for the same referencia so re-validating
     * an uncorrected booking does not pile up duplicate rows. The entry is left in REJECTED state,
     * which {@link #hasActiveSubmission(String)} does not treat as active, so the parte is retried
     * automatically once the underlying data is fixed and the booking is saved again.
     */
    public void recordLocalRejection(String referencia, String error) {
        LocalDateTime now = LocalDateTime.now();
        jdbi.useHandle(handle -> {
            handle.createUpdate(
                            "DELETE FROM onec_hospedajes_comunicacion " +
                                    "WHERE _referencia = :referencia AND _status = 'REJECTED' " +
                                    "AND _numero_lote IS NULL")
                    .bind("referencia", referencia)
                    .execute();
            handle.createUpdate(
                            "INSERT INTO onec_hospedajes_comunicacion " +
                                    "(_id, _referencia, _orden, _status, _error, _created_at, _updated_at) " +
                                    "VALUES (:id, :referencia, 0, 'REJECTED', :error, :now, :now)")
                    .bind("id", UUID.randomUUID())
                    .bind("referencia", referencia)
                    .bind("error", error)
                    .bind("now", now)
                    .execute();
        });
    }

    /**
     * Whether a comunicación for this referencia has already been submitted and not rejected — used
     * for idempotency so a re-save does not resubmit, while a rejected parte can still be retried.
     */
    public boolean hasActiveSubmission(String referencia) {
        return jdbi.withHandle(handle -> handle.createQuery(
                        "SELECT COUNT(*) FROM onec_hospedajes_comunicacion " +
                                "WHERE _referencia = :referencia AND _status IN ('SUBMITTED', 'REGISTERED')")
                .bind("referencia", referencia)
                .mapTo(int.class)
                .one()) > 0;
    }

    /** Distinct batch numbers still awaiting a definitive per-comunicación outcome. */
    public List<String> findUnreconciledLotes(int limit) {
        return jdbi.withHandle(handle -> handle.createQuery(
                        "SELECT DISTINCT _numero_lote FROM onec_hospedajes_comunicacion " +
                                "WHERE _status = 'SUBMITTED' AND _numero_lote IS NOT NULL " +
                                "ORDER BY _numero_lote LIMIT :limit")
                .bind("limit", limit)
                .mapTo(String.class)
                .list());
    }

    public void markRegistered(String numeroLote, int orden, String codigoComunicacion) {
        jdbi.useHandle(handle -> handle.createUpdate(
                        "UPDATE onec_hospedajes_comunicacion " +
                                "SET _status = 'REGISTERED', _codigo_comunicacion = :codigo, " +
                                "_error = NULL, _updated_at = :now " +
                                "WHERE _numero_lote = :lote AND _orden = :orden AND _status = 'SUBMITTED'")
                .bind("codigo", codigoComunicacion)
                .bind("lote", numeroLote)
                .bind("orden", orden)
                .bind("now", LocalDateTime.now())
                .execute());
    }

    public void markRejected(String numeroLote, int orden, String error) {
        jdbi.useHandle(handle -> handle.createUpdate(
                        "UPDATE onec_hospedajes_comunicacion " +
                                "SET _status = 'REJECTED', _error = :error, _updated_at = :now " +
                                "WHERE _numero_lote = :lote AND _orden = :orden AND _status = 'SUBMITTED'")
                .bind("error", error)
                .bind("lote", numeroLote)
                .bind("orden", orden)
                .bind("now", LocalDateTime.now())
                .execute());
    }

    public void markCancelled(String codigoComunicacion) {
        jdbi.useHandle(handle -> handle.createUpdate(
                        "UPDATE onec_hospedajes_comunicacion " +
                                "SET _status = 'CANCELLED', _updated_at = :now " +
                                "WHERE _codigo_comunicacion = :codigo")
                .bind("codigo", codigoComunicacion)
                .bind("now", LocalDateTime.now())
                .execute());
    }
}
