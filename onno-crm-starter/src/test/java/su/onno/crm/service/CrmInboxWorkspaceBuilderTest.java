package su.onno.crm.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrmInboxWorkspaceBuilderTest {

    private static CrmInboxWorkspace.Facet facet(String key) {
        return new CrmInboxWorkspace.Facet(key, "Facet " + key,
                List.of(new CrmInboxWorkspace.FacetOption("A", "A", "#10B981")),
                (conversations, principal) -> Map.of());
    }

    @Test
    void defaultsWriteRolesToReadRolesAndKeepsFacets() {
        var workspace = CrmInboxWorkspace.builder("sales", "Sales")
                .readRoles("SALES")
                .selection(conversation -> true)
                .facet(facet("stage"))
                .build();

        assertThat(workspace.writeRoles()).isEqualTo(Set.of("SALES"));
        assertThat(workspace.facets()).extracting(CrmInboxWorkspace.Facet::key).containsExactly("stage");
        assertThat(workspace.configure().apply(null)).isNull();
    }

    @Test
    void rejectsDuplicateKeysAndConversationFieldsIncludingInheritedOnes() {
        var builder = CrmInboxWorkspace.builder("sales", "Sales")
                .readRoles("SALES")
                .selection(conversation -> true)
                .facet(facet("stage"))
                .facet(facet("stage"));
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> facet("subject")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> facet("code")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> facet("deletionMark")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reservesEveryComputedRowKey() {
        for (String key : List.of("id", "customerDisplay", "customerRef", "customerAvatar", "statusDisplay", "statusColor",
                "priorityDisplay", "priorityColor", "assigneeDisplay", "inboxDisplay", "rowBadges"))
            assertThatThrownBy(() -> facet(key)).as(key).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void canonicalConstructorValidatesFacets() {
        Function<List<CrmInboxWorkspace.Facet>, CrmInboxWorkspace> create = facets -> new CrmInboxWorkspace("sales", "Sales",
                Set.of("SALES"), Set.of("SALES"), c -> true, UnaryOperator.identity(), facets);
        assertThatThrownBy(() -> create.apply(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create.apply(Arrays.asList(facet("stage"), null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create.apply(List.of(facet("stage"), facet("stage")))).isInstanceOf(IllegalArgumentException.class);
        assertThat(create.apply(List.of(facet("stage"))).facets()).hasSize(1);
    }

    @Test
    void dynamicOptionsAreEvaluatedOnEveryRead() {
        var library = new ArrayList<CrmInboxWorkspace.FacetOption>();
        var tags = new CrmInboxWorkspace.Facet("tags", "Tags", () -> List.copyOf(library), (conversations, principal) -> Map.of(), true);
        assertThat(tags.options()).isEmpty();
        library.add(new CrmInboxWorkspace.FacetOption("vip", "VIP", "#F59E0B"));
        assertThat(tags.options()).extracting(CrmInboxWorkspace.FacetOption::value).containsExactly("vip");
        assertThatThrownBy(() -> new CrmInboxWorkspace.Facet("tags", "Tags", (CrmInboxWorkspace.FacetOptions) null,
                (conversations, principal) -> Map.of())).isInstanceOf(NullPointerException.class);
    }
}
