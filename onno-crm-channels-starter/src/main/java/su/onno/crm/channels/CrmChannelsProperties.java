package su.onno.crm.channels;
import org.springframework.boot.context.properties.ConfigurationProperties;
/** Provider setup; all adapters default disabled. */
@ConfigurationProperties("onno.crm.channels")
public class CrmChannelsProperties {
 private final Telegram telegram = new Telegram();
 public Telegram getTelegram(){return telegram;}
 public static class Telegram {
/** Enable the single-bot Telegram adapter. */
 private boolean enabled = false;
 public boolean isEnabled(){return enabled;}
 public void setEnabled(boolean value){this.enabled=value;}
/** Bot token; supply through external secret configuration. */
 private String token = null;
 public String getToken(){return token;}
 public void setToken(String value){this.token=value;}
}
 private final Gmail gmail = new Gmail();
 public Gmail getGmail(){return gmail;}
 public static class Gmail {
/** Enable the single-mailbox Gmail OAuth adapter. */
 private boolean enabled = false;
 public boolean isEnabled(){return enabled;}
 public void setEnabled(boolean value){this.enabled=value;}
/** Google OAuth client JSON file outside the application artifact. */
 private String clientFile = System.getProperty("user.home")+"/.config/onno/gmail-client.json";
 public String getClientFile(){return clientFile;}
 public void setClientFile(String value){this.clientFile=value;}
/** Private writable Google token JSON file. */
 private String tokensFile = System.getProperty("user.home")+"/.config/onno/gmail-tokens.json";
 public String getTokensFile(){return tokensFile;}
 public void setTokensFile(String value){this.tokensFile=value;}
/** Exact registered Google OAuth callback URI; use HTTPS in deployments. */
 private String redirectUri = "http://127.0.0.1:8090/api/crm/gmail/callback";
 public String getRedirectUri(){return redirectUri;}
 public void setRedirectUri(String value){this.redirectUri=value;}
}
 private final Instagram instagram = new Instagram();
 public Instagram getInstagram(){return instagram;}
 public static class Instagram {
/** Enable the single-account Instagram polling adapter. */
 private boolean enabled = false;
 public boolean isEnabled(){return enabled;}
 public void setEnabled(boolean value){this.enabled=value;}
/** Private Instagram token JSON file. */
 private String tokensFile = System.getProperty("user.home")+"/.config/onno/instagram-tokens.json";
 public String getTokensFile(){return tokensFile;}
 public void setTokensFile(String value){this.tokensFile=value;}
}
 private final Whatsapp whatsapp = new Whatsapp();
 public Whatsapp getWhatsapp(){return whatsapp;}
 public static class Whatsapp {
/** Enable the single-number WhatsApp Cloud API adapter. */
 private boolean enabled = false;
 public boolean isEnabled(){return enabled;}
 public void setEnabled(boolean value){this.enabled=value;}
/** Private WhatsApp token and webhook signing JSON file. */
 private String tokensFile = System.getProperty("user.home")+"/.config/onno/whatsapp-tokens.json";
 public String getTokensFile(){return tokensFile;}
 public void setTokensFile(String value){this.tokensFile=value;}
}
}
