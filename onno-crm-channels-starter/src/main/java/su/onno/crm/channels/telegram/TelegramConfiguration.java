package su.onno.crm.channels.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import su.onno.crm.channels.CrmChannelsProperties;
import su.onno.crm.repository.ConversationMessageRepository;
import su.onno.crm.repository.ConversationRepository;
import su.onno.crm.repository.InboxRepository;
import su.onno.crm.service.CrmAttachments;
import su.onno.crm.service.CrmConnectionAccounts;
import su.onno.crm.service.CrmContactService;
import su.onno.crm.service.CrmConversationStatuses;
import su.onno.crm.service.CrmWorkspaceService;

/**
 * Opt-in Telegram integration around one server-configured bot: ordinary bot chats land in a shared
 * inbox (unless {@code onno.crm.channels.telegram.regular-chats-enabled=false}), and when the bot has
 * business mode, each shared or personal Telegram business account routes a Telegram user's own
 * chats into its own inbox. Credentials stay in external Spring configuration.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "onno.crm.channels.telegram.enabled", havingValue = "true")
public class TelegramConfiguration {
    @Bean
    @ConditionalOnMissingBean
    TelegramClient telegramClient(CrmChannelsProperties properties, ObjectMapper json) {
        return new TelegramClient(properties.getTelegram().getToken(), json);
    }

    @Bean
    TelegramBusinessAccounts telegramBusinessAccounts(JdbcTemplate jdbc, PlatformTransactionManager transactions,
                                                      CrmConnectionAccounts accounts, CrmWorkspaceService workspace,
                                                      CrmContactService contacts, CrmConversationStatuses statuses,
                                                      InboxRepository inboxes, ConversationRepository conversations,
                                                      ConversationMessageRepository messages, CrmAttachments attachments) {
        return new TelegramBusinessAccounts(jdbc, transactions, accounts, workspace, contacts, statuses,
                inboxes, conversations, messages, attachments);
    }

    @Bean
    @ConditionalOnMissingBean
    TelegramInboxBridge telegramInboxBridge(TelegramClient client, JdbcTemplate jdbc, PlatformTransactionManager transactions,
                                            InboxRepository inboxes, ConversationRepository conversations,
                                            ConversationMessageRepository messages, CrmContactService contacts,
                                            CrmWorkspaceService workspace, CrmConversationStatuses statuses,
                                            CrmAttachments attachments, TelegramBusinessAccounts business,
                                            CrmChannelsProperties properties) {
        return new TelegramInboxBridge(client, jdbc, transactions, inboxes, conversations, messages, contacts,
                workspace, statuses, attachments, business, properties.getTelegram().isRegularChatsEnabled());
    }
}
