package com.example.integration.hospedajes;

import com.example.domain.documents.Booking;
import com.example.domain.enumerations.BookingStatus;
import com.onec.hospedajes.ComunicacionResult;
import com.onec.hospedajes.HospedajesCommunicationLog;
import com.onec.hospedajes.HospedajesService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.relational.core.mapping.event.AfterSaveCallback;

import java.util.List;

/**
 * Submits a parte de viajeros to SES.HOSPEDAJES when a {@link Booking} reaches
 * {@link BookingStatus#CHECKED_IN}. Idempotent via the communication ledger, so re-saving a
 * checked-in booking does not resubmit. SES failures are logged and never block the save.
 */
public class BookingHospedajesCallback implements AfterSaveCallback<Booking> {

    private static final Logger log = LoggerFactory.getLogger(BookingHospedajesCallback.class);

    private final HospedajesService hospedajes;
    private final HospedajesCommunicationLog ledger;
    private final BookingParteMapper mapper;

    public BookingHospedajesCallback(HospedajesService hospedajes,
                                     HospedajesCommunicationLog ledger,
                                     BookingParteMapper mapper) {
        this.hospedajes = hospedajes;
        this.ledger = ledger;
        this.mapper = mapper;
    }

    @Override
    public Booking onAfterSave(Booking booking) {
        try {
            if (booking.getStatus() != BookingStatus.CHECKED_IN) {
                return booking;
            }
            String referencia = booking.getNumber();
            if (referencia == null || ledger.hasActiveSubmission(referencia)) {
                return booking;
            }
            mapper.toParte(booking).ifPresentOrElse(parte -> {
                ComunicacionResult result =
                        hospedajes.registrar(parte.establishmentCode(), List.of(parte.comunicacion()));
                if (result.accepted()) {
                    log.info("SES.HOSPEDAJES accepted parte for booking {} -> lote {}",
                            referencia, result.numeroLote());
                } else {
                    log.warn("SES.HOSPEDAJES rejected parte for booking {}: {} {}",
                            referencia, result.codigo(), result.descripcion());
                }
            }, () -> log.debug("Booking {} not yet registrable (no establishment code or travelers)",
                    referencia));
        } catch (Exception e) {
            log.error("Failed to submit parte de viajeros for booking {}", booking.getNumber(), e);
        }
        return booking;
    }
}
