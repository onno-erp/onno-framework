package su.onno.ui;

import org.junit.jupiter.api.Test;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;

import java.security.Principal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code mainRecordRoutes} comes from the views that opt in ({@link EntityView#opensInMain()}), not
 * from the navigation: an entity reached only through links still opens its records in the main pane.
 */
class DivKitControllerMainRecordRoutesTest {

    private static final class WeddingEvent {}
    private static final class Invoice {}
    private static final class Supplier {}
    private static final class Secret {}

    private static EntityView view(Class<?> entity, boolean opensInMain) {
        return new EntityView() {
            @Override public Class<?> entity() { return entity; }
            @Override public boolean opensInMain() { return opensInMain; }
        };
    }

    private static CatalogDescriptor catalog(String name, Class<?> type) {
        return new CatalogDescriptor(name, name, name.toLowerCase(), type, 9, false, true, "", "",
                List.of(), List.of(), List.of());
    }

    @Test
    void routesEveryReadableOptedInEntityWithoutANavItem() {
        UiViewResolver views = new UiViewResolver(null, List.of(
                view(WeddingEvent.class, true),
                view(Invoice.class, true),
                view(Supplier.class, false),
                view(Secret.class, true)));
        CatalogQueryService catalogs = mock(CatalogQueryService.class);
        DocumentQueryService documents = mock(DocumentQueryService.class);
        UiAccessService access = mock(UiAccessService.class);
        Principal principal = () -> "planner";

        CatalogDescriptor weddings = catalog("WeddingEvents", WeddingEvent.class);
        CatalogDescriptor secrets = catalog("Secrets", Secret.class);
        DocumentDescriptor invoices = mock(DocumentDescriptor.class);
        when(invoices.logicalName()).thenReturn("SupplierInvoices");
        when(catalogs.forClass(WeddingEvent.class)).thenReturn(weddings);
        when(catalogs.forClass(Secret.class)).thenReturn(secrets);
        when(documents.forClass(Invoice.class)).thenReturn(invoices);
        when(access.canRead(eq(principal), any(CatalogDescriptor.class))).thenReturn(true);
        when(access.canRead(principal, secrets)).thenReturn(false);
        when(access.canRead(principal, invoices)).thenReturn(true);

        List<String> routes = DivKitController.mainRecordRoutes(views, catalogs, documents, access, principal, "");

        assertThat(routes).containsExactly("/catalogs/wedding_events", "/documents/supplier_invoices");
    }
}
