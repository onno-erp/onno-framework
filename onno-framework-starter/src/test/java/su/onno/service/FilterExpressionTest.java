package su.onno.service;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FilterExpressionTest {

    private static final Set<String> COLUMNS = Set.of("status", "amount", "client");

    @Test
    void blankFilterIsEmpty() {
        assertThat(FilterExpression.parse(null, COLUMNS).isEmpty()).isTrue();
        assertThat(FilterExpression.parse("   ", COLUMNS).isEmpty()).isTrue();
    }

    @Test
    void bindsValueAndNeverInlinesIt() {
        FilterExpression.Result r = FilterExpression.parse("status != cancelled", COLUMNS);
        assertThat(r.sql()).isEqualTo("status <> :wf0");
        assertThat(r.bindings()).containsEntry("wf0", "cancelled");
    }

    @Test
    void typesLiterals() {
        assertThat(FilterExpression.parse("amount >= 100", COLUMNS).bindings()).containsEntry("wf0", 100L);
        assertThat(FilterExpression.parse("status = 'on hold'", COLUMNS).bindings()).containsEntry("wf0", "on hold");
        assertThat(FilterExpression.parse("amount = 12.5", COLUMNS).bindings())
                .containsEntry("wf0", new java.math.BigDecimal("12.5"));
    }

    @Test
    void bindsUuidColumnsTyped() {
        java.util.UUID id = java.util.UUID.randomUUID();
        // A uuid column (ref/enum) binds a typed UUID — PostgreSQL rejects a varchar param there.
        assertThat(FilterExpression.parse("client = " + id, COLUMNS, Set.of("client")).bindings())
                .containsEntry("wf0", id);
        // A non-uuid column keeps the varchar bind even for a uuid-shaped value.
        assertThat(FilterExpression.parse("status = " + id, COLUMNS, Set.of("client")).bindings())
                .containsEntry("wf0", id.toString());
        // A malformed uuid degrades to the varchar bind rather than failing the parse.
        assertThat(FilterExpression.parse("client = notauuid", COLUMNS, Set.of("client")).bindings())
                .containsEntry("wf0", "notauuid");
    }

    @Test
    void nullBecomesIsNullSemantics() {
        assertThat(FilterExpression.parse("client = null", COLUMNS).sql()).isEqualTo("client IS NULL");
        assertThat(FilterExpression.parse("client != null", COLUMNS).sql()).isEqualTo("client IS NOT NULL");
        assertThat(FilterExpression.parse("client = null", COLUMNS).bindings()).isEmpty();
    }

    @Test
    void chainsClausesWithAnd() {
        FilterExpression.Result r = FilterExpression.parse("status = open AND amount > 0", COLUMNS);
        assertThat(r.sql()).isEqualTo("status = :wf0 AND amount > :wf1");
        assertThat(r.bindings()).containsEntry("wf0", "open").containsEntry("wf1", 0L);
    }

    @Test
    void systemColumnsAreAllowed() {
        assertThat(FilterExpression.parse("_posted = true", COLUMNS).sql()).isEqualTo("_posted = :wf0");
        assertThat(FilterExpression.parse("_posted = true", COLUMNS).bindings()).containsEntry("wf0", Boolean.TRUE);
    }

    @Test
    void unknownColumnCausesErrorThroughLegacyOverload() {
        assertThatThrownBy(() -> FilterExpression.parse("secret_column = 1", COLUMNS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secret_column");
    }

    @Test
    void injectionInColumnPositionIsRejected() {
        // A semicolon / SQL fragment in the LHS never matches the identifier pattern.
        assertThat(FilterExpression.parse("status; DROP TABLE bills = 1", COLUMNS).isEmpty()).isTrue();
        assertThat(FilterExpression.parse("1=1 OR status = open", COLUMNS).isEmpty()).isTrue();
    }

    @Test
    void injectionInValuePositionStaysBound() {
        // A malicious-looking value is captured verbatim as a bound parameter, not SQL.
        FilterExpression.Result r = FilterExpression.parse("status = '0; DROP TABLE bills'", COLUMNS);
        assertThat(r.sql()).isEqualTo("status = :wf0");
        assertThat(r.bindings()).containsEntry("wf0", "0; DROP TABLE bills");
    }

    @Test
    void logicalPostedNameResolvesToSystemColumn() {
        FilterExpression.Result result = FilterExpression.parse("posted = true", COLUMNS, Set.of(), field -> field.equals("posted") ? "_posted" : field);
        assertThat(result.sql()).isEqualTo("_posted = :wf0");
        assertThat(result.bindings()).containsEntry("wf0", true);
    }

    @Test
    void unknownFieldCausesError() {
        assertThatThrownBy(() -> FilterExpression.parse("postedd = true", COLUMNS, Set.of(), field -> field))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("postedd");
    }
}
