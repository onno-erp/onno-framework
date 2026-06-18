package su.onno.spring;

import su.onno.annotations.ScheduledJob;
import su.onno.jobs.BackgroundTask;

import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;

import java.util.Map;

public class ScheduledJobRegistrar implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobRegistrar.class);

    private final ApplicationContext applicationContext;
    private final JobScheduler jobScheduler;

    public ScheduledJobRegistrar(ApplicationContext applicationContext, JobScheduler jobScheduler) {
        this.applicationContext = applicationContext;
        this.jobScheduler = jobScheduler;
    }

    @Override
    public void afterPropertiesSet() {
        Map<String, BackgroundTask> tasks = applicationContext.getBeansOfType(BackgroundTask.class);
        for (BackgroundTask task : tasks.values()) {
            ScheduledJob annotation = task.getClass().getAnnotation(ScheduledJob.class);
            if (annotation == null) continue;

            String jobName = annotation.name();
            String cron = annotation.cron();

            log.info("Registering scheduled job: {} with cron: {}", jobName, cron);
            jobScheduler.scheduleRecurrently(jobName, cron, task::execute);
        }
    }
}
