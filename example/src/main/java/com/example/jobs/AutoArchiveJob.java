package com.example.jobs;

import com.example.domain.constants.AutoArchiveEnabled;
import com.onec.repository.ConstantManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * A demo background procedure: every 15s it runs a "maintenance pass" — but only while the
 * {@code AutoArchiveEnabled} setting is on. Flip the switch on the Settings page to start/stop it;
 * the effect is visible in the application log. A real procedure would archive old records, send
 * digests, reconcile an integration, etc.
 */
public class AutoArchiveJob {

    private static final Logger log = LoggerFactory.getLogger(AutoArchiveJob.class);

    private final ConstantManager constants;
    private int passes = 0;

    public AutoArchiveJob(ConstantManager constants) {
        this.constants = constants;
    }

    @Scheduled(initialDelay = 8_000, fixedDelay = 15_000)
    public void run() {
        Boolean enabled = constants.get(AutoArchiveEnabled.class);
        if (!Boolean.TRUE.equals(enabled)) {
            log.info("[auto-archive] disabled — skipping (toggle it on in Settings to run)");
            return;
        }
        passes++;
        log.info("[auto-archive] enabled — running maintenance pass #{}", passes);
    }
}
