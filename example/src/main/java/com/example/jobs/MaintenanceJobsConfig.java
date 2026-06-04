package com.example.jobs;

import com.onec.repository.ConstantManager;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring scheduling and registers the demo maintenance job. The job is gated on the
 * {@code AutoArchiveEnabled} setting, so it's a worked example of a background process you can
 * turn on/off from the Settings page.
 */
@Configuration
@EnableScheduling
public class MaintenanceJobsConfig {

    @Bean
    public AutoArchiveJob autoArchiveJob(ConstantManager constants) {
        return new AutoArchiveJob(constants);
    }
}
