package su.onno.fixtures;

import su.onno.annotations.Dimension;
import su.onno.annotations.InformationRegister;
import su.onno.annotations.Resource;
import su.onno.model.InformationRecord;
import su.onno.model.Periodicity;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@InformationRegister(name = "Prices", periodicity = Periodicity.DAY)
public class TestPriceRegister extends InformationRecord {

    @Dimension
    private UUID product;

    @Dimension
    private UUID warehouse;

    @Resource(precision = 15, scale = 2)
    private BigDecimal price;
}
