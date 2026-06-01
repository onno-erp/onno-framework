package com.onec.hospedajes;

import java.util.List;

/** Result of a {@code consultaLote}: the batch status plus the per-comunicación outcome. */
public record LoteEstado(int codigo, String descripcion, String numeroLote, List<ComunicacionEstado> comunicaciones) {

    /** Outcome of one comunicación within a batch. A non-null {@link #codigoComunicacion()} means
     *  it was registered; a non-null {@link #error()} means it was rejected and must be resubmitted. */
    public record ComunicacionEstado(String referencia, String codigoComunicacion, String error) {

        public boolean registered() {
            return codigoComunicacion != null && !codigoComunicacion.isBlank();
        }
    }
}
