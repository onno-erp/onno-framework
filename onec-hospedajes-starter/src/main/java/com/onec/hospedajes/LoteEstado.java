package com.onec.hospedajes;

import java.util.List;

/**
 * Result of a {@code consultaLote}: the query outcome ({@link #codigo()}/{@link #descripcion()}),
 * the batch processing state ({@link #codigoEstado()}/{@link #descEstado()}) and the per-comunicación
 * results. Per the WSDL ({@code wscomunicacionType} / {@code resultadoComunicacion}) each result is
 * keyed by {@link ComunicacionEstado#orden()} — the 1-based position of the comunicación in the
 * submitted batch — NOT by the caller's referencia.
 */
public record LoteEstado(int codigo, String descripcion, String numeroLote,
                         int codigoEstado, String descEstado, List<ComunicacionEstado> comunicaciones) {

    /**
     * Outcome of one comunicación within a batch. A non-null {@link #codigoComunicacion()} means it
     * was registered; a non-null {@link #error()} means it was rejected and must be corrected and
     * resubmitted.
     */
    public record ComunicacionEstado(int orden, boolean anulada,
                                     String codigoComunicacion, String tipoError, String error) {

        public boolean registered() {
            return codigoComunicacion != null && !codigoComunicacion.isBlank();
        }
    }
}
