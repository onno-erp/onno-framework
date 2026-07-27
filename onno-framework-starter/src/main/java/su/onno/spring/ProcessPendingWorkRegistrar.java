package su.onno.spring;

import org.jobrunr.scheduling.JobScheduler;
import org.springframework.beans.factory.InitializingBean;
import su.onno.process.ProcessEngine;

import java.time.Duration;
import java.util.Objects;

/** Registers the durable timer/subprocess poller with the starter's JobRunr scheduler. */
public final class ProcessPendingWorkRegistrar implements InitializingBean {

    static final String JOB_ID = "onno-process-pending-work";

    private final ProcessEngine engine;
    private final JobScheduler scheduler;

    public ProcessPendingWorkRegistrar(ProcessEngine engine, JobScheduler scheduler) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public void afterPropertiesSet() {
        scheduler.scheduleRecurrently(JOB_ID, Duration.ofSeconds(5), () -> engine.runPending(100));
    }
}
