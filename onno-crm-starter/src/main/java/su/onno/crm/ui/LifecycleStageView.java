package su.onno.crm.ui;
import su.onno.crm.domain.LifecycleStage;
import su.onno.ui.*;
public class LifecycleStageView implements EntityView<LifecycleStage> {
    @Override public Class<LifecycleStage> entity() { return LifecycleStage.class; }
    @Override public void list(ListSpec<LifecycleStage> list) {
        list.title("Contact stages");
        list.columns(LifecycleStage::getDescription, LifecycleStage::getColor).label(LifecycleStage::getDescription, "Name").sortBy(LifecycleStage::getDescription);
    }
    @Override public void fields(EntityConfigBuilder<LifecycleStage> fields) {
        fields.field(LifecycleStage::getDescription).label("Name").order(0)
            .field(LifecycleStage::getColor).widget("color").order(1);
    }
}
