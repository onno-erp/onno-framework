package su.onno.ui;

import su.onno.access.AccessSubject;
import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Document;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.DocumentObject;
import su.onno.period.PeriodCloseAction;
import su.onno.period.PeriodCloseExempt;
import su.onno.period.PeriodCloseService;
import su.onno.period.PeriodClosedException;
import su.onno.period.PeriodScope;
import su.onno.schema.SchemaGenerator;
import su.onno.security.SecretCipher;
import su.onno.ui.period.PeriodCloseAccess;
import su.onno.ui.period.PeriodCloseController;
import su.onno.ui.period.PeriodClosePage;
import su.onno.ui.period.PeriodClosedExceptionHandler;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Period close on the generic UI/REST command path (which writes through JDBI, not the repository),
 * plus the admin API, the built-in page and the role-based override.
 */
class PeriodCloseUiTest {

    @Document(name = "PeriodInvoices", numberPrefix = "PI-", context = "Finance")
    @AccessControl(readRoles = "ACCOUNTANT", writeRoles = "ACCOUNTANT")
    @SuppressWarnings("unused") // Reflective model fixture.
    public static class PeriodInvoice extends DocumentObject {
        @Attribute(length = 60) private String title;
    }

    @Document(name = "PeriodEvents", numberPrefix = "PE-")
    @AccessControl(readRoles = "ACCOUNTANT", writeRoles = "ACCOUNTANT")
    @PeriodCloseExempt
    @SuppressWarnings("unused") // Reflective model fixture.
    public static class PeriodEvent extends DocumentObject {
        @Attribute(length = 60) private String title;
    }

    private final AccessSubject accountant = AccessSubject.user("ann", Set.of("ACCOUNTANT"));
    private DocumentDescriptor invoices;
    private DocumentDescriptor events;
    private PeriodCloseService periods;
    private DocumentCommandService commands;
    private UiAccessService access;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        Jdbi jdbi = Jdbi.create(ds);

        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        MetadataRegistry registry = new MetadataRegistry();
        invoices = scanner.scanDocument(PeriodInvoice.class);
        events = scanner.scanDocument(PeriodEvent.class);
        registry.registerDocument(invoices);
        registry.registerDocument(events);
        new SchemaGenerator(registry).execute(jdbi);

        periods = new PeriodCloseService(jdbi, registry);
        periods.ensureSchema();
        access = new UiAccessService(registry);
        commands = new DocumentCommandService(registry, jdbi, new UiProperties(),
                new WriteLifecycleCommandServiceTest.SequentialNumbers(), null,
                new DocumentQueryService(registry, jdbi), access, event -> { }, new SecretCipher(null));
        commands.setPeriodCloseService(periods);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void commandPath_rejectsCreateUpdateAndDeleteInsideTheClosedPeriod() {
        UUID january = id(commands.create(invoices,
                Map.of("title", "January", "date", "2026-01-15T10:00:00"), accountant));
        UUID february = id(commands.create(invoices,
                Map.of("title", "February", "date", "2026-02-15T10:00:00"), accountant));
        periods.close(PeriodScope.global(), LocalDate.of(2026, 1, 31), "admin", null);

        assertThatThrownBy(() -> commands.create(invoices,
                Map.of("title", "Late", "date", "2026-01-31T23:00:00"), accountant))
                .isInstanceOf(PeriodClosedException.class);
        assertThatThrownBy(() -> commands.update(invoices, january, Map.of("title", "Edited"), accountant))
                .isInstanceOf(PeriodClosedException.class)
                .hasMessageContaining("cannot change PeriodInvoices dated 2026-01-15");
        assertThatThrownBy(() -> commands.update(invoices, january,
                Map.of("date", "2026-02-02T10:00:00"), accountant))
                .isInstanceOf(PeriodClosedException.class);
        assertThatThrownBy(() -> commands.update(invoices, february,
                Map.of("date", "2026-01-02T10:00:00"), accountant))
                .isInstanceOf(PeriodClosedException.class);
        assertThatThrownBy(() -> commands.delete(invoices, january, accountant))
                .isInstanceOf(PeriodClosedException.class);

        assertThat(commands.update(invoices, february, Map.of("title", "Edited"), accountant).get("title"))
                .isEqualTo("Edited");
        commands.create(invoices, Map.of("title", "Open", "date", "2026-02-01T00:00:00"), accountant);
        commands.delete(invoices, february, accountant);
    }

    @Test
    void exemptDocumentType_isNeverChecked() {
        periods.close(PeriodScope.global(), LocalDate.of(2026, 1, 31), "admin", null);

        UUID event = id(commands.create(events,
                Map.of("title", "Meeting", "date", "2026-01-15T10:00:00"), accountant));
        commands.update(events, event, Map.of("title", "Moved"), accountant);
        commands.delete(events, event, accountant);
    }

    @Test
    void overrideRole_bypassesTheClose_andIsAudited() {
        PeriodCloseAccess policy = new PeriodCloseAccess(access, List.of(), List.of("CHIEF_ACCOUNTANT"));
        periods.setOverridePolicy(policy);
        periods.close(PeriodScope.global(), LocalDate.of(2026, 1, 31), "admin", null);
        Map<String, Object> body = Map.of("title", "Correction", "date", "2026-01-15T10:00:00");

        signIn("ann", "ACCOUNTANT");
        assertThatThrownBy(() -> commands.create(invoices, body, accountant))
                .isInstanceOf(PeriodClosedException.class);
        // ADMIN is not exempt unless listed.
        signIn("root", "ADMIN");
        assertThatThrownBy(() -> commands.create(invoices, body, accountant))
                .isInstanceOf(PeriodClosedException.class);

        signIn("vera", "CHIEF_ACCOUNTANT");
        commands.create(invoices, body, accountant);
        assertThat(periods.audit(10)).filteredOn(e -> e.action() == PeriodCloseAction.OVERRIDE)
                .singleElement()
                .satisfies(entry -> assertThat(entry.actor()).isEqualTo("vera"));
    }

    @Test
    void controller_gatesCloseAndReopenOnManageRoles() {
        PeriodCloseAccess policy = new PeriodCloseAccess(access, List.of("CHIEF_ACCOUNTANT"), List.of());
        PeriodCloseController controller =
                new PeriodCloseController(periods, policy, access, new UiProperties());
        Authentication ann = token("ann", "ACCOUNTANT");
        Authentication vera = token("vera", "CHIEF_ACCOUNTANT");
        Authentication root = token("root", "ADMIN");

        assertThatThrownBy(() -> controller.close(
                Map.of("scope", "global", "closedThrough", "2026-01-31"), ann))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
        assertThatThrownBy(() -> controller.audit(10, ann)).isInstanceOf(ResponseStatusException.class);

        assertThat(controller.close(Map.of("scope", "context:Finance", "closedThrough", "2026-01-31",
                "reason", "month end"), vera))
                .containsEntry("scope", "context:Finance")
                .containsEntry("closedThrough", "2026-01-31")
                .containsEntry("updatedBy", "vera");
        assertThat(controller.state(ann)).containsEntry("canManage", false);
        assertThat((List<?>) controller.state(ann).get("locks")).hasSize(1);
        assertThat(controller.state(root)).containsEntry("canManage", true);

        assertThatThrownBy(() -> controller.reopen(Map.of("scope", "context:Finance"), vera))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        assertThatThrownBy(() -> controller.close(
                Map.of("scope", "register:Nope", "closedThrough", "2026-01-31"), vera))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        assertThat(controller.reopen(Map.of("scope", "context:Finance", "reason", "late invoice"), root))
                .containsEntry("closedThrough", null);
        assertThat(controller.audit(10, vera)).extracting(row -> row.get("action"))
                .containsExactlyInAnyOrder("CLOSE", "REOPEN");
    }

    @Test
    void page_showsLocks_andItsActionsCloseAndReopen() {
        PeriodCloseAccess policy = new PeriodCloseAccess(access, List.of("CHIEF_ACCOUNTANT"), List.of());
        PeriodClosePage page = new PeriodClosePage(periods, policy);

        PageBuilder empty = new PageBuilder();
        page.compose(empty);
        assertThat(texts(empty)).contains("No period is closed.");
        ActionSpec.Action close = empty.pageAction("closePeriod");
        ActionSpec.Action reopen = empty.pageAction("reopenPeriod");
        assertThat(close.roles()).containsExactly("ADMIN", "CHIEF_ACCOUNTANT");
        assertThat(close.form().getFirst().options())
                .containsExactly("global", "context:Finance");

        ActionResult closed = close.handler().apply(context(Map.of(
                "scope", "context:Finance", "closedThrough", "2026-01-31", "reason", "month end")));
        assertThat(closed.refresh()).isTrue();
        assertThat(periods.closedThrough(PeriodScope.context("Finance"))).contains(LocalDate.of(2026, 1, 31));

        // A reopen without a reason is refused with a toast, not an exception.
        ActionResult refused = reopen.handler().apply(context(Map.of("scope", "context:Finance")));
        assertThat(refused.refresh()).isFalse();
        assertThat(periods.locks()).hasSize(1);

        PageBuilder withLock = new PageBuilder();
        page.compose(withLock);
        assertThat(texts(withLock)).anyMatch(t -> t.startsWith("Context Finance: closed through 2026-01-31 by vera"));
        assertThat(texts(withLock)).anyMatch(t -> t.contains("closed context Finance through 2026-01-31")
                && t.endsWith("by vera: month end"));

        reopen.handler().apply(context(Map.of("scope", "context:Finance", "reason", "late invoice")));
        assertThat(periods.locks()).isEmpty();
    }

    @Test
    void exceptionHandler_mapsToConflictWithTheClosedPeriod() {
        periods.close(PeriodScope.global(), LocalDate.of(2026, 1, 31), "admin", null);
        PeriodClosedException rejection = (PeriodClosedException) catching(() -> commands.create(invoices,
                Map.of("title", "Late", "date", "2026-01-15T10:00:00"), accountant));

        var response = new PeriodClosedExceptionHandler().handle(rejection);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody())
                .containsEntry("error", "period-closed")
                .containsEntry("scope", "global")
                .containsEntry("closedThrough", "2026-01-31")
                .containsEntry("operation", "CREATE");
        assertThat((String) response.getBody().get("message")).contains("reopen the period");
    }

    private static ActionContext context(Map<String, String> inputs) {
        return new ActionContext("pages", PeriodClosePage.ROUTE, null, "vera", inputs, Map.of(),
                AccessSubject.user("vera", Set.of("CHIEF_ACCOUNTANT")));
    }

    private static List<String> texts(PageBuilder builder) {
        return builder.components().stream()
                .filter(c -> c.kind() == PageComponent.Kind.TEXT)
                .map(PageComponent::text)
                .toList();
    }

    private static UUID id(Map<String, Object> row) {
        return UUID.fromString(row.get("_id").toString());
    }

    private static void signIn(String name, String... roles) {
        SecurityContextHolder.getContext().setAuthentication(token(name, roles));
    }

    private static Authentication token(String name, String... roles) {
        UserDetails user = User.withUsername(name).password("x").roles(roles).build();
        return new UsernamePasswordAuthenticationToken(user, "x", user.getAuthorities());
    }

    private static Throwable catching(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable thrown) {
            return thrown;
        }
    }
}
