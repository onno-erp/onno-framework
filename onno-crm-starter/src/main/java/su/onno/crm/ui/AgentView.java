package su.onno.crm.ui;

import org.springframework.stereotype.Component;
import su.onno.crm.domain.Agent;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.ListSpec;

@Component
public class AgentView implements EntityView<Agent> {

    @Override public Class<Agent> entity() { return Agent.class; }

    @Override
    public void list(ListSpec<Agent> list) {
        list.columns(Agent::getAvatarUrl, Agent::getDescription, Agent::getEmail, Agent::getJobTitle)
                .label(Agent::getAvatarUrl, "")
                .label(Agent::getDescription, "Name")
                .sortBy(Agent::getDescription);
    }

    @Override
    public void fields(EntityConfigBuilder<Agent> fields) {
        fields.field(Agent::getDescription).order(0).label("Name")
                .field(Agent::getEmail).order(1)
                .field(Agent::getJobTitle).order(2)
                .field(Agent::getAvatarUrl).order(3).widget("avatar").label("Photo");
    }
}
