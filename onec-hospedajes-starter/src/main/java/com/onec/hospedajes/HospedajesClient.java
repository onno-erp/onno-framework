package com.onec.hospedajes;

import com.onec.hospedajes.model.Comunicacion;

import java.util.List;

/**
 * High-level client for the SES.HOSPEDAJES "Servicio de Comunicación" (RD 933/2021).
 *
 * <p>The service is asynchronous: {@link #altaPartes} returns a batch number ({@code numeroLote})
 * once a request is accepted, and the caller must later {@link #consultaLote} to learn which
 * comunicaciones were registered (and their codes) versus rejected.
 */
public interface HospedajesClient {

    /**
     * Submit an "alta" of partes de viajeros (tipoComunicacion=PV, tipoOperacion=A) for a single
     * establishment. All comunicaciones in one request belong to {@code codigoEstablecimiento}.
     */
    ComunicacionResult altaPartes(String codigoEstablecimiento, List<Comunicacion> comunicaciones);

    /** Cancel previously registered comunicaciones by their assigned codes (tipoOperacion=B). */
    ComunicacionResult anularComunicaciones(List<String> codigosComunicacion);

    /** Query the processing state and per-comunicación outcome of a batch. */
    LoteEstado consultaLote(String numeroLote);
}
