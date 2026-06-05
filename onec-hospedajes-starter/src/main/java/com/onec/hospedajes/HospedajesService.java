package com.onec.hospedajes;

import com.onec.hospedajes.model.Comunicacion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Application-facing facade over {@link HospedajesClient} that also records the async lifecycle in
 * the {@link HospedajesCommunicationLog}. The host application maps its own domain (e.g. a booking
 * and its guests) into {@link Comunicacion} objects; this service handles submission, audit, and
 * reconciliation of the resulting batch.
 */
public class HospedajesService {

    private static final Logger log = LoggerFactory.getLogger(HospedajesService.class);

    /** SES error code for a validation failure ({@code "Error de validación"}); reused for local rejections. */
    private static final int ERROR_VALIDACION = 10121;

    private final HospedajesClient client;
    private final HospedajesCommunicationLog communicationLog;
    private final ParteValidator validator = new ParteValidator();

    public HospedajesService(HospedajesClient client, HospedajesCommunicationLog communicationLog) {
        this.client = client;
        this.communicationLog = communicationLog;
    }

    /**
     * Submit partes de viajeros for one establishment and record each comunicación as SUBMITTED at
     * its 1-based position in the batch (the {@code orden} the service uses to report outcomes).
     *
     * <p>Each parte is first checked against {@link ParteValidator} (the MIR conditional rules the
     * XSD does not express). Partes that fail are recorded as locally REJECTED — with the precise
     * reason — and excluded from the batch, so they never reach the service to come back as an
     * opaque async {@code 10121}; only the valid subset is submitted. If nothing is valid, no call
     * is made and a synthetic rejected result is returned.
     */
    public ComunicacionResult registrar(String codigoEstablecimiento, List<Comunicacion> comunicaciones) {
        List<Comunicacion> valid = new ArrayList<>();
        int rejected = 0;
        for (Comunicacion c : comunicaciones) {
            List<String> violations = validator.validate(c);
            if (violations.isEmpty()) {
                valid.add(c);
                continue;
            }
            rejected++;
            String referencia = c.getContrato() == null ? null : c.getContrato().getReferencia();
            String detail = "Error de validación local: " + String.join("; ", violations);
            log.warn("Parte {} rejected before submit by local validation: {}", referencia, detail);
            if (communicationLog != null) {
                communicationLog.recordLocalRejection(referencia, detail);
            }
        }

        if (valid.isEmpty()) {
            return new ComunicacionResult(ERROR_VALIDACION,
                    "Error de validación local: " + rejected + " comunicación(es) rechazada(s) antes del envío",
                    null);
        }

        ComunicacionResult result = client.altaPartes(codigoEstablecimiento, valid);
        if (result.accepted() && communicationLog != null) {
            int orden = 1;
            for (Comunicacion c : valid) {
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
