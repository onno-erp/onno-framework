package su.onno.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the web layer behaves: the REST API, the command path, the event stream and live reload.
 * Everything that configures what the UI looks like (mount path, branding, language, theme) lives in
 * {@code su.onno.web.UiProperties} ({@code onno.ui.*}, onno-web-starter) instead.
 */
@ConfigurationProperties(prefix = "onno.web")
public class WebProperties {

    /**
     * Master switch for the web starter: the REST API, the event stream and the served UI. The
     * service layer it calls (query/command services, access control) stays on, so MCP, import and
     * application code keep working. Also gated on a {@code MetadataRegistry} bean being present.
     */
    private boolean enabled = true;

    /**
     * When true, the application accepts no writes: every create, update, delete, post/unpost and
     * command — over REST, MCP and starter endpoints alike — is rejected with {@code 403}, and the
     * model reports no write rights so the UI renders read-only.
     */
    private boolean readOnly = false;

    /**
     * Live-reload dev mode. When on, the {@code ready} ack of the {@code /api/events} SSE stream
     * marks the server as a development instance, and the web client answers a server restart (a
     * changed {@code bootId} across a stream reconnect) with a full page reload — so a devtools
     * restart cycle ends with the browser refreshing itself: save a file, see the change. Unset (the
     * default) auto-detects: dev mode turns on exactly when {@code spring-boot-devtools} is on the
     * classpath — present under {@code bootRun}/exploded-classpath runs, absent from the production
     * boot jar — so a plain deployment never reloads a user's page on redeploy. Set explicitly to
     * force it either way (e.g. {@code true} for a hosted preview instance that rebuilds without
     * devtools).
     */
    private Boolean devMode;

    /**
     * The dev-mode reload trigger file, resolved against the working directory. While dev mode is
     * on, touching this file ({@code touch .onno-reload}) broadcasts a {@code reload} event over the
     * {@code /api/events} SSE stream and every connected browser does a full refresh — an explicit
     * "refresh now" for scripts and agents, with no HTTP endpoint or CSRF dance. Ignored outside dev
     * mode.
     */
    private String devReloadTrigger = ".onno-reload";

    /** List feeds — how many rows a keyset window returns by default. */
    private ListFeed list = new ListFeed();

    /** Batches — how a command or delete over a multi-record selection runs. */
    private Batch batch = new Batch();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public Boolean getDevMode() {
        return devMode;
    }

    public void setDevMode(Boolean devMode) {
        this.devMode = devMode;
    }

    public String getDevReloadTrigger() {
        return devReloadTrigger;
    }

    public void setDevReloadTrigger(String devReloadTrigger) {
        this.devReloadTrigger = devReloadTrigger;
    }

    public ListFeed getList() {
        return list;
    }

    public void setList(ListFeed list) {
        this.list = list;
    }

    public Batch getBatch() {
        return batch;
    }

    public void setBatch(Batch batch) {
        this.batch = batch;
    }

    /** List feeds, applied unless a list asks for its own window size. */
    public static class ListFeed {

        /** Default rows returned per keyset window. Clamped to the server ceiling (500). */
        private int pageSize = 50;

        public int getPageSize() {
            return pageSize;
        }

        public void setPageSize(int pageSize) {
            this.pageSize = pageSize;
        }
    }

    /**
     * Batches. A command or delete over a multi-record selection targets every id in one request and
     * the server runs the per-id handler over the set. Each id runs in its own transaction
     * (continue-on-failure), so the set can be resolved concurrently.
     */
    public static class Batch {

        /**
         * Maximum number of records a batch resolves in parallel per request. Each id runs its own
         * transaction on a worker thread, so keep this comfortably below the datasource's
         * {@code maximum-pool-size} or the fan-out will starve the JDBC pool. {@code 1} runs the set
         * sequentially. Parallel document deletes reverse postings concurrently, which can contend on
         * the same accumulation-register rows — lower this (or set {@code 1}) if you hit lock
         * timeouts on heavily-shared registers.
         */
        private int parallelism = 4;

        public int getParallelism() {
            return parallelism;
        }

        public void setParallelism(int parallelism) {
            this.parallelism = parallelism;
        }
    }
}
