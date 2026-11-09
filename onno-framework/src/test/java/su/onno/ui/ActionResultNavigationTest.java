package su.onno.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import su.onno.annotations.Catalog;
import su.onno.annotations.Document;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;
import su.onno.types.Ref;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActionResultNavigationTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000449");

    @Test
    void opensCatalogByLogicalNameRatherThanClassTableOrTitle() {
        assertThat(ActionResult.open(Ref.of(Customer.class, ID)))
                .isEqualTo(new ActionResult(false, null, "/catalogs/sales_customers/" + ID));
    }

    @Test
    void opensDocumentByLogicalNameRatherThanClassTableOrTitle() {
        assertThat(ActionResult.open(Ref.of(Invoice.class, ID)).navigateTo())
                .isEqualTo("/documents/sales_invoices/" + ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/reports/sales?period=month", "onno://documents/sales_invoices/new", "onno://"})
    void preservesSupportedRoutes(String route) {
        ActionResult result = ActionResult.navigate(route);
        assertThat(result.navigateTo()).isEqualTo(route);
        assertThat(result.refresh()).isFalse();
        assertThat(result.feedback()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "reports/sales", "https://example.com", "//example.com", "/\\example.com"})
    void rejectsRoutesThatTheWorkspaceCannotNavigate(String route) {
        assertThatThrownBy(() -> ActionResult.navigate(route)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingRouteAndReference() {
        assertThatThrownBy(() -> ActionResult.navigate(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ActionResult.open(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsReferencesWithoutAnEntityRoute() {
        assertThatThrownBy(() -> ActionResult.open(Ref.of(String.class, ID)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ActionResult.open(Ref.of(Ambiguous.class, ID)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesTheExistingConstructorAndAllowsCombinedResults() {
        ActionFeedback feedback = ActionToast.success("Created").feedback();
        ActionResult original = new ActionResult(true, feedback);
        assertThat(original.refresh()).isTrue();
        assertThat(original.feedback()).isSameAs(feedback);
        assertThat(original.navigateTo()).isNull();
        ActionResult combined = new ActionResult(true, feedback, "/reports");
        assertThat(combined.refresh()).isTrue();
        assertThat(combined.feedback()).isSameAs(feedback);
        assertThat(combined.navigateTo()).isEqualTo("/reports");
    }

    @Catalog(name = "Sales Customers", title = "Клиенты", tableName = "host_customers")
    static class Customer extends CatalogObject {}

    @Document(name = "SalesInvoices", title = "Счета", tableName = "host_invoices")
    static class Invoice extends DocumentObject {}

    @Catalog(name = "Ambiguous")
    @Document(name = "Ambiguous")
    static class Ambiguous {}
}
