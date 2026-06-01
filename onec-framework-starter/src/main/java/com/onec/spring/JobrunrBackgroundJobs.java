package com.onec.spring;

import com.onec.jobs.BackgroundJobs;
import com.onec.jobs.BackgroundTask;

import org.jobrunr.jobs.JobId;
import org.jobrunr.scheduling.JobScheduler;

import java.time.Instant;
import java.util.UUID;

public class JobrunrBackgroundJobs implements BackgroundJobs {

    private final JobScheduler jobScheduler;

    public JobrunrBackgroundJobs(JobScheduler jobScheduler) {
        this.jobScheduler = jobScheduler;
    }

    @Override
    public UUID enqueue(BackgroundTask task) {
        JobId jobId = jobScheduler.enqueue(task::execute);
        return jobId.asUUID();
    }

    @Override
    public UUID schedule(BackgroundTask task, Instant scheduledAt) {
        JobId jobId = jobScheduler.schedule(scheduledAt, task::execute);
        return jobId.asUUID();
    }

    @Override
    public void cancel(UUID jobId) {
        jobScheduler.delete(jobId);
    }
}
