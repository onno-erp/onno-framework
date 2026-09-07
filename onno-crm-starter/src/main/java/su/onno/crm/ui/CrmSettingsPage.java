package su.onno.crm.ui;
import org.springframework.stereotype.Component;
import su.onno.ui.Page;
import su.onno.ui.PageBuilder;
@Component
public class CrmSettingsPage implements Page {
    public String route(){return "/crm-settings";}
    public void compose(PageBuilder page){page.title("Channel connections");page.subtitle("Connect your accounts and manage the channels in your inbox.");page.widget("Connections").type("crmSettings").width("full");}
}
