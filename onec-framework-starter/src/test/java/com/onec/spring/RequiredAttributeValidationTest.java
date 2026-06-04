package com.onec.spring;

import com.onec.metadata.DefaultNamingStrategy;
import com.onec.metadata.MetadataRegistry;
import com.onec.metadata.MetadataScanner;
import com.onec.numbering.NumberGenerator;
import com.onec.security.SecretCipher;
import com.onec.spring.fixtures.TestRequiredCatalog;
import com.onec.validation.ValidationException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A missing required attribute must raise the typed {@link ValidationException} (which the web layer
 * maps to HTTP 400), not a raw {@code IllegalStateException} that surfaces as a 500 (issue #32).
 */
class RequiredAttributeValidationTest {

    private final MetadataRegistry registry = buildRegistry();
    private final OnecBeforeConvertCallback callback =
            new OnecBeforeConvertCallback(registry, noopNumberGenerator(), new SecretCipher("test-secret-key"));

    @Test
    void missingRequiredAttribute_throwsTypedValidationException() {
        TestRequiredCatalog entity = new TestRequiredCatalog();
        entity.setCode("R-1");
        // name (required) left null

        assertThatThrownBy(() -> callback.onBeforeConvert(entity))
                .isInstanceOf(ValidationException.class)
                // The message now uses the human-readable display name ("Name is required").
                .hasMessageContaining("Name");

        ValidationException ex = (ValidationException) catching(() -> callback.onBeforeConvert(entity));
        assertThat(ex.getField()).isEqualTo("name");
    }

    @Test
    void presentRequiredAttribute_passes() {
        TestRequiredCatalog entity = new TestRequiredCatalog();
        entity.setCode("R-2");
        entity.setName("Present");

        callback.onBeforeConvert(entity); // must not throw
    }

    private static Throwable catching(Runnable r) {
        try {
            r.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private static MetadataRegistry buildRegistry() {
        MetadataRegistry registry = new MetadataRegistry();
        registry.registerCatalog(new MetadataScanner(new DefaultNamingStrategy()).scan(TestRequiredCatalog.class));
        return registry;
    }

    private static NumberGenerator noopNumberGenerator() {
        return new NumberGenerator() {
            @Override
            public String nextNumber(String entityName, int length) {
                return "0";
            }

            @Override
            public String nextCode(String entityName, int length) {
                return "0";
            }
        };
    }
}
