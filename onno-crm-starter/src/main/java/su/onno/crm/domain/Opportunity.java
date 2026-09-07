package su.onno.crm.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;
import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;
import su.onno.types.Ref;

@Catalog(name = "CrmOpportunities", title = "Opportunity", codePrefix = "OP-", context = "CRM")
@AccessControl(readRoles = {"CRM_AGENT", "CRM_MANAGER"}, writeRoles = {"CRM_AGENT", "CRM_MANAGER"})
@Getter
@Setter
public class Opportunity extends CatalogObject {

    @Attribute(displayName = "Customer", required = true)
    private Ref<Customer> customer;

    @Attribute(displayName = "Owner")
    private Ref<Agent> owner;

    @Attribute(displayName = "Stage", required = true)
    private OpportunityStage stage = OpportunityStage.DISCOVERY;

    @Attribute(displayName = "Value", precision = 15, scale = 2, min = 0)
    private BigDecimal amount = BigDecimal.ZERO;

    @Attribute(displayName = "Probability, %", min = 0, max = 100)
    private int probability = 10;

    @Attribute(displayName = "Expected close")
    private LocalDate expectedClose;

    @Attribute(displayName = "Next step", length = 500)
    private String nextStep;
}
