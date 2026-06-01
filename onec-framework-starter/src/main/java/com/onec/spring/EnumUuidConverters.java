package com.onec.spring;

import com.onec.metadata.EnumerationDescriptor;
import com.onec.repository.EnumerationPersistence;
import com.onec.types.Ref;

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
        converters.add(new RefToUuid());
        converters.add(new UuidToRef());
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
}
