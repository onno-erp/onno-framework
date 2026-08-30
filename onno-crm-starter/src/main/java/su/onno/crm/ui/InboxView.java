package su.onno.crm.ui;

import org.springframework.stereotype.Component;
import su.onno.crm.domain.Inbox;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.ListSpec;

@Component
public class InboxView implements EntityView<Inbox> {

    @Override public Class<Inbox> entity() { return Inbox.class; }

    @Override
    public void list(ListSpec<Inbox> list) {
        list.columns(Inbox::getDescription, Inbox::getChannel, Inbox::getAddress, Inbox::isActive)
                .label(Inbox::getDescription, "Name")
                .sortBy(Inbox::getDescription);
        list.filter(Inbox::getChannel).multiOptions();
    }

    @Override
    public void fields(EntityConfigBuilder<Inbox> fields) {
        fields.field(Inbox::getDescription).order(0).label("Name")
                .field(Inbox::getChannel).order(1).width("1/2")
                .field(Inbox::getAddress).order(2).width("1/2")
                .field(Inbox::isActive).order(3).widget("switch").width("1/2")
                .field(Inbox::getAccentColor).order(4).widget("color").width("1/2");
    }
}
