package su.onno.crm.domain;

import lombok.Getter;
import lombok.Setter;
import su.onno.annotations.*;
import su.onno.model.CatalogObject;
import su.onno.types.Ref;

/** Stable channel identity; messages keep their own channel-specific conversation. */
@Catalog(name = "CrmContactIdentities", title = "Contact identity", codePrefix = "ID-", context = "CRM")
@AccessControl(readRoles = {"CRM_AGENT", "CRM_MANAGER"}, writeRoles = {})
@Getter @Setter
public class ContactIdentity extends CatalogObject {
    @Attribute(required = true) private Ref<Customer> customer;
    @Attribute(required = true) private Channel channel;
    @Attribute(required = true, length = 200) private String connectionKey;
    @Attribute(required = true, length = 240) private String externalId;
    @Attribute(length = 240) private String address;
    @Attribute private boolean verified;
}
