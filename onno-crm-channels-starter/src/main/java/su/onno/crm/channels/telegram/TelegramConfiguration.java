package su.onno.crm.channels.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import su.onno.crm.repository.*;

/** Opt-in, local single-bot integration. Credentials stay in external Spring configuration. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "onno.crm.channels.telegram.enabled", havingValue = "true")
public class TelegramConfiguration {
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    TelegramClient telegramClient(@Value("${onno.crm.channels.telegram.token}") String token, ObjectMapper json) {
        return new TelegramClient(token, json);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    TelegramInboxBridge telegramInboxBridge(TelegramClient client, JdbcTemplate jdbc,
            PlatformTransactionManager transactions, CustomerRepository customers, InboxRepository inboxes,
            ConversationRepository conversations, ConversationMessageRepository messages, su.onno.crm.service.CrmContactService contacts, su.onno.crm.service.CrmWorkspaceService workspace, su.onno.crm.service.CrmConversationStatuses statuses) {
        return new TelegramInboxBridge(client, jdbc, transactions, customers, inboxes, conversations, messages, contacts, workspace, statuses);
    }
}
