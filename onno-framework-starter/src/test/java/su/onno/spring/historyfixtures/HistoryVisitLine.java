package su.onno.spring.historyfixtures;

import su.onno.annotations.Attribute;
import su.onno.model.TabularSectionRow;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class HistoryVisitLine extends TabularSectionRow {

    @Attribute(length = 100)
    private String title;
}
