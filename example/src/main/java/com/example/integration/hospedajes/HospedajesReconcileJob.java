package com.example.integration.hospedajes;

import com.onec.hospedajes.HospedajesService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Periodically polls SES.HOSPEDAJES for the outcome of outstanding batches so registered/rejected
 * partes are reflected in the ledger within the 24h SLA. Interval is configurable via
 * {@code onec.hospedajes.reconcile-interval-ms} (default 15 minutes).
 */
public class HospedajesReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(HospedajesReconcileJob.class);

    private final HospedajesService hospedajes;

    public HospedajesReconcileJob(HospedajesService hospedajes) {
        this.hospedajes = hospedajes;
    }

    @Scheduled(
            fixedDelayString = "${onec.hospedajes.reconcile-interval-ms:900000}",
            initialDelayString = "${onec.hospedajes.reconcile-initial-delay-ms:60000}")
    public void reconcile() {
        try {
            int updated = hospedajes.reconcile(50);
            if (updated > 0) {
                log.info("Reconciled {} SES.HOSPEDAJES comunicaciones", updated);
            }
        } catch (Exception e) {
            log.warn("SES.HOSPEDAJES reconcile poll failed", e);
        }
    }
}
