package su.onno.jobs;

import java.time.Instant;
import java.util.UUID;

public interface BackgroundJobs {

    UUID enqueue(BackgroundTask task);

    UUID schedule(BackgroundTask task, Instant scheduledAt);

    void cancel(UUID jobId);
}
