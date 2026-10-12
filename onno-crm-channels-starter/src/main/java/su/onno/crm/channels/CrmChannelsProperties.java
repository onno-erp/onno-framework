package su.onno.crm.channels;

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

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { this.enabled = value; }
        public String getClientFile() { return clientFile; }
        public void setClientFile(String value) { this.clientFile = value; }
        public String getTokensFile() { return tokensFile; }
        public void setTokensFile(String value) { this.tokensFile = value; }
        public String getRedirectUri() { return redirectUri; }
        public void setRedirectUri(String value) { this.redirectUri = value; }
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
