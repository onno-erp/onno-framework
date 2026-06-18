package su.onno.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * The related-list DSL on {@link EntityConfigBuilder}: panels are collected in declaration order,
 * defaults fill in for the optional bits, and the fluent chaining (back to {@code field}/another
 * {@code relatedList}) doesn't drop earlier config.
 */
class RelatedListBuilderTest {

    static class Join {}

    @Test
    void relatedList_capturesViaDisplayColumnsAndLabel() {
        EntityConfigBuilder cfg = new EntityConfigBuilder();
        cfg.relatedList("doctors", Join.class)
                .via("clinic")
                .display("doctor")
                .columns("doctor", "role")
                .label("Doctors");

        List<RelatedList> lists = cfg.buildRelatedLists();
        assertThat(lists).hasSize(1);
        RelatedList rl = lists.get(0);
        assertThat(rl.name()).isEqualTo("doctors");
        assertThat(rl.joinCatalog()).isEqualTo(Join.class);
        assertThat(rl.via()).isEqualTo("clinic");
        assertThat(rl.display()).isEqualTo("doctor");
        assertThat(rl.columns()).containsExactly("doctor", "role");
        assertThat(rl.label()).isEqualTo("Doctors");
    }

    @Test
    void columns_defaultEmpty_andLabelBlank_whenUnset() {
        EntityConfigBuilder cfg = new EntityConfigBuilder();
        cfg.relatedList("clinics", Join.class).via("doctor").display("clinic");

        RelatedList rl = cfg.buildRelatedLists().get(0);
        assertThat(rl.columns()).isEmpty();
        assertThat(rl.label()).isEmpty();
        // Visible in the detail view by default (renders read-only there as well as in the form).
        assertThat(rl.hideInDetail()).isFalse();
    }

    @Test
    void hideInDetail_optsPanelOutOfTheDetailRender() {
        EntityConfigBuilder cfg = new EntityConfigBuilder();
        cfg.relatedList("doctors", Join.class).via("clinic").display("doctor").hideInDetail();

        assertThat(cfg.buildRelatedLists().get(0).hideInDetail()).isTrue();
    }

    @Test
    void columns_dedupesRepeatedFields() {
        EntityConfigBuilder cfg = new EntityConfigBuilder();
        cfg.relatedList("doctors", Join.class).columns("doctor").columns("doctor", "role");

        assertThat(cfg.buildRelatedLists().get(0).columns()).containsExactly("doctor", "role");
    }

    @Test
    void multiplePanels_keptInDeclarationOrder_andChainBackToFields() {
        EntityConfigBuilder cfg = new EntityConfigBuilder();
        cfg.relatedList("doctors", Join.class).via("clinic").display("doctor")
                .relatedList("nurses", Join.class).via("clinic").display("nurse")
                .field("address").order(0);

        List<RelatedList> lists = cfg.buildRelatedLists();
        assertThat(lists).extracting(RelatedList::name).containsExactly("doctors", "nurses");
        // The chained field hint survived the hop through the related-list builders.
        assertThat(cfg.buildFieldHints()).containsKey("address");
    }

    @Test
    void sameName_reconfiguresOnePanel_ratherThanAddingDuplicate() {
        EntityConfigBuilder cfg = new EntityConfigBuilder();
        cfg.relatedList("doctors", Join.class).via("clinic");
        cfg.relatedList("doctors", Join.class).display("doctor");

        List<RelatedList> lists = cfg.buildRelatedLists();
        assertThat(lists).hasSize(1);
        assertThat(lists.get(0).via()).isEqualTo("clinic");
        assertThat(lists.get(0).display()).isEqualTo("doctor");
    }
}
