package com.onec.hospedajes;

/** Header {@code tipoComunicacion}: the kind of communication being submitted. */
public enum TipoComunicacion {
    PARTE_VIAJEROS("PV"),
    RESERVA_HOSPEDAJE("RH"),
    ALQUILER_VEHICULO("AV"),
    RESERVA_VEHICULO("RV");

    private final String code;

    TipoComunicacion(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
