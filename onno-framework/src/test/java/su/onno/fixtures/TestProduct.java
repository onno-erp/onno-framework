package su.onno.fixtures;

import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Catalog(name = "TestProducts", codeLength = 9)
@Getter
@Setter
public class TestProduct extends CatalogObject {

    @Attribute(length = 100)
    private String fullName;

    @Attribute(precision = 15, scale = 2)
    private BigDecimal unitPrice;

    @Attribute(length = 25)
    private String unit;
}
