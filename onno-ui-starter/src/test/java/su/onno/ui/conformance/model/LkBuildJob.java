package su.onno.ui.conformance.model;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Document;
import su.onno.annotations.TabularSection;
import su.onno.model.DocumentObject;
import su.onno.lifecycle.Postable;
import su.onno.posting.PostingContext;
import su.onno.types.Ref;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** A document scoped through its tenant, with a tabular section and register movements. */
@Document(name = "LkBuildJobs")
@AccessControl(readRoles = {"CUSTOMER", "STAFF"})
public class LkBuildJob extends DocumentObject implements Postable {

    @Attribute
    private Ref<LkTenant> tenant;

    @Attribute
    private String note;

    @TabularSection(name = "lines")
    private List<LkBuildLine> lines = new ArrayList<>();

    @Override
    public void handlePosting(PostingContext context) {
        BigDecimal total = BigDecimal.ZERO;
        for (LkBuildLine line : lines) {
            if (line.getMinutes() != null) total = total.add(line.getMinutes());
        }
        BigDecimal minutes = total;
        context.movements(LkUsage.class).addReceipt(r -> {
            r.setTenant(tenant);
            r.setMinutes(minutes);
        });
    }

    public Ref<LkTenant> getTenant() {
        return tenant;
    }

    public void setTenant(Ref<LkTenant> tenant) {
        this.tenant = tenant;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public List<LkBuildLine> getLines() {
        return lines;
    }

    public void setLines(List<LkBuildLine> lines) {
        this.lines = lines;
    }
}
