package com.example;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import su.onno.archunit.OnnoRules;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** The bookstore keeps every onno architecture rule — the same test an application copies. */
class OnnoArchitectureTest {

    private static final JavaClasses APP = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.example");

    @Test
    void followsOnnoRules() {
        assertFalse(APP.isEmpty(), "imported no application classes");
        OnnoRules.all().check(APP);
        OnnoRules.COLLECTION_ENDPOINTS_RETURN_KEYSET_WINDOWS.check(APP);
    }
}
