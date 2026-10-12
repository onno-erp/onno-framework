package su.onno.crm.channels;

import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import su.onno.crm.channels.gmail.GmailBridge;
import su.onno.crm.channels.gmail.GmailClient;
import su.onno.crm.channels.telegram.TelegramBusinessAccounts;
import su.onno.crm.service.CrmAccountProvider;
import su.onno.crm.service.CrmChannelConnection;
import su.onno.crm.service.CrmConnectionAccounts.Account;
import su.onno.crm.service.CrmConnectionAccounts.Scope;

/**
 * The account providers this module ships. Each is replaceable by declaring a bean with the same
 * name. Gmail and Telegram business manage per-account connections (shared or personal); the other
 * adapters run on one shared, server-configured account and are listed so the connections page can
 * explain how to enable them.
 */
@Configuration(proxyBeanMethods = false)
public class ConnectionProviders {
    private static final List<Scope> SHARED_OR_PERSONAL = List.of(Scope.SHARED, Scope.PERSONAL);

    @Bean @ConditionalOnMissingBean(name = "gmailAccountProvider")
    CrmAccountProvider gmailAccountProvider(CrmChannelsProperties properties, ObjectProvider<GmailBridge> bridges,
                                            ObjectProvider<GmailClient> clients) {
        return new CrmAccountProvider() {
            @Override public Descriptor descriptor() {
                var client = clients.getIfAvailable();
                boolean ready = properties.getGmail().isEnabled() && client != null && client.configured();
                return new Descriptor("gmail", "Gmail", "EMAIL", "/crm/channels/gmail.svg",
                        "Email conversations with files, threaded replies, and replies sent from Gmail on known threads.", ready, SHARED_OR_PERSONAL,
                        ready ? "" : "An administrator needs to enable Gmail and configure the Google OAuth client on the server.");
            }

            @Override public CrmChannelConnection.View view(Account account) {
                var bridge = bridges.getIfAvailable();
                return bridge == null ? CrmAccountProvider.super.view(account) : bridge.view(account);
            }

            @Override public void command(Account account, String action) {
                var bridge = bridges.getIfAvailable();
                if (bridge == null) throw new IllegalStateException("Gmail is not enabled");
                bridge.command(account, action);
            }
        };
    }

    /** A Telegram user's own chats through a business-mode bot. */
    @Bean @ConditionalOnMissingBean(name = "telegramBusinessAccountProvider")
    CrmAccountProvider telegramBusinessAccountProvider(CrmChannelsProperties properties,
                                                       ObjectProvider<TelegramBusinessAccounts> services) {
        return new CrmAccountProvider() {
            @Override public Descriptor descriptor() {
                var service = services.getIfAvailable();
                boolean ready = properties.getTelegram().isEnabled() && service != null && service.available();
                return new Descriptor(TelegramBusinessAccounts.PROVIDER, "Telegram business", "TELEGRAM",
                        "/crm/channels/telegram.png", "Reply from your own Telegram account through your organisation's business bot.",
                        ready, SHARED_OR_PERSONAL, ready ? "" : "Enable a Telegram bot with business mode on the server first.");
            }

            @Override public CrmChannelConnection.View view(Account account) {
                var service = services.getIfAvailable();
                return service == null ? CrmAccountProvider.super.view(account) : service.view(account);
            }

            @Override public void command(Account account, String action) {
                var service = services.getIfAvailable();
                if (service == null) throw new IllegalStateException("Telegram is unavailable");
                service.command(account, action);
            }
        };
    }

    /**
     * Customers writing to your organisation's bot; distinct from Telegram business, which uses a
     * person's own account. Unavailable when ordinary bot chats are turned off (business-only bot).
     */
    @Bean @ConditionalOnMissingBean(name = "telegramAccountProvider")
    CrmAccountProvider telegramAccountProvider(CrmChannelsProperties properties) {
        return sharedOnly("telegram", "Telegram bot", "TELEGRAM", "/crm/channels/telegram.png",
                "Customers message your organisation's bot.",
                properties.getTelegram().isEnabled() && properties.getTelegram().isRegularChatsEnabled());
    }

    @Bean @ConditionalOnMissingBean(name = "instagramAccountProvider")
    CrmAccountProvider instagramAccountProvider(CrmChannelsProperties properties) {
        return sharedOnly("instagram", "Instagram", "INSTAGRAM", "/crm/channels/instagram.png",
                "Messages from your professional account.", properties.getInstagram().isEnabled());
    }

    @Bean @ConditionalOnMissingBean(name = "whatsappAccountProvider")
    CrmAccountProvider whatsappAccountProvider(CrmChannelsProperties properties) {
        return sharedOnly("whatsapp", "WhatsApp", "WHATSAPP", "/crm/channels/whatsapp.svg",
                "Customer messages through WhatsApp Business.", properties.getWhatsapp().isEnabled());
    }

    private static CrmAccountProvider sharedOnly(String key, String label, String channel, String icon,
                                                 String description, boolean enabled) {
        String hint = enabled
                ? "This provider currently uses one shared account configured by your administrator."
                : "An administrator needs to enable and configure this provider on the server. This adapter currently supports a shared account only.";
        var descriptor = new CrmAccountProvider.Descriptor(key, label, channel, icon, description, enabled, List.of(), hint);
        return () -> descriptor;
    }
}
