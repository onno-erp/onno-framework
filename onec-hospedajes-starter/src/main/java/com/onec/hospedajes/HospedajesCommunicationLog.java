package com.onec.hospedajes;

import org.jdbi.v3.core.Jdbi;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Audit + reconciliation ledger for partes sent to SES.HOSPEDAJES. One row per comunicación,
 * keyed by the caller's contract {@code referencia}, tracking the async lifecycle:
 * SUBMITTED → (REGISTERED | REJECTED) and later CANCELLED.
 */
public class HospedajesCommunicationLog {

    public enum Status { SUBMITTED, REGISTERED, REJECTED, CANCELLED }

    public record Entry(String referencia, String numeroLote, String codigoComunicacion, String status) {
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
                        "_codigo_comunicacion VARCHAR(50), " +
                        "_status VARCHAR(32) NOT NULL, " +
                        "_error VARCHAR(1000), " +
                        "_created_at TIMESTAMP NOT NULL, " +
                        "_updated_at TIMESTAMP NOT NULL)"));
    }

    public void recordSubmission(String referencia, String numeroLote) {
        LocalDateTime now = LocalDateTime.now();
        jdbi.useHandle(handle -> handle.createUpdate(
                        "INSERT INTO onec_hospedajes_comunicacion " +
                                "(_id, _referencia, _numero_lote, _status, _created_at, _updated_at) " +
                                "VALUES (:id, :referencia, :lote, 'SUBMITTED', :now, :now)")
                .bind("id", UUID.randomUUID())
                .bind("referencia", referencia)
                .bind("lote", numeroLote)
                .bind("now", now)
                .execute());
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

    public void markRegistered(String referencia, String numeroLote, String codigoComunicacion) {
        jdbi.useHandle(handle -> handle.createUpdate(
                        "UPDATE onec_hospedajes_comunicacion " +
                                "SET _status = 'REGISTERED', _codigo_comunicacion = :codigo, " +
                                "_error = NULL, _updated_at = :now " +
                                "WHERE _referencia = :referencia AND _numero_lote = :lote AND _status = 'SUBMITTED'")
                .bind("codigo", codigoComunicacion)
                .bind("referencia", referencia)
                .bind("lote", numeroLote)
                .bind("now", LocalDateTime.now())
                .execute());
    }

    public void markRejected(String referencia, String numeroLote, String error) {
        jdbi.useHandle(handle -> handle.createUpdate(
                        "UPDATE onec_hospedajes_comunicacion " +
                                "SET _status = 'REJECTED', _error = :error, _updated_at = :now " +
                                "WHERE _referencia = :referencia AND _numero_lote = :lote AND _status = 'SUBMITTED'")
                .bind("error", error)
                .bind("referencia", referencia)
                .bind("lote", numeroLote)
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
