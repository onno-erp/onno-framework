package su.onno.importer;

import su.onno.metadata.CatalogDescriptor;
import su.onno.ui.CatalogCommandService;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogCsvImportServiceTest {

    private final CatalogCommandService commands = mock(CatalogCommandService.class);
    private final OnnoImportProperties properties = new OnnoImportProperties();
    private final CatalogCsvImportService service =
            new CatalogCsvImportService(mock(Jdbi.class), commands, properties);

    private final CatalogDescriptor clients = new CatalogDescriptor(
            "Clients", "Clients", "cat_clients", Object.class, 9, false, true, "C-", "Sales",
            List.of("ADMIN"), List.of("ADMIN"), List.of());

    @Test
    void preview_returnsHeadersSampleRowsAndTotalCount() {
        properties.setPreviewRows(1);
        byte[] csv = """
                Code,Name
                C-001,Alice
                C-002,Bob
                """.getBytes(StandardCharsets.UTF_8);

        CsvPreview preview = service.preview(csv, null);

        assertThat(preview.headers()).containsExactly("Code", "Name");
        assertThat(preview.rows()).containsExactly(Map.of("Code", "C-001", "Name", "Alice"));
        assertThat(preview.totalRows()).isEqualTo(2);
    }

    @Test
    void preview_stripsUtf8BomFromFirstHeader() {
        byte[] csv = "\uFEFFCode,Name\nC-001,Alice\n".getBytes(StandardCharsets.UTF_8);

        CsvPreview preview = service.preview(csv, null);

        assertThat(preview.headers()).containsExactly("Code", "Name");
        assertThat(preview.rows()).containsExactly(Map.of("Code", "C-001", "Name", "Alice"));
    }

    @Test
    void importCreateOnly_mapsFieldsAndDelegatesToCatalogCommands() {
        byte[] csv = """
                Code,Name
                C-001,Alice
                """.getBytes(StandardCharsets.UTF_8);
        when(commands.create(eq(clients), any(), any())).thenReturn(Map.of("_id", "new"));

        ImportResult result = service.importCatalog(clients, csv, null,
                Map.of("code", "Code", "description", "Name"),
                CatalogImportMode.CREATE_ONLY, false, null);

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        verify(commands).create(eq(clients), eq(Map.of("code", "C-001", "description", "Alice")), any());
    }

    @Test
    void dryRun_countsRowsWithoutWriting() {
        byte[] csv = """
                Code,Name
                C-001,Alice
                """.getBytes(StandardCharsets.UTF_8);

        ImportResult result = service.importCatalog(clients, csv, null,
                Map.of("code", "Code", "description", "Name"),
                CatalogImportMode.CREATE_ONLY, true, null);

        assertThat(result.dryRun()).isTrue();
        assertThat(result.created()).isEqualTo(1);
        verify(commands, never()).create(any(), any(), any());
    }

    @Test
    void upsertByCode_updatesExistingRows() {
        UUID existingId = UUID.randomUUID();
        Jdbi jdbi = jdbi();
        jdbi.useHandle(h -> {
            h.execute("CREATE TABLE cat_clients (_id UUID PRIMARY KEY, _code VARCHAR(20), _deletion_mark BOOLEAN)");
            h.createUpdate("INSERT INTO cat_clients (_id, _code, _deletion_mark) VALUES (:id, 'C-001', false)")
                    .bind("id", existingId)
                    .execute();
        });
        CatalogCsvImportService upsertService = new CatalogCsvImportService(jdbi, commands, properties);
        byte[] csv = """
                Code,Name
                C-001,Alice Updated
                """.getBytes(StandardCharsets.UTF_8);
        when(commands.update(eq(clients), eq(existingId), any(), any())).thenReturn(Map.of("_id", existingId));

        ImportResult result = upsertService.importCatalog(clients, csv, null,
                Map.of("code", "Code", "description", "Name"),
                CatalogImportMode.UPSERT_BY_CODE, false, null);

        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.created()).isZero();
        verify(commands).update(eq(clients), eq(existingId),
                eq(Map.of("code", "C-001", "description", "Alice Updated")), any());
    }

    private static Jdbi jdbi() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return Jdbi.create(ds);
    }
}
