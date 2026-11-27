package su.onno.spring.fixtures.scheduledjobs.missing;

import su.onno.annotations.ScheduledJob;
import su.onno.jobs.BackgroundTask;


@ScheduledJob(name = "MissingTask", cron = "0 0 2 * * *")
public class MissingTask implements BackgroundTask {
    @Override
    public void execute() {}
}
