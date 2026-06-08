package com.onec.schema;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class SqlDialectTest {

    @Test
    void h2_emitsMergeKeySyntax() {
        String sql = SqlDialect.H2.upsert(
                "enum_gender",
                List.of("_id", "_name", "_order"),
                List.of("_id"),
                List.of(":id", ":name", ":order"));

        assertThat(sql).isEqualTo(
                "MERGE INTO enum_gender (_id, _name, _order) KEY(_id) VALUES (:id, :name, :order)");
    }

    @Test
    void postgresql_emitsOnConflictDoUpdate() {
        String sql = SqlDialect.POSTGRESQL.upsert(
                "enum_gender",
                List.of("_id", "_name", "_order"),
                List.of("_id"),
                List.of(":id", ":name", ":order"));

        assertThat(sql).isEqualTo(
                "INSERT INTO enum_gender (_id, _name, _order) VALUES (:id, :name, :order)"
                        + " ON CONFLICT (_id) DO UPDATE SET _name = EXCLUDED._name, _order = EXCLUDED._order");
    }

    @Test
    void postgresql_constants_updatesValueOnConflict() {
        String sql = SqlDialect.POSTGRESQL.upsert(
                "constants",
                List.of("_name", "_value"),
                List.of("_name"),
                List.of(":name", ":value"));

        assertThat(sql).isEqualTo(
                "INSERT INTO constants (_name, _value) VALUES (:name, :value)"
                        + " ON CONFLICT (_name) DO UPDATE SET _value = EXCLUDED._value");
    }

    @Test
    void postgresql_allKeyColumns_doNothing() {
        String sql = SqlDialect.POSTGRESQL.upsert(
                "t", List.of("_id"), List.of("_id"), List.of(":id"));

        assertThat(sql).isEqualTo("INSERT INTO t (_id) VALUES (:id) ON CONFLICT (_id) DO NOTHING");
    }
}
