package su.onno.service;

import su.onno.repository.EnumerationPersistence;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The structured {@code where} compiler: grammar, value binding and its 400s. */
class WhereTest {

    enum Status { OPEN, CLOSED }

    private static final Function<String, String> RESOLVER = field -> switch (field) {
        case "posted" -> "_posted";
        case "date" -> "_date";
        case "number" -> "_number";
        case "assignedTo" -> "assigned_to";
        case "dueOn" -> "due_on";
        default -> field;
    };

    private static final Where.Schema SCHEMA = new Where.Schema(Map.of(
            "_posted", new Where.Column("_posted", Where.Type.BOOLEAN),
            "_date", new Where.Column("_date", Where.Type.DATETIME),
            "_number", new Where.Column("_number", Where.Type.TEXT),
            "total", new Where.Column("total", Where.Type.NUMBER),
            "status", new Where.Column("status", Where.Type.UUID, Status.class),
            "assigned_to", new Where.Column("assigned_to", Where.Type.UUID),
            "due_on", new Where.Column("due_on", Where.Type.DATE),
            "note", new Where.Column("note", Where.Type.TEXT)), RESOLVER);

    private static SqlPredicate compile(String json) {
        return Where.compile(json, SCHEMA);
    }

    @Test
    void blankAndEmptyObjectAreNoPredicate() {
        assertThat(compile(null).isEmpty()).isTrue();
        assertThat(compile("  ").isEmpty()).isTrue();
        assertThat(compile("{}").isEmpty()).isTrue();
        assertThat(compile("{\"total\":{}}").isEmpty()).isTrue();
        assertThat(compile("{\"not\":{}}").isEmpty()).isTrue();
    }

    @Test
    void literalIsEqualityOnTheResolvedStorageColumn() {
        SqlPredicate r = compile("{\"posted\":true}");
        assertThat(r.sql()).isEqualTo("_posted = :wh0");
        assertThat(r.bindings()).containsExactly(Map.entry("wh0", true));
    }

    @Test
    void severalKeysAndOperatorsAreAnded() {
        SqlPredicate r = compile("{\"posted\":true,\"total\":{\"gte\":100,\"lt\":1000.5}}");
        assertThat(r.sql()).isEqualTo("(_posted = :wh0) AND ((total >= :wh1) AND (total < :wh2))");
        assertThat(r.bindings().get("wh1")).isEqualTo(new BigDecimal("100"));
        assertThat(r.bindings().get("wh2")).isEqualTo(new BigDecimal("1000.5"));
    }

    @Test
    void everyComparisonOperator() {
        assertThat(compile("{\"total\":{\"eq\":1}}").sql()).isEqualTo("total = :wh0");
        assertThat(compile("{\"total\":{\"not\":1}}").sql()).isEqualTo("total <> :wh0");
        assertThat(compile("{\"total\":{\"gt\":1}}").sql()).isEqualTo("total > :wh0");
        assertThat(compile("{\"total\":{\"gte\":1}}").sql()).isEqualTo("total >= :wh0");
        assertThat(compile("{\"total\":{\"lt\":1}}").sql()).isEqualTo("total < :wh0");
        assertThat(compile("{\"total\":{\"lte\":1}}").sql()).isEqualTo("total <= :wh0");
        assertThat(compile("{\"total\":{\"lte\":\"2.5\"}}").bindings().get("wh0")).isEqualTo(new BigDecimal("2.5"));
    }

    @Test
    void nullMeansIsNullAndNotNullIsNotNull() {
        assertThat(compile("{\"assignedTo\":null}").sql()).isEqualTo("assigned_to IS NULL");
        assertThat(compile("{\"assignedTo\":{\"eq\":null}}").sql()).isEqualTo("assigned_to IS NULL");
        SqlPredicate notNull = compile("{\"assignedTo\":{\"not\":null}}");
        assertThat(notNull.sql()).isEqualTo("assigned_to IS NOT NULL");
        assertThat(notNull.bindings()).isEmpty();
    }

    @Test
    void refAndEnumValuesBindAsTypedUuids() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        SqlPredicate r = compile("{\"status\":{\"in\":[\"" + a + "\",\"" + b + "\"]},\"assignedTo\":\"" + a + "\"}");
        assertThat(r.sql()).isEqualTo("(status IN (:wh0, :wh1)) AND (assigned_to = :wh2)");
        assertThat(r.bindings().values()).containsExactly(a, b, a);
        assertThat(r.bindings().values()).allMatch(UUID.class::isInstance);
    }

    @Test
    void enumAlsoAcceptsItsConstantName() {
        SqlPredicate r = compile("{\"status\":\"CLOSED\"}");
        assertThat(r.bindings().get("wh0")).isEqualTo(EnumerationPersistence.resolveId(Status.class, Status.CLOSED));
    }

    @Test
    void inAndNotInHandleNullsAndEmptyLists() {
        UUID a = UUID.randomUUID();
        assertThat(compile("{\"status\":{\"in\":[\"" + a + "\",null]}}").sql())
                .isEqualTo("(status IN (:wh0) OR status IS NULL)");
        assertThat(compile("{\"status\":{\"in\":[null]}}").sql()).isEqualTo("status IS NULL");
        assertThat(compile("{\"status\":{\"in\":[]}}").sql()).isEqualTo("1=0");
        assertThat(compile("{\"status\":{\"notIn\":[\"" + a + "\"]}}").sql()).isEqualTo("NOT (status IN (:wh0))");
        assertThat(compile("{\"status\":{\"notIn\":[]}}").sql()).isEqualTo("NOT (1=0)");
    }

    @Test
    void containsAndStartsWithAreCaseInsensitiveAndEscapeWildcards() {
        SqlPredicate contains = compile("{\"note\":{\"contains\":\"Rush_50%\"}}");
        assertThat(contains.sql()).isEqualTo("LOWER(CAST(note AS VARCHAR)) LIKE :wh0 ESCAPE '\\'");
        assertThat(contains.bindings().get("wh0")).isEqualTo("%rush\\_50\\%%");
        SqlPredicate prefix = compile("{\"number\":{\"startsWith\":\"SO-\"}}");
        assertThat(prefix.sql()).isEqualTo("LOWER(CAST(_number AS VARCHAR)) LIKE :wh0 ESCAPE '\\'");
        assertThat(prefix.bindings().get("wh0")).isEqualTo("so-%");
    }

    @Test
    void orNotAndCombinators() {
        UUID a = UUID.randomUUID();
        SqlPredicate r = compile("{\"or\":[{\"status\":\"" + a + "\"},{\"posted\":true}],"
                + "\"not\":{\"note\":\"x\"},\"and\":[{\"total\":{\"gt\":0}}]}");
        assertThat(r.sql()).isEqualTo("((status = :wh0) OR (_posted = :wh1)) AND (NOT (note = :wh2)) AND (total > :wh3)");
        assertThat(compile("{\"or\":[]}").sql()).isEqualTo("1=0");
        assertThat(compile("{\"total\":{\"not\":{\"in\":[1,2]}}}").sql()).isEqualTo("NOT (total IN (:wh0, :wh1))");
    }

    @Test
    void temporalsBindTyped() {
        assertThat(compile("{\"date\":{\"gte\":\"2024-01-05\"}}").bindings().get("wh0"))
                .isEqualTo(LocalDateTime.of(2024, 1, 5, 0, 0));
        assertThat(compile("{\"date\":{\"lt\":\"2024-01-05T10:30:00\"}}").bindings().get("wh0"))
                .isEqualTo(LocalDateTime.of(2024, 1, 5, 10, 30));
        assertThat(compile("{\"date\":{\"lt\":\"2024-01-05T10:30:00Z\"}}").bindings().get("wh0"))
                .isEqualTo(LocalDateTime.of(2024, 1, 5, 10, 30));
        assertThat(compile("{\"dueOn\":\"2024-02-29\"}").bindings().get("wh0")).isEqualTo(LocalDate.of(2024, 2, 29));
    }

    @Test
    void unknownFieldIsA400NamingIt() {
        assertBadRequest("{\"nope\":1}", "nope");
        assertBadRequest("{\"or\":[{\"total\":1},{\"secretThing\":1}]}", "secretThing");
        assertBadRequest("{\"Robert'); DROP TABLE x;--\":1}", "Unknown where field");
    }

    @Test
    void malformedInputIsA400() {
        assertBadRequest("{not json", "not valid JSON");
        assertBadRequest("[1,2]", "JSON object");
        assertBadRequest("{\"total\":{\"between\":[1,2]}}", "between");
        assertBadRequest("{\"total\":[1,2]}", "in");
        assertBadRequest("{\"total\":{\"gt\":null}}", "null");
        assertBadRequest("{\"total\":\"abc\"}", "total");
        assertBadRequest("{\"status\":\"not-a-uuid\"}", "UUID");
        assertBadRequest("{\"posted\":\"maybe\"}", "true or false");
        assertBadRequest("{\"date\":{\"gte\":\"yesterday\"}}", "date");
        assertBadRequest("{\"status\":{\"in\":\"x\"}}", "array");
        assertBadRequest("{\"or\":{\"total\":1}}", "array");
        assertBadRequest("{\"note\":{\"contains\":{\"a\":1}}}", "string");
    }

    @Test
    void nestingDepthAndListSizeAreBounded() {
        String deep = "{\"total\":1}";
        for (int i = 0; i < Where.MAX_DEPTH; i++) {
            deep = "{\"not\":" + deep + "}";
        }
        assertBadRequest(deep, "nested too deeply");

        String ok = "{\"total\":1}";
        for (int i = 0; i < Where.MAX_DEPTH - 1; i++) {
            ok = "{\"not\":" + ok + "}";
        }
        assertThat(compile(ok).isEmpty()).isFalse();

        String[] values = new String[Where.MAX_IN_VALUES + 1];
        Arrays.fill(values, "1");
        assertBadRequest("{\"total\":{\"in\":[" + String.join(",", values) + "]}}", "at most");
        assertThat(compile("{\"total\":{\"in\":[" + String.join(",", List.of(values).subList(0, Where.MAX_IN_VALUES)) + "]}}")
                .bindings()).hasSize(Where.MAX_IN_VALUES);
    }

    @Test
    void andCombinesTwoCompiledPredicates() {
        SqlPredicate legacy = new SqlPredicate("total > :wf0", java.util.Map.of("wf0", 5));
        SqlPredicate where = compile("{\"posted\":true}");
        SqlPredicate both = Where.and(legacy, where);
        assertThat(both.sql()).isEqualTo("(total > :wf0) AND (_posted = :wh0)");
        assertThat(both.bindings()).containsOnlyKeys("wf0", "wh0");
        assertThat(Where.and(legacy, compile(null))).isSameAs(legacy);
        assertThat(Where.and(compile(null), where)).isSameAs(where);
    }

    private static void assertBadRequest(String json, String messagePart) {
        assertThatThrownBy(() -> compile(json))
                .isInstanceOfSatisfying(ServiceException.class, e -> {
                    assertThat(e.status()).isEqualTo(ServiceException.Status.BAD_REQUEST);
                    assertThat(e.getReason()).contains(messagePart);
                });
    }
}
