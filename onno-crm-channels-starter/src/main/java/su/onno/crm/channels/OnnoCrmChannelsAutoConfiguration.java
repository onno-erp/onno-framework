package su.onno.crm.channels;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
/** Installs opt-in CRM channel adapters without requiring a host component scan. */
@AutoConfiguration(before=su.onno.crm.OnnoCrmAutoConfiguration.class)
@org.springframework.boot.autoconfigure.condition.ConditionalOnBean(su.onno.crm.service.CrmCustomerBinding.class)
@EnableConfigurationProperties(CrmChannelsProperties.class)
@Import({ConnectionProviders.class,su.onno.crm.channels.whatsapp.WhatsAppConfiguration.class,su.onno.crm.channels.whatsapp.WhatsAppBridge.class,su.onno.crm.channels.whatsapp.WhatsAppWebhookController.class,su.onno.crm.channels.telegram.TelegramConfiguration.class,su.onno.crm.channels.telegram.TelegramBusinessController.class,su.onno.crm.channels.telegram.TelegramChannelConnection.class,su.onno.crm.channels.telegram.TelegramAvatarController.class,su.onno.crm.channels.gmail.GmailConfiguration.class,su.onno.crm.channels.gmail.GmailBridge.class,su.onno.crm.channels.gmail.GmailMediaFiles.class,su.onno.crm.channels.gmail.GmailOAuthController.class,su.onno.crm.channels.gmail.GmailMailboxController.class,su.onno.crm.channels.instagram.InstagramConfiguration.class,su.onno.crm.channels.instagram.InstagramBridge.class,su.onno.crm.channels.instagram.InstagramAvatars.class,su.onno.crm.channels.instagram.InstagramAvatarController.class})
public class OnnoCrmChannelsAutoConfiguration {}
