package com.onec.hospedajes;

/** Header {@code tipoOperacion}: the operation requested of the Comunicación service. */
public enum TipoOperacion {
    ALTA("A"),
    CONSULTA("C"),
    ANULACION("B");

    private final String code;

    TipoOperacion(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
