package su.onno.crm.domain;

import lombok.Getter;
import lombok.Setter;
import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;

@Catalog(name = "CrmAgents", title = "Team member", codePrefix = "AG-", context = "CRM")
@AccessControl(readRoles = {"CRM_AGENT", "CRM_MANAGER"}, writeRoles = {"CRM_MANAGER"})
@Getter
@Setter
public class Agent extends CatalogObject {

    @Attribute(displayName = "Email", required = true, email = true, length = 200)
    private String email;

    @Attribute(displayName = "Job title", length = 120)
    private String jobTitle;

    @Attribute(displayName = "Photo", length = 500)
    private String avatarUrl;
}
