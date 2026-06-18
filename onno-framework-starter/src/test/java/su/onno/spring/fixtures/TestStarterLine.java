package su.onno.spring.fixtures;

import su.onno.annotations.Attribute;
import su.onno.model.TabularSectionRow;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class TestStarterLine extends TabularSectionRow {

    @Attribute(length = 100)
    private String productName;

    @Attribute(precision = 15, scale = 2)
    private BigDecimal quantity;
}
