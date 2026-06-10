package com.onec.ui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ListFilterTest {

    private static final Set<String> COLUMNS = Set.of("season", "check_in", "amount");

    @Test
    void noParamsIsEmpty() {
        assertThat(ListFilter.parse(null, null, null, COLUMNS).isEmpty()).isTrue();
        assertThat(ListFilter.parse(List.of(), List.of(), List.of(), COLUMNS).isEmpty()).isTrue();
    }

    @Test
    void equalityComparesAsTextAndBindsValue() {
        ListFilter.Result r = ListFilter.parse(List.of("season,2025"), null, null, COLUMNS);
        assertThat(r.sql()).isEqualTo("CAST(season AS VARCHAR) = :lf0");
        assertThat(r.bindings()).containsEntry("lf0", "2025");
    }

    @Test
    void dateRangeBuildsNativeBounds() {
        ListFilter.Result r = ListFilter.parse(null,
                List.of("check_in,2024-01-01"), List.of("check_in,2024-12-31"), COLUMNS);
        assertThat(r.sql()).isEqualTo("check_in >= :lf0 AND check_in <= :lf1");
        assertThat(r.bindings()).containsEntry("lf0", "2024-01-01").containsEntry("lf1", "2024-12-31");
    }

    @Test
    void valueMayContainCommas() {
        // Only the first comma separates column from value; the rest is part of the value.
        ListFilter.Result r = ListFilter.parse(List.of("season,Q1, 2025"), null, null, COLUMNS);
        assertThat(r.bindings()).containsEntry("lf0", "Q1, 2025");
    }

    @Test
    void clearedControlIsSkipped() {
        assertThat(ListFilter.parse(List.of("season,"), null, null, COLUMNS).isEmpty()).isTrue();
        assertThat(ListFilter.parse(List.of("season,   "), null, null, COLUMNS).isEmpty()).isTrue();
    }

    @Test
    void unknownColumnIsIgnored() {
        assertThat(ListFilter.parse(List.of("secret_col,1"), null, null, COLUMNS).isEmpty()).isTrue();
    }

    @Test
    void systemColumnsAreAllowed() {
        ListFilter.Result r = ListFilter.parse(null, List.of("_date,2024-01-01"), null, COLUMNS);
        assertThat(r.sql()).isEqualTo("_date >= :lf0");
    }

    @Test
    void injectionInColumnPositionIsRejected() {
        assertThat(ListFilter.parse(List.of("season; DROP TABLE x,1"), null, null, COLUMNS).isEmpty()).isTrue();
    }

    @Test
    void injectionInValuePositionStaysBound() {
        ListFilter.Result r = ListFilter.parse(List.of("season,0; DROP TABLE x"), null, null, COLUMNS);
        assertThat(r.sql()).isEqualTo("CAST(season AS VARCHAR) = :lf0");
        assertThat(r.bindings()).containsEntry("lf0", "0; DROP TABLE x");
    }

    @Test
    void pairWithNoCommaIsSkipped() {
        assertThat(ListFilter.parse(List.of("season"), null, null, COLUMNS).isEmpty()).isTrue();
    }

    @Test
    void mixedFiltersChainWithAnd() {
        ListFilter.Result r = ListFilter.parse(
                List.of("season,2025"), List.of("amount,10"), List.of("amount,99"), COLUMNS);
        assertThat(r.sql())
                .isEqualTo("CAST(season AS VARCHAR) = :lf0 AND amount >= :lf1 AND amount <= :lf2");
        assertThat(r.bindings()).containsEntry("lf0", "2025").containsEntry("lf1", "10").containsEntry("lf2", "99");
    }

    @Test
    void multiSelectFoldsValuesForOneColumnIntoAnInList() {
        ListFilter.Result r = ListFilter.parse(
                null, List.of("season,2024", "season,2025"), null, null, null, null, COLUMNS);
        assertThat(r.sql()).isEqualTo("CAST(season AS VARCHAR) IN (:lf0, :lf1)");
        assertThat(r.bindings()).containsEntry("lf0", "2024").containsEntry("lf1", "2025");
    }

    @Test
    void multiSelectAcrossColumnsStaysAndedButGroupedPerColumn() {
        ListFilter.Result r = ListFilter.parse(
                null, List.of("season,2024", "amount,10", "season,2025"), null, null, null, null, COLUMNS);
        assertThat(r.sql())
                .isEqualTo("CAST(season AS VARCHAR) IN (:lf0, :lf1) AND CAST(amount AS VARCHAR) IN (:lf2)");
        assertThat(r.bindings())
                .containsEntry("lf0", "2024").containsEntry("lf1", "2025").containsEntry("lf2", "10");
    }

    @Test
    void containsMatchesLowercasedAndWrapsBothSides() {
        ListFilter.Result r = ListFilter.parse(
                null, null, List.of("season,Q1"), null, null, null, COLUMNS);
        assertThat(r.sql()).isEqualTo("LOWER(CAST(season AS VARCHAR)) LIKE :lf0");
        assertThat(r.bindings()).containsEntry("lf0", "%q1%");
    }

    @Test
    void startsWithAnchorsAtTheStart() {
        ListFilter.Result r = ListFilter.parse(
                null, null, null, List.of("season,Q1"), null, null, COLUMNS);
        assertThat(r.sql()).isEqualTo("LOWER(CAST(season AS VARCHAR)) LIKE :lf0");
        assertThat(r.bindings()).containsEntry("lf0", "q1%");
    }

    @Test
    void containsStaysBoundUnderInjection() {
        ListFilter.Result r = ListFilter.parse(
                null, null, List.of("season,%' OR '1'='1"), null, null, null, COLUMNS);
        assertThat(r.sql()).isEqualTo("LOWER(CAST(season AS VARCHAR)) LIKE :lf0");
        assertThat(r.bindings()).containsEntry("lf0", "%%' or '1'='1%");
    }

    @Test
    void allChannelsAndTogether() {
        ListFilter.Result r = ListFilter.parse(
                List.of("season,2025"), List.of("amount,10", "amount,20"),
                List.of("check_in,jan"), List.of("season,2"),
                List.of("amount,1"), List.of("amount,99"), COLUMNS);
        assertThat(r.sql()).isEqualTo(
                "CAST(season AS VARCHAR) = :lf0"
                        + " AND CAST(amount AS VARCHAR) IN (:lf1, :lf2)"
                        + " AND LOWER(CAST(check_in AS VARCHAR)) LIKE :lf3"
                        + " AND LOWER(CAST(season AS VARCHAR)) LIKE :lf4"
                        + " AND amount >= :lf5 AND amount <= :lf6");
    }

    @Test
    void multiAndContainsIgnoreUnknownColumns() {
        assertThat(ListFilter.parse(null, List.of("secret_col,1"), List.of("secret_col,x"),
                null, null, null, COLUMNS).isEmpty()).isTrue();
    }

    @Test
    void emptyMultiAndContainsAddNoConstraint() {
        assertThat(ListFilter.parse(null, List.of("season,"), List.of("season,  "),
                List.of("season,"), null, null, COLUMNS).isEmpty()).isTrue();
    }
}
