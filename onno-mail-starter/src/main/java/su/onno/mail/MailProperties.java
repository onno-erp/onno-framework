package su.onno.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "onno.mail")
public class MailProperties {

    /** Master switch for the mail starter. */
    private boolean enabled = true;

    /** Selects which {@code MailDispatcher} bean is active by its {@code name()}. */
    private String provider = "smtp";

    /** Default From: address when a {@link MailMessage} doesn't set one. */
    private String defaultFrom;

    /** Packages scanned for {@code MailTemplate}. Defaults to the application's base packages. */
    private List<String> basePackages = new ArrayList<>();

    /** Outbox relay batch size. */
    private int relayBatchSize = 50;

    /** Whether {@code MailService.queue(...)} writes to the outbox (true) or dispatches synchronously (false). */
    private boolean useOutbox = true;

    /** Charset used when rendering templates and building the MIME message. */
    private String encoding = "UTF-8";

    /** When true and a template renders HTML only, a plain-text alternative is derived so mail is multipart. */
    private boolean derivePlainText = true;

    @NestedConfigurationProperty
    private Relay relay = new Relay();

    @NestedConfigurationProperty
    private File file = new File();

    @NestedConfigurationProperty
    private Http http = new Http();

    @NestedConfigurationProperty
    private Failover failover = new Failover();

    @NestedConfigurationProperty
    private Preview preview = new Preview();

    @NestedConfigurationProperty
    private Webhook webhook = new Webhook();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getDefaultFrom() { return defaultFrom; }
    public void setDefaultFrom(String defaultFrom) { this.defaultFrom = defaultFrom; }
    public List<String> getBasePackages() { return basePackages; }
    public void setBasePackages(List<String> basePackages) { this.basePackages = basePackages; }
    public int getRelayBatchSize() { return relayBatchSize; }
    public void setRelayBatchSize(int relayBatchSize) { this.relayBatchSize = relayBatchSize; }
    public boolean isUseOutbox() { return useOutbox; }
    public void setUseOutbox(boolean useOutbox) { this.useOutbox = useOutbox; }
    public String getEncoding() { return encoding; }
    public void setEncoding(String encoding) { this.encoding = encoding; }
    public boolean isDerivePlainText() { return derivePlainText; }
    public void setDerivePlainText(boolean derivePlainText) { this.derivePlainText = derivePlainText; }
    public Relay getRelay() { return relay; }
    public void setRelay(Relay relay) { this.relay = relay; }
    public File getFile() { return file; }
    public void setFile(File file) { this.file = file; }
    public Http getHttp() { return http; }
    public void setHttp(Http http) { this.http = http; }
    public Failover getFailover() { return failover; }
    public void setFailover(Failover failover) { this.failover = failover; }
    public Preview getPreview() { return preview; }
    public void setPreview(Preview preview) { this.preview = preview; }
    public Webhook getWebhook() { return webhook; }
    public void setWebhook(Webhook webhook) { this.webhook = webhook; }

    /** Background relay that drains the outbox on a fixed schedule. */
    public static class Relay {
        /** Whether the scheduled relay is active. Requires an outbox (DataSource). */
        private boolean enabled = true;
        /** Delay between relay runs, in milliseconds. */
        private long intervalMs = 30_000;
        /** Max delivery attempts before a message is marked FAILED. */
        private int maxAttempts = 5;
        /**
         * How long a message claimed by a relay may stay in {@code SENDING} before another worker reclaims it.
         * Guards against a worker that crashed mid-send; set comfortably above the slowest provider send time.
         */
        private long leaseTimeoutMs = 300_000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getIntervalMs() { return intervalMs; }
        public void setIntervalMs(long intervalMs) { this.intervalMs = intervalMs; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public long getLeaseTimeoutMs() { return leaseTimeoutMs; }
        public void setLeaseTimeoutMs(long leaseTimeoutMs) { this.leaseTimeoutMs = leaseTimeoutMs; }
    }

    /** Config for the {@code file} dev dispatcher, which writes messages to disk instead of sending. */
    public static class File {
        /** Directory where {@code .eml} files are written. */
        private String directory = "build/mail";

        public String getDirectory() { return directory; }
        public void setDirectory(String directory) { this.directory = directory; }
    }

    /** Config for the universal {@code http} dispatcher: POSTs a rendered JSON body to any provider's REST API. */
    public static class Http {
        /** Endpoint URL the message is POSTed to. */
        private String url;
        /** HTTP method (defaults to POST). */
        private String method = "POST";
        /** Static headers added to every request, e.g. {@code Authorization: Bearer xxx}. */
        private Map<String, String> headers = new LinkedHashMap<>();
        /**
         * Thymeleaf body template producing the provider-specific JSON payload. Resolved by the resource loader;
         * the {@link MailMessage} is exposed as {@code msg}. Example: {@code classpath:/mail/http/sendgrid.json}.
         */
        private String bodyTemplate;
        /** Highest HTTP status (inclusive) still treated as success. */
        private int successStatusMax = 299;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }
        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> headers) { this.headers = headers; }
        public String getBodyTemplate() { return bodyTemplate; }
        public void setBodyTemplate(String bodyTemplate) { this.bodyTemplate = bodyTemplate; }
        public int getSuccessStatusMax() { return successStatusMax; }
        public void setSuccessStatusMax(int successStatusMax) { this.successStatusMax = successStatusMax; }
    }

    /** Config for the {@code failover} composite dispatcher: tries each named provider in order. */
    public static class Failover {
        /** Ordered provider names to try, e.g. {@code [ses, smtp]}. Active when {@code provider=failover}. */
        private List<String> providers = new ArrayList<>();

        public List<String> getProviders() { return providers; }
        public void setProviders(List<String> providers) { this.providers = providers; }
    }

    /** Dev-only HTTP endpoints for listing and rendering templates. Disabled by default. */
    public static class Preview {
        /** Enables the dev-only template preview endpoints. Off by default. */
        private boolean enabled = false;
        /** Base path for the preview endpoints. */
        private String path = "/onno/mail/preview";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
    }

    /** Inbound provider webhook (bounces/complaints) that feeds the suppression list. */
    public static class Webhook {
        /** Enables the inbound delivery-event webhook that feeds the suppression list. Off by default. */
        private boolean enabled = false;
        /** Path the provider posts delivery events to. */
        private String path = "/onno/mail/events";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
    }
}
