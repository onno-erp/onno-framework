package su.onno.spring;

import su.onno.metadata.EnumerationDescriptor;
import su.onno.repository.EnumerationPersistence;
import su.onno.types.Ref;

import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.GenericConverter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Spring Data JDBC converters that bridge our value-object types ({@link Ref} and framework enums)
 * and the UUID columns emitted by the schema generator.
 */
public final class EnumUuidConverters {

    private EnumUuidConverters() {}

    public static List<Object> build(Collection<EnumerationDescriptor> descriptors) {
        Set<Class<? extends Enum<?>>> enumClasses = new LinkedHashSet<>();
        for (EnumerationDescriptor d : descriptors) {
            enumClasses.add(d.javaClass());
        }
        List<Object> converters = new ArrayList<>();
        converters.add(new EnumToUuid(enumClasses));
        converters.add(new UuidToEnum(enumClasses));
        converters.add(new StringToEnum(enumClasses));
        converters.add(new RefToUuid());
        converters.add(new UuidToRef());
        converters.add(new StringToRef());
        return converters;
    }

    @WritingConverter
    public static final class EnumToUuid implements GenericConverter {
        private final Set<ConvertiblePair> pairs;

        EnumToUuid(Set<Class<? extends Enum<?>>> enumClasses) {
            Set<ConvertiblePair> p = new LinkedHashSet<>();
            for (Class<?> ec : enumClasses) {
                p.add(new ConvertiblePair(ec, UUID.class));
            }
            this.pairs = Set.copyOf(p);
        }

        @Override
        public Set<ConvertiblePair> getConvertibleTypes() {
            return pairs;
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        @Nullable
        public Object convert(@Nullable Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
            if (source == null) return null;
            return EnumerationPersistence.resolveId((Class) source.getClass(), (Enum) source);
        }
    }

    @ReadingConverter
    public static final class UuidToEnum implements GenericConverter {
        private final Set<ConvertiblePair> pairs;

        UuidToEnum(Set<Class<? extends Enum<?>>> enumClasses) {
            Set<ConvertiblePair> p = new LinkedHashSet<>();
            for (Class<?> ec : enumClasses) {
                p.add(new ConvertiblePair(UUID.class, ec));
            }
            this.pairs = Set.copyOf(p);
        }

        @Override
        public Set<ConvertiblePair> getConvertibleTypes() {
            return pairs;
        }

        @Override
        @Nullable
        public Object convert(@Nullable Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
            if (source == null) return null;
            return EnumerationPersistence.resolveValue(targetType.getType(), (UUID) source);
        }
    }

    /**
     * Reads a framework enum back from a {@code varchar} column that holds the enum's UUID as text.
     *
     * <p>{@link UuidToEnum} only fires when the JDBC value comes back as a {@link UUID}, which
     * requires the column to be typed {@code uuid}. When an attribute changed from {@code String}
     * to an {@code @Enumeration} on a database whose column was already provisioned as
     * {@code varchar}, onno keeps writing the enum's UUID but the driver hands it back as a
     * {@link String}. Without this converter Spring Data falls through to {@code Enum.valueOf(<uuid
     * string>)} and throws {@code IllegalArgumentException: No enum constant ...} (issue #168).
     *
     * <p>The value is parsed as a UUID and resolved through {@link EnumerationPersistence}; if it is
     * not a UUID (truly legacy data persisted as the enum's {@code name()} before onno used UUIDs)
     * it falls back to {@link Enum#valueOf}, preserving the historical behaviour.
     */
    @ReadingConverter
    public static final class StringToEnum implements GenericConverter {
        private final Set<ConvertiblePair> pairs;

        StringToEnum(Set<Class<? extends Enum<?>>> enumClasses) {
            Set<ConvertiblePair> p = new LinkedHashSet<>();
            for (Class<?> ec : enumClasses) {
                p.add(new ConvertiblePair(String.class, ec));
            }
            this.pairs = Set.copyOf(p);
        }

        @Override
        public Set<ConvertiblePair> getConvertibleTypes() {
            return pairs;
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        @Nullable
        public Object convert(@Nullable Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
            if (source == null) return null;
            String text = ((String) source).trim();
            if (text.isEmpty()) return null;
            UUID id = tryParseUuid(text);
            if (id != null) {
                return EnumerationPersistence.resolveValue(targetType.getType(), id);
            }
            return Enum.valueOf((Class) targetType.getType(), text);
        }
    }

    @WritingConverter
    public static final class RefToUuid implements Converter<Ref<?>, UUID> {
        @Override
        @Nullable
        public UUID convert(Ref<?> source) {
            return source == null ? null : source.id();
        }
    }

    @ReadingConverter
    public static final class UuidToRef implements GenericConverter {
        @Override
        public Set<ConvertiblePair> getConvertibleTypes() {
            return Set.of(new ConvertiblePair(UUID.class, Ref.class));
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        @Nullable
        public Object convert(@Nullable Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
            if (source == null) return null;
            Class<?> targetGeneric = targetType.getResolvableType().getGeneric(0).resolve();
            if (targetGeneric == null) {
                targetGeneric = Object.class;
            }
            return Ref.of((Class) targetGeneric, (UUID) source);
        }
    }

    /**
     * Reads a {@link Ref} back from a {@code varchar} column that holds the referenced id as text.
     *
     * <p>The {@code Ref → uuid} column is the norm, but a column that pre-existed as {@code varchar}
     * (e.g. an attribute retyped from {@code String} to {@code Ref<T>}) hands the id back as a
     * {@link String}. This mirrors {@link StringToEnum} for the reference case so such legacy and
     * migrated columns round-trip instead of throwing.
     */
    @ReadingConverter
    public static final class StringToRef implements GenericConverter {
        @Override
        public Set<ConvertiblePair> getConvertibleTypes() {
            return Set.of(new ConvertiblePair(String.class, Ref.class));
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        @Nullable
        public Object convert(@Nullable Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
            if (source == null) return null;
            String text = ((String) source).trim();
            if (text.isEmpty()) return null;
            UUID id = tryParseUuid(text);
            if (id == null) return null;
            Class<?> targetGeneric = targetType.getResolvableType().getGeneric(0).resolve();
            if (targetGeneric == null) {
                targetGeneric = Object.class;
            }
            return Ref.of((Class) targetGeneric, id);
        }
    }

    @Nullable
    private static UUID tryParseUuid(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
