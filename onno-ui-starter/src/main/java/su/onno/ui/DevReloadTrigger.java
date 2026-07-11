package su.onno.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Dev-mode reload signal: watches a trigger file and broadcasts a {@code reload} SSE event — a full
 * browser refresh on every connected client — whenever the file is touched. The automatic reload
 * (a devtools restart changing the {@code bootId}) covers recompiles; this covers everything a
 * restart doesn't announce, and gives scripts and agents an explicit "refresh now" with no HTTP
 * endpoint, no auth, no CSRF:
 *
 * <pre>touch .onno-reload</pre>
 *
 * <p>The file is polled by modification time (a {@code WatchService} would need the parent directory
 * to exist and buys nothing at a 500&nbsp;ms cadence). A file already present at boot does not fire —
 * only a touch after startup does. Inert (never started) outside dev mode.
 */
public class DevReloadTrigger {

    private static final Logger log = LoggerFactory.getLogger(DevReloadTrigger.class);
    private static final long POLL_MILLIS = 500;

    private final Path file;
    private final UiEventPublisher publisher;
    private final ScheduledExecutorService poller;
    private long lastSeenMillis;

    /** The inert non-dev instance: nothing to watch, nothing to stop. */
    private DevReloadTrigger() {
        this.file = null;
        this.publisher = null;
        this.poller = null;
    }

    public static DevReloadTrigger disabled() {
        return new DevReloadTrigger();
    }

    public DevReloadTrigger(Path file, UiEventPublisher publisher) {
        this.file = file;
        this.publisher = publisher;
        this.lastSeenMillis = currentMtime();
        this.poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "onno-dev-reload-trigger");
            t.setDaemon(true);
            return t;
        });
        poller.scheduleWithFixedDelay(this::poll, POLL_MILLIS, POLL_MILLIS, TimeUnit.MILLISECONDS);
        log.info("Dev reload trigger active — touch {} to reload every connected browser.",
                file.toAbsolutePath());
    }

    private void poll() {
        long mtime = currentMtime();
        if (mtime > lastSeenMillis) {
            lastSeenMillis = mtime;
            log.info("Reload trigger touched — telling connected browsers to refresh.");
            publisher.publishReload();
        }
    }

    /** The file's mtime, or 0 when absent — so a file created after boot fires on first sight. */
    private long currentMtime() {
        try {
            return Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : 0;
        } catch (IOException e) {
            return lastSeenMillis;
        }
    }

    public void stop() {
        if (poller != null) {
            poller.shutdownNow();
        }
    }
}
