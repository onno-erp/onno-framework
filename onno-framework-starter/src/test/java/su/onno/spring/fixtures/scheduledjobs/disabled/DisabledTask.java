package su.onno.spring.fixtures.scheduledjobs.disabled;

import su.onno.annotations.ScheduledJob;
import su.onno.jobs.BackgroundTask;

@org.springframework.context.annotation.Profile("disabled-jobs")
@ScheduledJob(name = "DisabledTask", cron = "0 0 2 * * *")
public class DisabledTask implements BackgroundTask {
    @Override
    public void execute() {}
}
