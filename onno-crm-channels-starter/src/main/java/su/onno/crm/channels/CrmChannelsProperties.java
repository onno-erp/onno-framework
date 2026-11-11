package su.onno.crm.channels;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Provider setup; all adapters default disabled. */
@ConfigurationProperties("onno.crm.channels")
public class CrmChannelsProperties {
    private final Telegram telegram = new Telegram();
    private final Gmail gmail = new Gmail();
    private final Instagram instagram = new Instagram();
    private final Whatsapp whatsapp = new Whatsapp();

    public Telegram getTelegram() { return telegram; }
    public Gmail getGmail() { return gmail; }
    public Instagram getInstagram() { return instagram; }
    public Whatsapp getWhatsapp() { return whatsapp; }

    public static class Telegram {
        /**
         * Enable the Telegram bot adapter: one server-configured bot that serves ordinary bot chats
         * and, when the bot has business mode, per-user Telegram business connections.
         */
        private boolean enabled = false;
        /**
         * Accept ordinary (non-business) private chats with the bot into a shared Telegram inbox.
         * When false the bot serves Telegram business connections only: ordinary chats are ignored,
         * no ordinary inbox is created, and replies to existing ordinary chats are not sent.
         */
        private boolean regularChatsEnabled = true;
        /** Bot token; supply through external secret configuration. */
        private String token = null;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { this.enabled = value; }
        public boolean isRegularChatsEnabled() { return regularChatsEnabled; }
        public void setRegularChatsEnabled(boolean value) { this.regularChatsEnabled = value; }
        public String getToken() { return token; }
        public void setToken(String value) { this.token = value; }
    }

    public static class Gmail {
        /**
         * Enable the Gmail OAuth adapter. Each connected account (shared or personal) authorizes
         * its own mailbox and gets its own inbox.
         */
        private boolean enabled = false;
        /** Google OAuth client JSON file outside the application artifact. */
        private String clientFile = System.getProperty("user.home") + "/.config/onno/gmail-client.json";
        /** Private writable Google token JSON file. */
        private String tokensFile = System.getProperty("user.home") + "/.config/onno/gmail-tokens.json";
        /** Exact registered Google OAuth callback URI; use HTTPS in deployments. */
        private String redirectUri = "http://127.0.0.1:8090/api/crm/gmail/callback";
        /**
         * Most Inbox messages a mailbox's first import brings in, newest first. A re-scan after
         * Gmail's history expired applies it to Inbox and to sent mail. 0 removes the count limit.
         */
        private int initialImportLimit = 500;
        /**
         * Age limit, in days, of the Inbox messages a mailbox's first import brings in. 0 removes the
         * age limit. A re-scan after Gmail's history expired starts from the mailbox's latest
         * imported email instead.
         */
        private int initialImportDays = 90;
        /** Each mailbox's older mail, imported once as history after its first import. */
        private HistoryImport historyImport = new HistoryImport();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { this.enabled = value; }
        public String getClientFile() { return clientFile; }
        public void setClientFile(String value) { this.clientFile = value; }
        public String getTokensFile() { return tokensFile; }
        public void setTokensFile(String value) { this.tokensFile = value; }
        public String getRedirectUri() { return redirectUri; }
        public void setRedirectUri(String value) { this.redirectUri = value; }
        public int getInitialImportLimit() { return initialImportLimit; }
        public void setInitialImportLimit(int value) {
            if (value < 0) throw new IllegalArgumentException("onno.crm.channels.gmail.initial-import-limit must be 0 or more");
            this.initialImportLimit = value;
        }
        public int getInitialImportDays() { return initialImportDays; }
        public void setInitialImportDays(int value) {
            if (value < 0) throw new IllegalArgumentException("onno.crm.channels.gmail.initial-import-days must be 0 or more");
            this.initialImportDays = value;
        }
        public HistoryImport getHistoryImport() { return historyImport; }
        public void setHistoryImport(HistoryImport value) { this.historyImport = value == null ? new HistoryImport() : value; }
    }

    /**
     * A mailbox's older mail, imported once after its first import finished: every thread under the
     * listed labels, newest first, a few threads per synchronization round so live mail keeps flowing.
     * The mail arrives as history: read, its new conversations closed, no notifications or
     * automation ({@code su.onno.crm.service.CrmHistoryImport}). Only the text is downloaded; each file
     * stays in Gmail and is fetched when someone opens it, so the import costs neither disk nor
     * Gmail's download quota. Progress is kept per mailbox in {@code onno_crm_gmail_history_import};
     * delete a mailbox's row there to import again (already imported emails are skipped).
     */
    public static class HistoryImport {
        /** Import each mailbox's older mail once, as read history with its files left in Gmail. */
        private boolean enabled = false;
        /** Gmail labels whose threads are imported, in this order; {@code INBOX} by default, add {@code SENT} for archived threads the studio answered. */
        private List<String> labels = new ArrayList<>(List.of("INBOX"));
        /** Threads one mailbox imports per synchronization round (a round runs every 15 seconds). */
        private int threadsPerRound = 20;
        /** Gmail categories left out; {@code CATEGORY_PROMOTIONS} and {@code CATEGORY_SOCIAL} (newsletters, social-network notices) by default. */
        private List<String> skipCategories = new ArrayList<>(List.of("CATEGORY_PROMOTIONS", "CATEGORY_SOCIAL"));

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { this.enabled = value; }
        public List<String> getLabels() { return labels; }
        public void setLabels(List<String> value) {
            if (value == null || value.isEmpty() || value.stream().anyMatch(label -> label == null || !label.matches("[A-Za-z0-9_-]{1,100}")))
                throw new IllegalArgumentException("onno.crm.channels.gmail.history-import.labels must list Gmail label ids such as INBOX or SENT");
            this.labels = new ArrayList<>(value);
        }
        public int getThreadsPerRound() { return threadsPerRound; }
        public void setThreadsPerRound(int value) {
            if (value < 1 || value > 100) throw new IllegalArgumentException("onno.crm.channels.gmail.history-import.threads-per-round must be between 1 and 100");
            this.threadsPerRound = value;
        }
        public List<String> getSkipCategories() { return skipCategories; }
        public void setSkipCategories(List<String> value) { this.skipCategories = value == null ? new ArrayList<>() : new ArrayList<>(value); }
    }

    public static class Instagram {
        /** Enable the single-account Instagram polling adapter. */
        private boolean enabled = false;
        /** Private Instagram token JSON file. */
        private String tokensFile = System.getProperty("user.home") + "/.config/onno/instagram-tokens.json";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { this.enabled = value; }
        public String getTokensFile() { return tokensFile; }
        public void setTokensFile(String value) { this.tokensFile = value; }
    }

    public static class Whatsapp {
        /** Enable the single-number WhatsApp Cloud API adapter. */
        private boolean enabled = false;
        /** Private WhatsApp token and webhook signing JSON file. */
        private String tokensFile = System.getProperty("user.home") + "/.config/onno/whatsapp-tokens.json";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { this.enabled = value; }
        public String getTokensFile() { return tokensFile; }
        public void setTokensFile(String value) { this.tokensFile = value; }
    }
}
