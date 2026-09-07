package su.onno.crm.domain;

import lombok.Getter;
import lombok.Setter;
import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;
import su.onno.types.Ref;

@Catalog(name = "CrmCustomers", title = "Customer", codePrefix = "CU-", context = "CRM")
@AccessControl(readRoles = {"CRM_AGENT", "CRM_MANAGER"}, writeRoles = {"CRM_AGENT", "CRM_MANAGER"})
@Getter
@Setter
public class Customer extends CatalogObject {

    @Attribute(displayName = "Lifecycle stage")
    private Ref<LifecycleStage> stage;

    @Attribute(displayName = "Email", email = true, length = 200)
    private String email;

    @Attribute(displayName = "Phone", length = 60)
    private String phone;

    @Attribute(displayName = "Company", length = 200)
    private String company;

    @Attribute(displayName = "Photo", length = 1024)
    private String avatarUrl;

    @Attribute(displayName = "Owner")
    private Ref<Agent> owner;

    @Attribute(displayName = "Lead source", length = 120)
    private String source;

    @Attribute(displayName = "Tags", length = 500)
    private String tags;

    @Attribute(displayName = "City", length = 120)
    private String city;
}
