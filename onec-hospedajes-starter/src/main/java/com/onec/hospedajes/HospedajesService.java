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

    /** Submit partes de viajeros and record each comunicación as SUBMITTED against its referencia. */
    public ComunicacionResult registrar(List<Comunicacion> comunicaciones) {
        ComunicacionResult result = client.altaPartes(comunicaciones);
        if (result.accepted() && communicationLog != null) {
            for (Comunicacion c : comunicaciones) {
                String referencia = c.getContrato() == null ? null : c.getContrato().getReferencia();
                if (referencia != null) {
                    communicationLog.recordSubmission(referencia, result.numeroLote());
                }
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
                if (c.referencia() == null) {
                    continue;
                }
                if (c.registered()) {
                    communicationLog.markRegistered(c.referencia(), lote, c.codigoComunicacion());
                } else if (c.error() != null) {
                    communicationLog.markRejected(c.referencia(), lote, c.error());
                    log.warn("SES.HOSPEDAJES rejected referencia {} in lote {}: {}",
                            c.referencia(), lote, c.error());
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
