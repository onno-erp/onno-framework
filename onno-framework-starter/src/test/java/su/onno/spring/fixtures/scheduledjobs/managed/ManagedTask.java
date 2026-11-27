package su.onno.spring.fixtures.scheduledjobs.managed;

import su.onno.annotations.ScheduledJob;
import su.onno.jobs.BackgroundTask;

@org.springframework.stereotype.Component
@ScheduledJob(name = "ManagedTask", cron = "0 0 2 * * *")
public class ManagedTask implements BackgroundTask {
    @Override
    public void execute() {}
}
