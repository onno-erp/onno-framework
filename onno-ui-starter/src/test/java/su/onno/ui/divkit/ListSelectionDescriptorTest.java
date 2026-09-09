package su.onno.ui.divkit;
import org.junit.jupiter.api.Test;
import su.onno.ui.ListSpec;
import su.onno.ui.ResolvedListView;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
class ListSelectionDescriptorTest {
    @Test void selectionIsOptInAndTravelsThroughDescriptor() {
        var spec = new ListSpec<>();
        assertThat(spec.selectionCheckboxes()).isFalse();
        spec.selectionCheckboxes(true);
        var view = new ResolvedListView("Customers", List.of(), false, null, false,
                List.of(), null, 50, null, null, spec.selectionCheckboxes());
        assertThat(SurfaceDivBuilder.listDescriptor(view,"catalogs","customers",null,true,List.of(),List.of()))
                .containsEntry("selectionCheckboxes",true);
        var legacy = new ResolvedListView("Customers",List.of(),false,null,false,List.of(),null,50,null,null);
        assertThat(legacy.selectionCheckboxes()).isFalse();
    }
}
