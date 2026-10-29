package su.onno.spring.fixtures;

import lombok.Getter;
import lombok.Setter;
import su.onno.annotations.Catalog;
import su.onno.annotations.TabularSection;
import su.onno.model.CatalogObject;
import java.util.ArrayList;
import java.util.List;

@Catalog(name = "StarterProfiles", autoNumber = false)
@Getter
@Setter
public class TestStarterProfile extends CatalogObject {
    @TabularSection(name = "filters")
    private List<Filter> filters = new ArrayList<>();

    @Getter
    @Setter
    public static class Filter extends su.onno.model.TabularSectionRow {
        @su.onno.annotations.Attribute private String property;
    }
}
