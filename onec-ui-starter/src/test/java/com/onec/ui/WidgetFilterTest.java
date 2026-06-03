package com.onec.ui;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WidgetFilterTest {

    private static final Set<String> COLUMNS = Set.of("status", "amount", "client");

    @Test
    void blankFilterIsEmpty() {
        assertThat(WidgetFilter.parse(null, COLUMNS).isEmpty()).isTrue();
        assertThat(WidgetFilter.parse("   ", COLUMNS).isEmpty()).isTrue();
    }

    @Test
    void bindsValueAndNeverInlinesIt() {
        WidgetFilter.Result r = WidgetFilter.parse("status != cancelled", COLUMNS);
        assertThat(r.sql()).isEqualTo("status <> :wf0");
        assertThat(r.bindings()).containsEntry("wf0", "cancelled");
    }

    @Test
    void typesLiterals() {
        assertThat(WidgetFilter.parse("amount >= 100", COLUMNS).bindings()).containsEntry("wf0", 100L);
        assertThat(WidgetFilter.parse("status = 'on hold'", COLUMNS).bindings()).containsEntry("wf0", "on hold");
        assertThat(WidgetFilter.parse("amount = 12.5", COLUMNS).bindings())
                .containsEntry("wf0", new java.math.BigDecimal("12.5"));
    }

    @Test
    void nullBecomesIsNullSemantics() {
        assertThat(WidgetFilter.parse("client = null", COLUMNS).sql()).isEqualTo("client IS NULL");
        assertThat(WidgetFilter.parse("client != null", COLUMNS).sql()).isEqualTo("client IS NOT NULL");
        assertThat(WidgetFilter.parse("client = null", COLUMNS).bindings()).isEmpty();
    }

    @Test
    void chainsClausesWithAnd() {
        WidgetFilter.Result r = WidgetFilter.parse("status = open AND amount > 0", COLUMNS);
        assertThat(r.sql()).isEqualTo("status = :wf0 AND amount > :wf1");
        assertThat(r.bindings()).containsEntry("wf0", "open").containsEntry("wf1", 0L);
    }

    @Test
    void systemColumnsAreAllowed() {
        assertThat(WidgetFilter.parse("_posted = true", COLUMNS).sql()).isEqualTo("_posted = :wf0");
        assertThat(WidgetFilter.parse("_posted = true", COLUMNS).bindings()).containsEntry("wf0", Boolean.TRUE);
    }

    @Test
    void unknownColumnIsIgnored() {
        assertThat(WidgetFilter.parse("secret_column = 1", COLUMNS).isEmpty()).isTrue();
    }

    @Test
    void injectionInColumnPositionIsRejected() {
        // A semicolon / SQL fragment in the LHS never matches the identifier pattern.
        assertThat(WidgetFilter.parse("status; DROP TABLE bills = 1", COLUMNS).isEmpty()).isTrue();
        assertThat(WidgetFilter.parse("1=1 OR status = open", COLUMNS).isEmpty()).isTrue();
    }

    @Test
    void injectionInValuePositionStaysBound() {
        // A malicious-looking value is captured verbatim as a bound parameter, not SQL.
        WidgetFilter.Result r = WidgetFilter.parse("status = '0; DROP TABLE bills'", COLUMNS);
        assertThat(r.sql()).isEqualTo("status = :wf0");
        assertThat(r.bindings()).containsEntry("wf0", "0; DROP TABLE bills");
    }
}
