package su.onno.web;

import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.spring5.SpringConnectionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import su.onno.access.AccessSubject;
import su.onno.service.CatalogQueryService;
import su.onno.service.RegisterQueryService;
import su.onno.service.AccessService;
import su.onno.service.EntityListExport;
import su.onno.service.RecordScopeCompiler;
import su.onno.service.EntityAccessPolicy;
import su.onno.service.WebProperties;
import su.onno.service.ServiceException;
import su.onno.access.RecordAccessPolicy;
import su.onno.access.RecordPolicies;
import su.onno.access.RecordScope;
import su.onno.annotations.AccessControl;
import su.onno.annotations.AccumulationRegister;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.annotations.Dimension;
import su.onno.annotations.Resource;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.AccumulationRecord;
import su.onno.model.AccumulationType;
import su.onno.model.CatalogObject;
import su.onno.schema.SchemaGenerator;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EntityListExportTest {
    DataSource dataSource;

    @Catalog(name = "Export clients")
    @AccessControl(readRoles = "READER")
    static class Client extends CatalogObject {
        @Attribute private String region;
        @Attribute private String tier;
        @Attribute private LocalDate openedOn;
        @Attribute(precision = 12, scale = 2) private BigDecimal amount;
        @Attribute(secret = true) private String token;
    }

    @AccumulationRegister(name = "Export stock", type = AccumulationType.BALANCE)
    static class Stock extends AccumulationRecord {
        @Dimension private String site;
        @Resource private BigDecimal amount;
    }

    private static final List<String> NONE = List.of();
    private static final AccessSubject.User READER = AccessSubject.user("reader", Set.of("READER"));
    private static final EntityListExport.Query TERMS = new EntityListExport.Query(
            "_code", true, "match", null, null, List.of("tier,gold"), NONE, NONE, NONE, NONE, NONE, null);
    private static final List<EntityListExport.Column> COLUMNS = List.of(
            new EntityListExport.Column("opened_on", "Opened"),
            new EntityListExport.Column("_code", "Code"),
            new EntityListExport.Column("amount", "Amount"));
    private static final LocalDate OPENED = LocalDate.of(2026, 11, 16);

    private CatalogDescriptor client;
    private Jdbi jdbi;
    private AccessService access;
    private CatalogQueryService query;
    private MetadataRegistry registry;
    private RecordScopeCompiler scopes;

    @BeforeEach
    void setUp() {
        jdbi = createJdbi();
        registry = new MetadataRegistry();
        client = new MetadataScanner(new DefaultNamingStrategy()).scan(Client.class);
        registry.registerCatalog(client);
        new SchemaGenerator(registry).execute(jdbi);
        scopes = new RecordScopeCompiler(new RecordPolicies(registry, List.of(
                RecordAccessPolicy.forCatalog(Client.class).appliesTo("READER")
                        .read(RecordScope.eq("region", "eu")))));
        access = new AccessService(registry);
        query = new CatalogQueryService(registry, jdbi, scopes, access);
    }

    Jdbi createJdbi() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:list_export_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource = ds;
        return Jdbi.create(new SpringConnectionFactory(ds));
    }

    @Test
    void writesAllMatchingAuthorizedRowsInColumnAndSortOrderAcrossKeysetWindows() throws IOException {
        jdbi.useHandle(h -> {
            try (var batch = h.prepareBatch(insertSql())) {
                for (int i = 1; i <= 501; i++) {
                    batch.bind("id", UUID.randomUUID()).bind("code", String.format(Locale.ROOT, "C-%03d", i))
                            .bind("description", "match").bind("region", "eu").bind("tier", "gold").add();
                }
                batch.execute();
            }
        });
        insert("C-999", "match", "us", "gold"); // record policy
        insert("C-998", "match", "eu", "silver"); // column filter
        insert("C-997", "other", "eu", "gold"); // search

        var export = export(501);
        var bytes = new ByteArrayOutputStream();
        ListExportWriter.writeXlsx(bytes, export.headings(), export.rows());
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes.toByteArray()))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getPhysicalNumberOfRows()).isEqualTo(502);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Opened");
            assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("Code");
            assertThat(sheet.getRow(0).getCell(2).getStringCellValue()).isEqualTo("Amount");
            var first = sheet.getRow(1);
            assertThat(DateUtil.isCellDateFormatted(first.getCell(0))).isTrue();
            assertThat(first.getCell(0).getLocalDateTimeCellValue()).isEqualTo(OPENED.atStartOfDay());
            assertThat(first.getCell(2).getNumericCellValue()).isEqualTo(42.25);
            for (int i = 1; i <= 501; i++) {
                assertThat(sheet.getRow(i).getCell(1).getStringCellValue())
                        .isEqualTo(String.format(Locale.ROOT, "C-%03d", 502 - i));
            }
        }
        var grouped = EntityListExport.catalog(query, access, READER, client.logicalName(), TERMS, COLUMNS,
                501, new EntityListExport.Grouping("opened_on", "month"));
        assertThat(grouped.rows()).hasSize(502).first().isEqualTo(java.util.Arrays.asList("2026-11", null, null));
    }

    @Test
    void rejectsLargerResultsAndAllowsAnEmptyResult() {
        assertThat(export(1).rows()).isEmpty();
        insert("C-001", "match", "eu", "gold");
        insert("C-002", "match", "eu", "gold");
        assertThatThrownBy(() -> export(1).rows().iterator().hasNext())
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.status()).isEqualTo(ServiceException.Status.PAYLOAD_TOO_LARGE));
    }

    @Test
    void enforcesTheRowCapBeyondManyWindowsWithoutTruncating() {
        jdbi.useHandle(h -> {
            try (var batch = h.prepareBatch(insertSql())) {
                for (int i = 1; i <= 10_001; i++) {
                    batch.bind("id", UUID.randomUUID()).bind("code", String.format(Locale.ROOT, "C-%05d", i))
                            .bind("description", "match").bind("region", "eu").bind("tier", "gold").add();
                }
                batch.execute();
            }
        });
        assertThatThrownBy(() -> {
            for (var ignored : export(10_000).rows()) { /* consume every window */ }
        }).isInstanceOfSatisfying(ServiceException.class,
                error -> assertThat(error.status()).isEqualTo(ServiceException.Status.PAYLOAD_TOO_LARGE));
        int count = 0;
        for (var ignored : export(10_001).rows()) count++;
        assertThat(count).isEqualTo(10_001);
    }

    @Test
    void cannotSelectSecretFieldsEvenForAdministrators() {
        assertThatThrownBy(() -> EntityListExport.catalog(query, access,
                AccessSubject.user("admin", Set.of("ADMIN")), client.logicalName(), TERMS,
                List.of(new EntityListExport.Column("token", "Token")), 100))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.status()).isEqualTo(ServiceException.Status.BAD_REQUEST));
    }

    @Test
    void honorsBothTheEntityGrantAndAdministratorInclusivePolicies() {
        assertThatThrownBy(() -> EntityListExport.catalog(query, access,
                AccessSubject.user("guest", Set.of("GUEST")), client.logicalName(), TERMS, COLUMNS, 100))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.status()).isEqualTo(ServiceException.Status.FORBIDDEN));

        var denyRead = new EntityAccessPolicy() {
            @Override public boolean includesAdministrators() { return true; }
            @Override public boolean allows(Set<String> roles, String type, String name, boolean write) { return false; }
        };
        var restrictedAccess = new AccessService(registry, () -> List.of(denyRead));
        var restrictedQuery = new CatalogQueryService(registry, jdbi, scopes, restrictedAccess);
        assertThatThrownBy(() -> EntityListExport.catalog(restrictedQuery, restrictedAccess,
                AccessSubject.user("admin", Set.of("ADMIN")), client.logicalName(), TERMS, COLUMNS, 100))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.status()).isEqualTo(ServiceException.Status.FORBIDDEN));
    }

    @Test
    void downloadAppliesGroupingAndRolesAndJournalsOnlyPreparedFiles() throws Exception {
        insert("C-001", "match", "eu", "gold");
        insert("C-002", "match", "eu", "silver");
        insert("C-003", "match", "eu", null);
        insert("C-004", "match", "us", "gold");
        var properties = new WebProperties();
        var journal = new ListExportJournal(jdbi);
        journal.initialize();
        var controller = new ListExportController(query, null, null, null, access, new UiMessages(java.util.Map.of()),
                properties, journal, new DataSourceTransactionManager(dataSource));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ServiceExceptionHandler())
                .setCustomArgumentResolvers(AccessSubjectArgumentResolver.fixed(READER)).build();
        String body = """
                {"format":"csv","columns":[{"key":"_code","label":"Code"}],
                 "query":{"q":["match"],"sort":["_code"],"dir":["desc"]},"groupBy":"tier"}
                """;
        String route = "/api/list/export/catalogs/{name}";
        mvc.perform(post(route, client.logicalName()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        properties.getList().setExportRoles(List.of("ROLE_READER"));
        var result = mvc.perform(post(route, client.logicalName()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse();
        assertThat(result.getContentAsString(java.nio.charset.StandardCharsets.UTF_8).lines()).containsExactly(
                "Code", "gold", "C-001", "silver", "C-002", "—", "C-003");

        properties.getList().setExportRowLimit(2);
        var overCap = mvc.perform(post(route, client.logicalName()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(413)).andReturn().getResponse();
        assertThat(overCap.getHeader("Content-Disposition")).isNull();
        List<String> actors = jdbi.withHandle(h -> h.createQuery("SELECT actor FROM onno_list_export_journal")
                .mapTo(String.class).list());
        assertThat(actors).containsExactly(READER.username());
    }

    @Test
    void downloadKeepsOneSnapshotWhenSortValuesChangeBetweenWindows() throws Exception {
        jdbi.useHandle(h -> {
            try (var batch = h.prepareBatch(insertSql())) {
                for (int i = 1; i <= 501; i++) {
                    batch.bind("id", UUID.randomUUID()).bind("code", String.format(Locale.ROOT, "C-%03d", i))
                            .bind("description", "match").bind("region", "eu").bind("tier", "gold").add();
                }
                batch.execute();
            }
        });
        var changed = new java.util.concurrent.atomic.AtomicBoolean();
        jdbi.setSqlLogger(new org.jdbi.v3.core.statement.SqlLogger() {
            @Override
            public void logAfterExecution(org.jdbi.v3.core.statement.StatementContext context) {
                String sql = context.getRenderedSql();
                if (sql.contains(client.tableName()) && sql.contains("LIMIT") && changed.compareAndSet(false, true)) {
                    // An independent connection simulates another user's committed edit after page 1.
                    try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                        statement.executeUpdate("UPDATE " + client.tableName() + " SET _code='ZZZ' WHERE _code='C-001'");
                    } catch (java.sql.SQLException error) {
                        throw new IllegalStateException(error);
                    }
                }
            }
        });
        var properties = new WebProperties();
        properties.getList().setExportRoles(List.of("READER"));
        var journal = new ListExportJournal(jdbi);
        journal.initialize();
        var controller = new ListExportController(query, null, null, null, access, new UiMessages(java.util.Map.of()),
                properties, journal, new DataSourceTransactionManager(dataSource));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ServiceExceptionHandler())
                .setCustomArgumentResolvers(AccessSubjectArgumentResolver.fixed(READER)).build();
        var response = mvc.perform(post("/api/list/export/catalogs/{name}", client.logicalName())
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"format":"csv","columns":[{"key":"_code","label":"Code"}],
                     "query":{"sort":["_code"],"dir":["asc"]}}
                    """))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(changed).isTrue();
        var codes = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8).lines().skip(1).toList();
        assertThat(codes).hasSize(501).doesNotHaveDuplicates().doesNotContain("ZZZ");
        assertThat(codes.getFirst()).isEqualTo("C-001");
        assertThat(codes.getLast()).isEqualTo("C-501");
    }

    @Test
    void rejectsMalformedAndUnknownParametersWithoutPreparingAFile() throws Exception {
        var properties = new WebProperties();
        properties.getList().setExportRoles(List.of("READER"));
        var journal = new ListExportJournal(jdbi);
        journal.initialize();
        var controller = new ListExportController(query, null, null, null, access,
                new UiMessages(java.util.Map.of()), properties, journal, new DataSourceTransactionManager(dataSource));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ServiceExceptionHandler())
                .setCustomArgumentResolvers(AccessSubjectArgumentResolver.fixed(READER)).build();
        for (String body : List.of(
                "{}",
                "{\"format\":\"pdf\",\"columns\":[{\"key\":\"_code\",\"label\":\"Code\"}]}",
                "{\"format\":\"csv\",\"columns\":[]}",
                "{\"format\":\"csv\",\"columns\":[null]}",
                "{\"format\":\"csv\",\"columns\":[{\"key\":\"unknown\",\"label\":\"Code\"}]}",
                "{\"format\":\"csv\",\"columns\":[{\"key\":\"token\",\"label\":\"Secret\"}]}",
                "{\"format\":\"csv\",\"columns\":[{\"key\":\"_code\",\"label\":\"Code\"}],\"groupBy\":\"token\"}",
                "{\"format\":\"csv\",\"columns\":[{\"key\":\"_code\",\"label\":\"Code\"}],\"query\":{\"q\":[null]}}"
        )) {
            var response = mvc.perform(post("/api/list/export/catalogs/{name}", client.logicalName())
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andReturn().getResponse();
            assertThat(response.getHeader("Content-Disposition")).isNull();
        }
        Long count = jdbi.withHandle(h -> h.createQuery("SELECT COUNT(*) FROM onno_list_export_journal")
                .mapTo(Long.class).one());
        assertThat(count).isZero();
    }

    @Test
    void exportsFilteredRegisterMovementsAndBalances() {
        var stock = new MetadataScanner(new DefaultNamingStrategy()).scanRegister(Stock.class);
        registry.registerAccumulation(stock);
        new SchemaGenerator(registry).execute(jdbi);
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO " + stock.tableName()
                    + " (_id,_period,_active,_movement_type,site,amount) VALUES "
                    + "(?, TIMESTAMP '2026-11-16 01:00:00',true,'RECEIPT','A',8),"
                    + "(?, TIMESTAMP '2026-11-16 02:00:00',true,'EXPENSE','B',5)",
                    UUID.randomUUID(), UUID.randomUUID());
            h.execute("INSERT INTO " + stock.totalsTableName() + " (site,amount) VALUES ('A',8),('B',5)");
        });
        var registers = new RegisterQueryService(registry, jdbi);
        var admin = AccessSubject.user("admin", Set.of("ADMIN"));
        var messages = new UiMessages(java.util.Map.of());
        var terms = new EntityListExport.Query("amount", false, null, null, null,
                NONE, NONE, NONE, NONE, NONE, NONE, "{\"site\":\"A\"}");
        var movementColumns = List.of(new EntityListExport.Column("_period", "Period"),
                new EntityListExport.Column("_movement_type", "Type"));
        var movements = EntityListExport.register(registers, access, admin, java.util.Map.of("RECEIPT", messages.get("register.receipt"), "EXPENSE", messages.get("register.expense")),
                stock.logicalName(), terms, movementColumns, 5, true, null);
        assertThat(movements.rows()).containsExactly(List.of(OPENED.atTime(1, 0), messages.get("register.receipt")));
        var balanceColumns = List.of(new EntityListExport.Column("site", "Site"),
                new EntityListExport.Column("amount", "Amount"));
        var balances = EntityListExport.register(registers, access, admin, java.util.Map.of("RECEIPT", messages.get("register.receipt"), "EXPENSE", messages.get("register.expense")),
                stock.logicalName(), terms, balanceColumns, 5, false, null);
        assertThat(balances.rows()).singleElement().satisfies(row -> {
            assertThat(row.get(0)).isEqualTo("A");
            assertThat((BigDecimal) row.get(1)).isEqualByComparingTo("8");
        });
    }

    private EntityListExport.Data export(int cap) {
        return EntityListExport.catalog(query, access, READER, client.logicalName(), TERMS, COLUMNS, cap);
    }

    private void insert(String code, String description, String region, String tier) {
        jdbi.useHandle(h -> h.createUpdate(insertSql())
                .bind("id", UUID.randomUUID()).bind("code", code).bind("description", description)
                .bind("region", region).bind("tier", tier).execute());
    }

    private String insertSql() {
        return "INSERT INTO " + client.tableName()
                + " (_id, _code, _description, _deletion_mark, region, tier, opened_on, amount, token)"
                + " VALUES (:id, :code, :description, false, :region, :tier, DATE '2026-11-16', 42.25, 'internal')";
    }
}
