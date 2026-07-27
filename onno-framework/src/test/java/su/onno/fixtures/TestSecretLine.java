package su.onno.fixtures;

import su.onno.annotations.Attribute;
import su.onno.model.TabularSectionRow;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TestSecretLine extends TabularSectionRow {

    @Attribute(secret = true)
    private String note;
}
