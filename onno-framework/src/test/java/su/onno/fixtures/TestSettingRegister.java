package su.onno.fixtures;

import su.onno.annotations.Attribute;
import su.onno.annotations.Dimension;
import su.onno.annotations.InformationRegister;
import su.onno.model.InformationRecord;
import su.onno.model.Periodicity;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@InformationRegister(name = "Settings", periodicity = Periodicity.NONE)
public class TestSettingRegister extends InformationRecord {

    @Dimension
    private UUID userId;

    @Attribute(length = 255)
    private String settingValue;
}
