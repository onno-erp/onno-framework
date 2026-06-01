package com.onec.hospedajes;

/**
 * Synchronous outcome of a submit/cancel request. A {@code codigo} of 0 means the request was
 * accepted for asynchronous processing — NOT that the comunicaciones were validated. Use the
 * returned {@link #numeroLote()} with {@code consultaLote} to obtain the real per-comunicación
 * result and the assigned comunicación codes.
 */
public record ComunicacionResult(int codigo, String descripcion, String numeroLote) {

    public boolean accepted() {
        return codigo == 0;
    }
}
