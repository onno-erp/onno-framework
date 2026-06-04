package com.onec.security;

import com.onec.metadata.AttributeDescriptor;
import com.onec.metadata.MetadataRegistry;
import com.onec.model.CatalogObject;
import com.onec.model.DocumentObject;

import java.lang.reflect.Field;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Applies a transform to the secret String fields of a typed catalog/document aggregate.
 * Used by the Spring Data callbacks to encrypt before write and decrypt after read, so
 * application code that goes through the repositories always sees plaintext while the
 * database only ever holds ciphertext.
 */
public final class SecretFields {

    private SecretFields() {}

    public static void apply(Object aggregate, MetadataRegistry registry, UnaryOperator<String> transform) {
        List<AttributeDescriptor> attributes = secretAttributes(aggregate, registry);
        if (attributes.isEmpty()) return;
        for (AttributeDescriptor attr : attributes) {
            Field field = findField(aggregate.getClass(), attr.fieldName());
            if (field == null) continue;
            try {
                field.setAccessible(true);
                Object value = field.get(aggregate);
                if (value instanceof String s) {
                    field.set(aggregate, transform.apply(s));
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Failed to access secret field " + attr.fieldName(), e);
            }
        }
    }

    private static List<AttributeDescriptor> secretAttributes(Object aggregate, MetadataRegistry registry) {
        List<AttributeDescriptor> attributes;
        if (aggregate instanceof CatalogObject) {
            attributes = registry.getCatalogDescriptor(aggregate.getClass()).attributes();
        } else if (aggregate instanceof DocumentObject) {
            attributes = registry.getDocumentDescriptor(aggregate.getClass()).attributes();
        } else {
            return List.of();
        }
        return attributes.stream().filter(AttributeDescriptor::secret).toList();
    }

    private static Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
