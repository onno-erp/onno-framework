package com.onec.fixtures;

import com.onec.annotations.Attribute;
import com.onec.annotations.Catalog;
import com.onec.model.CatalogObject;

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
