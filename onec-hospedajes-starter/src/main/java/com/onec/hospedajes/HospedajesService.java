package com.onec.hospedajes;

import com.onec.hospedajes.model.Comunicacion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Application-facing facade over {@link HospedajesClient} that also records the async lifecycle in
 * the {@link HospedajesCommunicationLog}. The host application maps its own domain (e.g. a booking
 * and its guests) into {@link Comunicacion} objects; this service handles submission, audit, and
 * reconciliation of the resulting batch.
 */
public class HospedajesService {

    private static final Logger log = LoggerFactory.getLogger(HospedajesService.class);

    private final HospedajesClient client;
    private final HospedajesCommunicationLog communicationLog;

    public HospedajesService(HospedajesClient client, HospedajesCommunicationLog communicationLog) {
        this.client = client;
        this.communicationLog = communicationLog;
    }

    /**
     * Submit partes de viajeros for one establishment and record each comunicación as SUBMITTED at
     * its 1-based position in the batch (the {@code orden} the service uses to report outcomes).
     */
    public ComunicacionResult registrar(String codigoEstablecimiento, List<Comunicacion> comunicaciones) {
        ComunicacionResult result = client.altaPartes(codigoEstablecimiento, comunicaciones);
        if (result.accepted() && communicationLog != null) {
            int orden = 1;
            for (Comunicacion c : comunicaciones) {
                String referencia = c.getContrato() == null ? null : c.getContrato().getReferencia();
                communicationLog.recordSubmission(referencia, result.numeroLote(), orden);
                orden++;
            }
        }
        return result;
    }

    /**
     * Poll outstanding batches for their definitive outcome and update the log. Intended to be
     * triggered periodically (e.g. a scheduled job) so the 24h SLA is met and rejected partes are
     * surfaced for correction and resubmission.
     */
    public int reconcile(int maxLotes) {
        if (communicationLog == null) {
            return 0;
        }
        int updated = 0;
        for (String lote : communicationLog.findUnreconciledLotes(maxLotes)) {
            LoteEstado estado = client.consultaLote(lote);
            for (LoteEstado.ComunicacionEstado c : estado.comunicaciones()) {
                if (c.orden() < 0) {
                    continue;
                }
                if (c.registered()) {
                    communicationLog.markRegistered(lote, c.orden(), c.codigoComunicacion());
                } else if (c.error() != null) {
                    String detail = (c.tipoError() == null ? "" : c.tipoError() + ": ") + c.error();
                    communicationLog.markRejected(lote, c.orden(), detail);
                    log.warn("SES.HOSPEDAJES rejected orden {} in lote {}: {}", c.orden(), lote, detail);
                } else {
                    continue; // still processing — leave SUBMITTED for the next poll
                }
                updated++;
            }
        }
        return updated;
    }

    public ComunicacionResult anular(List<String> codigosComunicacion) {
        ComunicacionResult result = client.anularComunicaciones(codigosComunicacion);
        if (result.accepted() && communicationLog != null) {
            codigosComunicacion.forEach(communicationLog::markCancelled);
        }
        return result;
    }
}
