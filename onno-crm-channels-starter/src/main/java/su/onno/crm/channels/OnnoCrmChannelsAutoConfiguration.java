package su.onno.crm.channels;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
/** Installs opt-in CRM channel adapters without requiring a host component scan. */
@AutoConfiguration(before=su.onno.crm.OnnoCrmAutoConfiguration.class)
@org.springframework.boot.autoconfigure.condition.ConditionalOnBean(su.onno.crm.service.CrmCustomerBinding.class)
@EnableConfigurationProperties(CrmChannelsProperties.class)
@Import({su.onno.crm.channels.whatsapp.WhatsAppClient.class,su.onno.crm.channels.whatsapp.WhatsAppBridge.class,su.onno.crm.channels.whatsapp.WhatsAppWebhookController.class,su.onno.crm.channels.telegram.TelegramConfiguration.class,su.onno.crm.channels.telegram.TelegramChannelConnection.class,su.onno.crm.channels.telegram.TelegramAvatarController.class,su.onno.crm.channels.gmail.GmailClient.class,su.onno.crm.channels.gmail.GmailBridge.class,su.onno.crm.channels.gmail.GmailOAuthController.class,su.onno.crm.channels.instagram.InstagramClient.class,su.onno.crm.channels.instagram.InstagramBridge.class})
public class OnnoCrmChannelsAutoConfiguration {}
