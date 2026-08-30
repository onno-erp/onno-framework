package su.onno.crm.ui;

import org.springframework.stereotype.Component;
import su.onno.crm.domain.Opportunity;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.ListSpec;
import su.onno.ui.ListSpec.Agg;

@Component
public class OpportunityView implements EntityView<Opportunity> {

    @Override public Class<Opportunity> entity() { return Opportunity.class; }

    @Override
    public void list(ListSpec<Opportunity> list) {
        list.columns(Opportunity::getDescription, Opportunity::getCustomer, Opportunity::getStage,
                        Opportunity::getAmount, Opportunity::getProbability,
                        Opportunity::getExpectedClose, Opportunity::getOwner)
                .label(Opportunity::getDescription, "Opportunity")
                .sortBy(Opportunity::getExpectedClose)
                .groupable(Opportunity::getStage)
                .defaultGroupBy(Opportunity::getStage)
                .aggregate(Opportunity::getAmount, Agg.SUM, "Stage value");
        list.filter(Opportunity::getStage).multiOptions();
        list.filter(Opportunity::getExpectedClose).dateRange();
    }

    @Override
    public void fields(EntityConfigBuilder<Opportunity> fields) {
        fields.field(Opportunity::getDescription).order(0).label("Opportunity").group("Deal")
                .field(Opportunity::getCustomer).order(1).group("Deal").width("1/2")
                .field(Opportunity::getOwner).order(2).group("Deal").width("1/2")
                .field(Opportunity::getStage).order(3).group("Pipeline").width("1/2")
                .field(Opportunity::getProbability).order(4).group("Pipeline").width("1/2").format("integer")
                .field(Opportunity::getAmount).order(5).group("Pipeline").width("1/2")
                .field(Opportunity::getExpectedClose).order(6).group("Pipeline").width("1/2").format("dd MMM yyyy")
                .field(Opportunity::getNextStep).order(7).group("Next action").widget("textarea");
    }

    @Override public boolean comments() { return true; }
}
