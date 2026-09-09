package su.onno.crm.domain;

import lombok.Getter;
import lombok.Setter;
import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;

@Catalog(name = "CrmInboxes", title = "Inbox", codePrefix = "IN-", context = "CRM")
@AccessControl(readRoles = {"ADMIN"}, writeRoles = {"ADMIN"})
@Getter
@Setter
public class Inbox extends CatalogObject {

    @Attribute(displayName = "Channel", required = true)
    private String channel;

    @Attribute(displayName = "Address / handle", required = true, length = 240)
    private String address;

    @Attribute(displayName = "Active")
    private boolean active = true;

    @Attribute(displayName = "Accent color", length = 20)
    private String accentColor = "#6366F1";
}
