package su.onno.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Periodically asks onno-cloud for the latest published framework version and, when it is newer than
 * the running one, flips {@link #status()} to "update available" — which the web client reads off
 * {@code /api/config} and renders as a dismissible banner.
 *
 * <p>Deliberately self-contained: a daemon {@link ScheduledExecutorService} (the same pattern as
 * {@link UiEventPublisher}'s keepalive, so the framework needs no {@code @EnableScheduling}) and the
 * JDK {@link HttpClient} (no new dependency). Every failure path — disabled, unknown local version,
 * network error, non-200, malformed body — is swallowed to a debug log and leaves the status at
 * "no update", so a flaky network never produces a wrong or alarming notice.
 */
public class UpdateChecker {

    private static final Logger log = LoggerFactory.getLogger(UpdateChecker.class);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final UpdateProperties props;
    private final String currentVersion;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final ScheduledExecutorService scheduler;

    private volatile UpdateStatus status;

    public UpdateChecker(UpdateProperties props, String currentVersion, ObjectMapper mapper) {
        this.props = props;
        this.currentVersion = currentVersion == null ? "" : currentVersion.trim();
        this.mapper = mapper;
        this.status = UpdateStatus.none(this.currentVersion);
        this.http = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(threadFactory());
    }

    /** Schedules the recurring check. A no-op when disabled or the local version is unknown. */
    @PostConstruct
    public void start() {
        if (!props.isEnabled()) {
            log.debug("Update check disabled (onno.ui.update-check.enabled=false)");
            return;
        }
        if (currentVersion.isEmpty()) {
            // No baked version (e.g. an IDE run without processResources) — we can't compare, so we
            // never signal an update rather than risk a false positive.
            log.debug("Update check skipped: running framework version is unknown");
            return;
        }
        long delay = Math.max(1, props.getInitialDelay().toSeconds());
        long period = Math.max(60, props.getInterval().toSeconds());
        scheduler.scheduleWithFixedDelay(this::check, delay, period, TimeUnit.SECONDS);
    }

    /** The last-known result; never null. */
    public UpdateStatus status() {
        return status;
    }

    /** One poll of the cloud. Package-private so tests can drive it directly. */
    void check() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(props.getUrl()))
                    .timeout(HTTP_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() == 204) {
                // Cloud has nothing announced — clear any prior signal.
                status = UpdateStatus.none(currentVersion);
                return;
            }
            if (res.statusCode() != 200 || res.body() == null || res.body().isBlank()) {
                log.debug("Update check: non-200 ({}) from {}", res.statusCode(), props.getUrl());
                return;
            }

            JsonNode body = mapper.readTree(res.body());
            String latest = text(body, "version");
            if (latest == null || latest.isBlank()) {
                return;
            }
            String releaseUrl = text(body, "notesUrl");
            boolean newer = isNewer(latest, currentVersion);
            status = new UpdateStatus(newer, currentVersion, latest.trim(), releaseUrl, Instant.now());
            if (newer) {
                log.info("A newer onno-framework version is available: {} (running {})",
                        latest.trim(), currentVersion);
            }
        } catch (Exception e) {
            // Fail-silent: offline, DNS failure, timeout, bad JSON — none of it should surface.
            log.debug("Update check failed (ignored): {}", e.toString());
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /**
     * True when {@code latest} is a strictly higher version than {@code current}. Compares dotted
     * numeric segments left to right; a build with no pre-release suffix ({@code 1.0.0}) is newer than
     * the same numbers with one ({@code 1.0.0-RC1}), so a SNAPSHOT never out-ranks its release.
     * Anything unparseable yields false — we don't nag on a version string we don't understand.
     */
    static boolean isNewer(String latest, String current) {
        if (latest == null || current == null || latest.isBlank() || current.isBlank()) {
            return false;
        }
        try {
            long[] l = numbers(latest);
            long[] c = numbers(current);
            int n = Math.max(l.length, c.length);
            for (int i = 0; i < n; i++) {
                long a = i < l.length ? l[i] : 0;
                long b = i < c.length ? c[i] : 0;
                if (a != b) {
                    return a > b;
                }
            }
            // Equal numeric core: a release outranks a pre-release of the same numbers.
            return current.contains("-") && !latest.contains("-");
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static long[] numbers(String version) {
        String core = version.trim().split("[-+]", 2)[0];   // strip pre-release / build metadata
        String[] parts = core.split("\\.");
        long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = parts[i].isBlank() ? 0 : Long.parseLong(parts[i]);
        }
        return out;
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    private static ThreadFactory threadFactory() {
        return r -> {
            Thread t = new Thread(r, "onno-update-check");
            t.setDaemon(true);
            return t;
        };
    }
}
