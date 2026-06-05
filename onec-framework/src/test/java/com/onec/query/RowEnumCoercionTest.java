package com.onec.query;

import com.onec.repository.EnumerationPersistence;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for the enum-as-UUID bug in the typed query layer. Enum attributes are stored as
 * their stable UUID (see {@code SchemaGenerator}/{@link EnumerationPersistence}), so when a
 * {@code fetchInto(...)} DTO has an enum field, {@link RowMapper#coerce} must resolve the UUID
 * back to the constant — it used to call {@code Enum.valueOf(type, uuid.toString())}, which threw
 * {@code IllegalArgumentException} ("No enum constant ...&lt;uuid&gt;").
 */
class RowEnumCoercionTest {

    enum Status {
        DRAFT,
        POSTED
    }

    @Test
    void coerce_resolvesEnumFromStoredUuid() {
        UUID id = EnumerationPersistence.resolveId(Status.class, Status.POSTED);
        assertThat(RowMapper.coerce(id, Status.class)).isEqualTo(Status.POSTED);
    }

    @Test
    void coerce_resolvesEnumFromStoredUuidString() {
        UUID id = EnumerationPersistence.resolveId(Status.class, Status.DRAFT);
        assertThat(RowMapper.coerce(id.toString(), Status.class)).isEqualTo(Status.DRAFT);
    }

    @Test
    void coerce_fallsBackToConstantNameForPlainStrings() {
        // A plain (non-UUID) string still maps by constant name, so name-keyed columns keep working.
        assertThat(RowMapper.coerce("POSTED", Status.class)).isEqualTo(Status.POSTED);
    }

    @Test
    void coerce_passesThroughEnumInstance() {
        assertThat(RowMapper.coerce(Status.DRAFT, Status.class)).isEqualTo(Status.DRAFT);
    }
}
