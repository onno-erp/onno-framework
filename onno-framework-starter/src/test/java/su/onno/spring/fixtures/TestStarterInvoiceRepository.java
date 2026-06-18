package su.onno.spring.fixtures;

import su.onno.repository.DocumentRepository;

/**
 * Typed Spring Data JDBC repository over a document that carries a {@code @TabularSection}.
 * Used by {@code TabularSectionRepositoryIT} to prove line items round-trip through the
 * generated repositories (and {@code RefResolver}), not just the generic JDBI CRUD API.
 */
public interface TestStarterInvoiceRepository extends DocumentRepository<TestStarterInvoice> {
}
