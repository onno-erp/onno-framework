package su.onno.ui.conformance.model;

import su.onno.annotations.Attribute;
import su.onno.model.TabularSectionRow;
import su.onno.types.Ref;

import java.math.BigDecimal;

public class LkBuildLine extends TabularSectionRow {

    @Attribute
    private Ref<LkInstance> instance;

    @Attribute(precision = 12, scale = 2)
    private BigDecimal minutes;

    public Ref<LkInstance> getInstance() {
        return instance;
    }

    public void setInstance(Ref<LkInstance> instance) {
        this.instance = instance;
    }

    public BigDecimal getMinutes() {
        return minutes;
    }

    public void setMinutes(BigDecimal minutes) {
        this.minutes = minutes;
    }
}
