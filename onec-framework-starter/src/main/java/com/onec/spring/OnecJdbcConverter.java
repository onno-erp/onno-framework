package com.onec.spring;

import org.springframework.data.convert.CustomConversions;
import org.springframework.data.jdbc.core.convert.JdbcTypeFactory;
import org.springframework.data.jdbc.core.convert.MappingJdbcConverter;
import org.springframework.data.jdbc.core.convert.RelationResolver;
import org.springframework.data.relational.core.mapping.RelationalMappingContext;
import org.springframework.data.relational.core.mapping.RelationalPersistentProperty;

import java.util.Optional;

/**
 * A {@link MappingJdbcConverter} that fixes how framework enums map onto their database column type.
 *
 * <p>Spring Data JDBC resolves the column type of any enum property to {@code String} via
 * {@code JdbcColumnTypes} (which hard-maps {@code Enum -> String}) <em>before</em> consulting custom
 * conversions. The schema generator, however, emits a {@code UUID} column for {@code @Enumeration}
 * attributes (each enum value has a stable name-based UUID), and {@link EnumUuidConverters} registers
 * an {@code Enum -> UUID} writing converter. Because the converter's target ({@code UUID}) is never
 * asked for — the framework requests an {@code Enum -> String} conversion against the resolved column
 * type — the enum is bound as its {@code name()} string into a {@code UUID} column and the insert
 * fails at runtime (issue #26).
 *
 * <p>This subclass overrides {@link #getColumnType(RelationalPersistentProperty)} so that any enum
 * for which a custom write target is registered (i.e. our framework enums, which convert to
 * {@code UUID}) reports that write target as its column type. With the column type now {@code UUID},
 * the writing path converts the enum through {@link EnumUuidConverters.EnumToUuid} and binds a real
 * {@code UUID}. Enums without a custom write target keep Spring's default {@code String} behaviour.
 */
public class OnecJdbcConverter extends MappingJdbcConverter {

    public OnecJdbcConverter(RelationalMappingContext context, RelationResolver relationResolver,
                             CustomConversions conversions, JdbcTypeFactory typeFactory) {
        super(context, relationResolver, conversions, typeFactory);
    }

    @Override
    public Class<?> getColumnType(RelationalPersistentProperty property) {
        Class<?> actualType = property.getActualType();
        if (actualType != null && actualType.isEnum()) {
            Optional<Class<?>> writeTarget = getConversions().getCustomWriteTarget(actualType);
            if (writeTarget.isPresent()) {
                return writeTarget.get();
            }
        }
        return super.getColumnType(property);
    }
}
