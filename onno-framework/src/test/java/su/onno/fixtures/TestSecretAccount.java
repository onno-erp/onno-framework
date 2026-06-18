package su.onno.fixtures;

import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;

import lombok.Getter;
import lombok.Setter;

@Catalog(name = "TestSecretAccounts", codeLength = 9)
@Getter
@Setter
public class TestSecretAccount extends CatalogObject {

    @Attribute(length = 100)
    private String username;

    @Attribute(displayName = "WebService password", length = 100, secret = true)
    private String wsPassword;
}
