package com.onec.spring.fixtures;

import com.onec.repository.CatalogRepository;

/**
 * Typed Spring Data JDBC repository over a catalog with an enum attribute. Used by
 * {@code EnumAttributeRepositoryIT} to prove {@code @Enumeration} attributes round-trip
 * through {@code save(...)} / {@code findById(...)} (issue #26).
 */
public interface TestServiceRepository extends CatalogRepository<TestService> {
}
